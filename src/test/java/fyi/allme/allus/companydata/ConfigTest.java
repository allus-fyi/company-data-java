package fyi.allme.allus.companydata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Config loader tests. Mirrors the Python reference's test_config. */
class ConfigTest {

    private static Path write(Path dir, Map<String, Object> data) throws Exception {
        Path p = dir.resolve("config.json");
        Files.writeString(p, fyi.allme.allus.companydata.internal.Json.write(data), StandardCharsets.UTF_8);
        return p;
    }

    private static Map<String, Object> full() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("api_url", "https://api.allme.fyi");
        m.put("client_id", "svc_abc");
        m.put("client_secret", "file-secret");
        m.put("service_private_key", "./service-CRM.pem");
        m.put("key_passphrase", "file-passphrase");
        m.put("account_private_key", "./account.pem");
        m.put("account_passphrase", "acct-pass");
        m.put("webhooks", Map.of("wh_1", "secret-one", "wh_2", "secret-two"));
        m.put("cache_dir", "./allus-cache");
        m.put("format", "json");
        return m;
    }

    @Test
    void fromFileLoadsAllFields(@TempDir Path tmp) throws Exception {
        Config cfg = Config.fromFile(write(tmp, full()).toString());
        assertEquals("https://api.allme.fyi", cfg.apiUrl());
        assertEquals("svc_abc", cfg.clientId());
        assertEquals("file-secret", cfg.clientSecret());
        assertEquals("./service-CRM.pem", cfg.servicePrivateKey());
        assertEquals("file-passphrase", cfg.keyPassphrase());
        assertEquals("./account.pem", cfg.accountPrivateKey());
        assertEquals("acct-pass", cfg.accountPassphrase());
        assertEquals("./allus-cache", cfg.cacheDir());
        assertEquals("json", cfg.format());
        assertEquals("secret-one", cfg.webhookSecret("wh_1"));
        assertEquals("secret-two", cfg.webhookSecret("wh_2"));
    }

    @Test
    void optionalFieldsDefault(@TempDir Path tmp) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("api_url", "https://api.allme.fyi");
        data.put("client_id", "svc_abc");
        data.put("client_secret", "s");
        data.put("service_private_key", "./k.pem");
        data.put("key_passphrase", "p");
        Config cfg = Config.fromFile(write(tmp, data).toString());
        assertNull(cfg.accountPrivateKey());
        assertNull(cfg.accountPassphrase());
        assertTrue(cfg.webhooks().isEmpty());
        assertEquals("./allus-cache", cfg.cacheDir());
        assertEquals("json", cfg.format());
    }

    @Test
    void missingRequiredFieldRaises(@TempDir Path tmp) throws Exception {
        Map<String, Object> data = full();
        data.remove("client_secret");
        ConfigException exc = assertThrows(ConfigException.class, () ->
            Config.fromFile(write(tmp, data).toString()));
        assertTrue(exc.getMessage().contains("client_secret"));
    }

    @Test
    void missingFileRaises(@TempDir Path tmp) {
        assertThrows(ConfigException.class, () ->
            Config.fromFile(tmp.resolve("does-not-exist.json").toString()));
    }

    @Test
    void invalidJsonRaises(@TempDir Path tmp) throws Exception {
        Path p = tmp.resolve("bad.json");
        Files.writeString(p, "{ not valid json", StandardCharsets.UTF_8);
        assertThrows(ConfigException.class, () -> Config.fromFile(p.toString()));
    }

    @Test
    void invalidFormatRaises(@TempDir Path tmp) throws Exception {
        Map<String, Object> data = full();
        data.put("format", "yaml");
        assertThrows(ConfigException.class, () -> Config.fromFile(write(tmp, data).toString()));
    }

    @Test
    void flatWebhookSecretShortcut(@TempDir Path tmp) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("api_url", "https://api.allme.fyi");
        data.put("client_id", "svc_abc");
        data.put("client_secret", "s");
        data.put("service_private_key", "./k.pem");
        data.put("key_passphrase", "p");
        data.put("webhook_secret", "the-only-secret");
        Config cfg = Config.fromFile(write(tmp, data).toString());
        assertEquals("the-only-secret", cfg.webhookSecret());
        assertEquals("the-only-secret", cfg.webhookSecret("anything"));
    }

    @Test
    void envOverridesFileValues() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("ALLUS_CLIENT_SECRET", "env-secret");
        env.put("ALLUS_KEY_PASSPHRASE", "env-passphrase");
        env.put("ALLUS_API_URL", "https://api-eu.allme.fyi");
        Config cfg = Config.build(full(), env::get);
        assertEquals("env-secret", cfg.clientSecret());       // overridden
        assertEquals("env-passphrase", cfg.keyPassphrase());  // overridden
        assertEquals("https://api-eu.allme.fyi", cfg.apiUrl()); // overridden
        assertEquals("svc_abc", cfg.clientId());              // from file (no env)
    }

    @Test
    void fromEnvBuildsWithoutAFile() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("ALLUS_API_URL", "https://api.allme.fyi");
        env.put("ALLUS_CLIENT_ID", "svc_env");
        env.put("ALLUS_CLIENT_SECRET", "env-secret");
        env.put("ALLUS_SERVICE_PRIVATE_KEY", "./k.pem");
        env.put("ALLUS_KEY_PASSPHRASE", "env-pass");
        Config cfg = Config.build(Map.of(), env::get);
        assertEquals("svc_env", cfg.clientId());
        assertEquals("env-secret", cfg.clientSecret());
    }

    @Test
    void builderBuildsAValidConfig() {
        Config cfg = Config.builder()
            .apiUrl("https://api.allme.fyi")
            .clientId("svc")
            .clientSecret("s")
            .servicePrivateKey("./k.pem")
            .keyPassphrase("p")
            .build();
        assertEquals("svc", cfg.clientId());
        assertEquals("json", cfg.format());
    }
}
