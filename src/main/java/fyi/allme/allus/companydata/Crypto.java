package fyi.allme.allus.companydata;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.operator.InputDecryptorProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCSException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.StringReader;
import java.security.PrivateKey;
import java.security.Security;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;

/**
 * Decryption core — byte-identical to the platform's Web Crypto encryption and to
 * every other SDK port.
 *
 * <p>Each person value arrives as a hybrid ciphertext wrapper, encrypted <em>for
 * the service public key</em>; the SDK decrypts with the service private key:
 *
 * <pre>
 * wrapper = {"_enc":1,
 *            "k":  base64(rsa_oaep_sha256(aesKey, servicePublicKey)),
 *            "iv": base64(iv12),
 *            "d":  base64(aes256gcm_ciphertext_with_tag)}
 *
 * decrypt(wrapper, servicePrivateKey):
 *   aesKey    = RSA-OAEP(SHA-256, MGF1-SHA256) decrypt wrapper.k   // 32 bytes
 *   plaintext = AES-256-GCM decrypt wrapper.d with aesKey, iv=wrapper.iv
 *               // the 16-byte GCM tag is the LAST 16 bytes of d
 *   return utf8(plaintext)
 * </pre>
 *
 * <p><b>The two JCE footguns this class pins:</b>
 * <ol>
 *   <li><b>OAEP MGF1.</b> {@code Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")}
 *       alone uses <b>MGF1-SHA1</b> even though the digest is SHA-256. We init the
 *       cipher with an explicit {@link OAEPParameterSpec} carrying
 *       {@code MGF1ParameterSpec.SHA256}, so MGF1 is SHA-256 — matching Web Crypto
 *       {@code RSA-OAEP/SHA-256}. (The account-key webhook envelope uses the
 *       SHA-1 default — see {@link Webhooks}.)</li>
 *   <li><b>PBES2 PEM load.</b> The service PEM is PBES2 (PBKDF2-HMAC-SHA256 +
 *       AES-256-CBC, 100k iters). Pure-JDK PKCS#8 decryption of that profile is
 *       fiddly, so we use BouncyCastle:
 *       {@code PEMParser → PKCS8EncryptedPrivateKeyInfo → InputDecryptorProvider}
 *       from {@code JceOpenSSLPKCS8DecryptorProviderBuilder}, then
 *       {@code JcaPEMKeyConverter}.</li>
 * </ol>
 */
public final class Crypto {
    /** GCM tag length in bytes — appended to the AES-GCM ciphertext. */
    public static final int GCM_TAG_LEN = 16;
    /** GCM IV length in bytes. */
    public static final int GCM_IV_LEN = 12;

    private static final BouncyCastleProvider BC = new BouncyCastleProvider();

    static {
        // Register BouncyCastle if it isn't already, so the PKCS#8 decryptor and
        // its PBKDF2/AES-CBC building blocks resolve. Idempotent.
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BC);
        }
    }

    private Crypto() {
    }

    /**
     * Load an OpenSSL-encrypted PKCS#8 PEM into an in-memory RSA private key.
     *
     * <p>The PEM is PBES2 (PBKDF2-HMAC-SHA256 + AES-256-CBC). BouncyCastle handles
     * the SHA-256 PRF cleanly. The key is never written back to disk in plaintext.
     *
     * <p>Config-only key handling: the passphrase is driven by
     * {@code Config.keyPassphrase()} / {@code Config.accountPassphrase()} — never
     * passed in by application code.
     *
     * @throws DecryptException on a wrong passphrase, malformed PEM, or a non-RSA key.
     */
    public static RSAPrivateKey loadPrivateKey(byte[] encryptedPemBytes, String passphrase) {
        String pem = new String(encryptedPemBytes, java.nio.charset.StandardCharsets.UTF_8);
        char[] pw = (passphrase != null ? passphrase : "").toCharArray();
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object parsed = parser.readObject();
            if (parsed == null) {
                throw new DecryptException("PEM contained no object");
            }
            PrivateKeyInfo keyInfo;
            if (parsed instanceof PKCS8EncryptedPrivateKeyInfo encrypted) {
                InputDecryptorProvider decryptor =
                    new JceOpenSSLPKCS8DecryptorProviderBuilder()
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                        .build(pw);
                keyInfo = encrypted.decryptPrivateKeyInfo(decryptor);
            } else if (parsed instanceof PrivateKeyInfo info) {
                // An unencrypted PKCS#8 PEM (defensive — the service PEM is always
                // encrypted, but tolerate a plain one rather than crashing).
                keyInfo = info;
            } else {
                throw new DecryptException(
                    "PEM did not contain an (encrypted) PKCS#8 private key: "
                        + parsed.getClass().getName());
            }
            PrivateKey key = new JcaPEMKeyConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getPrivateKey(keyInfo);
            if (!(key instanceof RSAPrivateKey rsa)) {
                throw new DecryptException("PEM did not contain an RSA private key");
            }
            return rsa;
        } catch (PKCSException exc) {
            // A wrong passphrase surfaces here (decrypt failure / pad check).
            throw new DecryptException("could not load private key PEM (wrong passphrase?): "
                + exc.getMessage(), exc);
        } catch (OperatorCreationException | IOException exc) {
            throw new DecryptException("could not load private key PEM: " + exc.getMessage(), exc);
        }
    }

    /**
     * Decrypt a platform {@code {"_enc":1,k,iv,d}} wrapper to a UTF-8 plaintext
     * string.
     *
     * <p>For a <em>text</em> value the plaintext is the value itself. For a
     * <em>binary</em> value the plaintext is a JSON envelope STRING (photo:
     * {@code {"full":"data:...","thumb":...}}; document:
     * {@code {"file":"data:...","original_name":...}}) — NOT raw bytes. The
     * envelope→data-URI→bytes parse lives on {@link BinaryHandle}; here we only
     * ever decrypt to that envelope string.
     *
     * @throws DecryptException on a malformed wrapper, the wrong key, or a GCM tag mismatch.
     */
    public static String decrypt(Wrapper wrapper, RSAPrivateKey privateKey) {
        if (wrapper == null) {
            throw new DecryptException("wrapper must not be null");
        }
        byte[] encKey = b64decode(wrapper.k(), "k");
        byte[] iv = b64decode(wrapper.iv(), "iv");
        byte[] ciphertextWithTag = b64decode(wrapper.d(), "d");

        if (iv.length != GCM_IV_LEN) {
            throw new DecryptException("iv must be " + GCM_IV_LEN + " bytes, got " + iv.length);
        }
        if (ciphertextWithTag.length < GCM_TAG_LEN) {
            throw new DecryptException("ciphertext too short to contain a GCM tag");
        }

        // 1) RSA-OAEP(SHA-256, MGF1-SHA256) unwrap the AES key. The explicit
        //    OAEPParameterSpec pins MGF1 to SHA-256 (SunJCE would default it to
        //    SHA-1, which would fail) — matches Web Crypto RSA-OAEP/SHA-256.
        byte[] aesKey = rsaOaepDecrypt(encKey, privateKey, "SHA-256", MGF1ParameterSpec.SHA256);
        if (aesKey.length != 32) {
            throw new DecryptException(
                "unwrapped AES key must be 32 bytes (AES-256), got " + aesKey.length);
        }

        // 2) AES-256-GCM decrypt. Java's GCM expects the 16-byte tag appended to
        //    the ciphertext, which is exactly the platform's layout — pass d as-is.
        byte[] plaintext = aesGcmDecrypt(aesKey, iv, ciphertextWithTag);
        return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * RSA-OAEP decrypt with a pinned OAEP digest and MGF1 hash.
     *
     * <p>Used with SHA-256/MGF1-SHA256 for service-key field values (the normal
     * path) and with SHA-1/MGF1-SHA1 for the account-key webhook envelope (see
     * {@link Webhooks}). The explicit {@link OAEPParameterSpec} is the only way to
     * control MGF1 on SunJCE (the transform string's hash sets only the OAEP digest).
     */
    static byte[] rsaOaepDecrypt(byte[] data, RSAPrivateKey key, String oaepDigest,
                                 MGF1ParameterSpec mgf1) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            OAEPParameterSpec spec = new OAEPParameterSpec(
                oaepDigest, "MGF1", mgf1, PSource.PSpecified.DEFAULT);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            return cipher.doFinal(data);
        } catch (java.security.GeneralSecurityException exc) {
            throw new DecryptException(
                "RSA-OAEP unwrap failed (wrong key?): " + exc.getMessage(), exc);
        }
    }

    /** AES-256-GCM decrypt; {@code ciphertextWithTag} carries the 16-byte tag appended. */
    static byte[] aesGcmDecrypt(byte[] aesKey, byte[] iv, byte[] ciphertextWithTag) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LEN * 8, iv);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), spec);
            return cipher.doFinal(ciphertextWithTag);
        } catch (javax.crypto.AEADBadTagException exc) {
            throw new DecryptException("AES-GCM tag mismatch (wrong key or corrupt data)", exc);
        } catch (java.security.GeneralSecurityException exc) {
            throw new DecryptException("AES-GCM decrypt failed: " + exc.getMessage(), exc);
        }
    }

    static byte[] b64decode(String value, String fieldName) {
        if (value == null) {
            throw new DecryptException("wrapper missing required field '" + fieldName + "'");
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exc) {
            throw new DecryptException("wrapper field '" + fieldName + "' is not valid base64", exc);
        }
    }
}
