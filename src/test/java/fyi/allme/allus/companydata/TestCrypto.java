package fyi.allme.allus.companydata;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test-only encryption helpers — encrypt a plaintext into a platform wrapper with
 * a known public key, so model/client/webhook tests can build values that decrypt
 * back to known content through the SAME crypto core. Mirrors the Python tests'
 * {@code encrypt_for_key} / {@code _wrap_to_account_key} fixtures.
 */
final class TestCrypto {
    private static final SecureRandom RNG = new SecureRandom();

    private TestCrypto() {
    }

    /** Encrypt plaintext into a {@code {"_enc":1,k,iv,d}} wrapper map, OAEP-SHA256. */
    static Map<String, Object> encryptForKey(RSAPublicKey publicKey, String plaintext) {
        return wrap(publicKey, plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "SHA-256", MGF1ParameterSpec.SHA256);
    }

    /** Encrypt with the account-key path: OAEP-SHA1 (OpenSSL's default MGF1-SHA1). */
    static Map<String, Object> wrapToAccountKey(RSAPublicKey publicKey, byte[] plaintext) {
        return wrap(publicKey, plaintext, "SHA-1", MGF1ParameterSpec.SHA1);
    }

    private static Map<String, Object> wrap(RSAPublicKey publicKey, byte[] plaintext,
                                             String oaepDigest, MGF1ParameterSpec mgf1) {
        try {
            byte[] aesKey = new byte[32];
            RNG.nextBytes(aesKey);
            byte[] iv = new byte[12];
            RNG.nextBytes(iv);

            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(aesKey, "AES"),
                new GCMParameterSpec(128, iv));
            byte[] ct = gcm.doFinal(plaintext); // tag appended

            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
            rsa.init(Cipher.ENCRYPT_MODE, publicKey,
                new OAEPParameterSpec(oaepDigest, "MGF1", mgf1, PSource.PSpecified.DEFAULT));
            byte[] k = rsa.doFinal(aesKey);

            Map<String, Object> w = new LinkedHashMap<>();
            w.put("_enc", 1);
            w.put("k", Base64.getEncoder().encodeToString(k));
            w.put("iv", Base64.getEncoder().encodeToString(iv));
            w.put("d", Base64.getEncoder().encodeToString(ct));
            return w;
        } catch (GeneralSecurityException exc) {
            throw new RuntimeException(exc);
        }
    }

    /** Derive the matching RSA public key from a CRT private key (vector key). */
    static RSAPublicKey publicKeyOf(java.security.interfaces.RSAPrivateKey priv) {
        try {
            if (!(priv instanceof java.security.interfaces.RSAPrivateCrtKey crt)) {
                throw new IllegalStateException("private key is not a CRT key; cannot derive public");
            }
            java.security.spec.RSAPublicKeySpec spec =
                new java.security.spec.RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent());
            return (RSAPublicKey) java.security.KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (GeneralSecurityException exc) {
            throw new RuntimeException(exc);
        }
    }

    /** The base64 SPKI/DER encoding of a public key (what GET /api/keys returns). */
    static String spkiB64(RSAPublicKey pub) {
        // JCA's RSAPublicKey.getEncoded() is X.509 SubjectPublicKeyInfo (SPKI/DER).
        return Base64.getEncoder().encodeToString(pub.getEncoded());
    }

    /** Generate a throwaway RSA-2048 keypair for the account-key webhook tests. */
    static KeyPair generateRsa2048() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (GeneralSecurityException exc) {
            throw new RuntimeException(exc);
        }
    }

    /**
     * Write an encrypted PKCS#8 PEM (PBES2: PBKDF2-HMAC-SHA256 + AES-256-CBC) for a
     * private key — the same profile {@code Crypto.loadPrivateKey} reads. Used by the
     * webhook account-key tests so the account PEM loads through BouncyCastle.
     */
    static String encryptedPkcs8Pem(java.security.PrivateKey key, String passphrase) {
        try {
            org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder builder =
                new org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder(
                    org.bouncycastle.asn1.nist.NISTObjectIdentifiers.id_aes256_CBC);
            builder.setProvider("BC");
            builder.setIterationCount(100_000);
            // PBKDF2 PRF = HMAC-SHA256 (matches the platform's pkcs8.js PBES2 profile).
            builder.setPRF(new org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.id_hmacWithSHA256,
                org.bouncycastle.asn1.DERNull.INSTANCE));
            builder.setPassword(passphrase.toCharArray());
            org.bouncycastle.operator.OutputEncryptor encryptor = builder.build();
            org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo encrypted =
                new org.bouncycastle.pkcs.jcajce.JcaPKCS8EncryptedPrivateKeyInfoBuilder(key).build(encryptor);
            java.io.StringWriter sw = new java.io.StringWriter();
            try (org.bouncycastle.openssl.jcajce.JcaPEMWriter pw =
                     new org.bouncycastle.openssl.jcajce.JcaPEMWriter(sw)) {
                pw.writeObject(new org.bouncycastle.util.io.pem.PemObject(
                    "ENCRYPTED PRIVATE KEY", encrypted.getEncoded()));
            }
            return sw.toString();
        } catch (Exception exc) {
            throw new RuntimeException(exc);
        }
    }

    /** HMAC-SHA256 hex of a body with a secret (mirrors PHP hash_hmac). */
    static String hmacHex(String secret, byte[] body) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException exc) {
            throw new RuntimeException(exc);
        }
    }
}
