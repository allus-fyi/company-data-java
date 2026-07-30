package fyi.allme.allus.examples;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Cross-request state for the whole example suite (contract §"Backend state", config-file model). ONE
 * {@code .runtime/} tree serves all three scenario families — every scenario keys its files by its public
 * scenario id (identity ints {@code "1"}…{@code "8"}, {@code "flow:run"}, the {@code companydata:*} ids),
 * sanitised to a filesystem-safe token by {@link #sid(String)}, so the families never collide.
 *
 * <p>Single-worker server (a single-thread {@code HttpServer} executor) → requests serialize; there is
 * NO concurrency to guard, so there are NO locks, NO tombstones and NO burn-on-read. Everything lives
 * under {@code .runtime/} (git-ignored, wiped at startup):
 * <ul>
 *   <li>{@code config/{sid}.json}      — the canonical SDK config file a scenario runs OFF (written by
 *       {@code POST /api/scenarios/{id}/config} from the browser settings; NOT TTL-swept)</li>
 *   <li>{@code config/{sid}.meta.json} — demo-only run parameters that are not SDK Config fields
 *       (authorize_base / claims / share_code / context / flow_id / connection_id / fixture / webhook_id)</li>
 *   <li>{@code config/keys/<sha1>.pem} — the private-key file(s) a config references by path (0600)</li>
 *   <li>{@code runs/{runId}.json}      — one run's PKCE/state/nonce or accumulating result + calls</li>
 *   <li>{@code webhook-route.json}     — the SINGLE active company-data webhook run {webhookId, runId}</li>
 *   <li>{@code state.json}             — the setup snapshot POSTed to {@code /api/state}, held verbatim
 *       as OPAQUE cold storage: never parsed here, never used to run anything</li>
 *   <li>{@code cache/}                 — the SDK pump's buffer + dead-letters ({@code Config.cacheDir})</li>
 * </ul>
 * Config files persist across runs (removed only by Clear or the startup wipe); run files are written
 * write-temp + atomic rename and removed by their 30-minute TTL (lazy sweep on any request), by Clear,
 * or by the startup wipe.
 */
public final class Runtime {
    /** 30-minute run TTL (millis). Config files are exempt (they are configuration, not runs). */
    static final long TTL_MS = 1800L * 1000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Config fields whose value is a key PEM path (union across the families, for GC). */
    private static final List<String> KEY_FIELDS = List.of("oauth_private_key", "service_private_key");

    public final Path runtimeDir;
    public final Path runsDir;
    public final Path configDir;
    public final Path configKeysDir;
    /** The SDK pump's buffer + dead-letter dir ({@code Config.cacheDir}) — under .runtime so Clear wipes it. */
    public final Path cacheDir;
    /** The SINGLE active company-data webhook route record {webhookId, runId}. */
    public final Path routePath;
    /** The setup snapshot POSTed to {@code /api/state} — opaque bytes, never parsed here. */
    public final Path statePath;

    public Runtime(Path baseDir) {
        this.runtimeDir = baseDir.resolve(".runtime");
        this.runsDir = runtimeDir.resolve("runs");
        this.configDir = runtimeDir.resolve("config");
        this.configKeysDir = configDir.resolve("keys");
        this.cacheDir = runtimeDir.resolve("cache");
        this.routePath = runtimeDir.resolve("webhook-route.json");
        this.statePath = runtimeDir.resolve("state.json");
    }

    public void ensureDirs() {
        for (Path d : List.of(runtimeDir, runsDir, configDir, configKeysDir, cacheDir)) {
            try {
                Files.createDirectories(d);
            } catch (IOException exc) {
                throw new UncheckedIOException(exc);
            }
        }
    }

    /** Startup wipe: remove ALL runtime state (configs + keys + runs + cache + route), then recreate. */
    public void wipeAll() {
        rmTree(runtimeDir);
        ensureDirs();
    }

    // ── lazy TTL sweep ──────────────────────────────────────────────────────

    /**
     * Remove expired run files + orphaned *.tmp files. Called on every request. Configs carry NO TTL.
     * When the active webhook run is swept, its routing record is dropped too (a stale record never
     * routes to a burned run).
     */
    public void sweep() {
        long now = System.currentTimeMillis();
        try (Stream<Path> files = Files.list(runsDir)) {
            files.forEach(p -> {
                String name = p.getFileName().toString();
                if (name.endsWith(".tmp")) {
                    quietDelete(p);
                } else if (name.endsWith(".json") && now - lastModified(p) > TTL_MS) {
                    quietDelete(p);
                }
            });
        } catch (IOException ignored) {
            // runs dir may not exist yet — ensureDirs() runs first, so this is benign.
        }
        Map<String, String> route = readRoute();
        if (route != null && !Files.isRegularFile(runsDir.resolve(route.get("runId") + ".json"))) {
            quietDelete(routePath);
        }
    }

    // ── config files ─────────────────────────────────────────────────────────

    /** Filesystem-safe token for a scenario's id (e.g. "companydata:read" → "companydata_read"). */
    public static String sid(String scenarioId) {
        String tok = scenarioId == null ? "" : scenarioId.replaceAll("[^a-zA-Z0-9]+", "_");
        return tok.replaceAll("^_+", "").replaceAll("_+$", "");
    }

    public Path configPathFor(String scenarioId) {
        return configDir.resolve(sid(scenarioId) + ".json");
    }

    public Path metaPathFor(String scenarioId) {
        return configDir.resolve(sid(scenarioId) + ".meta.json");
    }

    public boolean hasConfig(String scenarioId) {
        return Files.isRegularFile(configPathFor(scenarioId));
    }

    /**
     * Write a scenario's canonical SDK config file (config endpoint). Atomic write-temp + rename.
     * Returns the RELATIVE path (for display/inspection in the setup panel).
     */
    public String writeConfig(String scenarioId, Map<String, Object> config) {
        ensureDirs();
        atomicWrite(configPathFor(scenarioId), Json.writeBytes(config), null);
        return ".runtime/config/" + sid(scenarioId) + ".json";
    }

    public void writeConfigMeta(String scenarioId, Map<String, Object> meta) {
        ensureDirs();
        atomicWrite(metaPathFor(scenarioId), Json.writeBytes(meta), null);
    }

    public Map<String, Object> readConfigMeta(String scenarioId) {
        byte[] raw = quietRead(metaPathFor(scenarioId));
        return raw == null ? new LinkedHashMap<>() : Json.parse(raw);
    }

    /**
     * Materialize a browser-sent PEM to {@code config/keys/<sha1>.pem} (0600) and return its ABSOLUTE
     * path — the value recorded in the config file (the SDK reads keys by path). Content-addressed:
     * identical PEM reuses the same file. Removed only by Clear or the startup wipe (never TTL).
     */
    public String materializeConfigKey(String pem) {
        ensureDirs();
        Path path = configKeysDir.resolve(sha1(pem) + ".pem");
        if (!Files.isRegularFile(path)) {
            atomicWrite(path, pem.getBytes(StandardCharsets.UTF_8), owner0600());
        }
        chmod0600(path);
        return path.toString();
    }

    // ── runs ────────────────────────────────────────────────────────────────

    public String newRunId() {
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    public void writeRun(String runId, Map<String, Object> data) {
        data.put("runId", runId);
        atomicWrite(runsDir.resolve(runId + ".json"), Json.writeBytes(data), null);
    }

    /**
     * Read a run, honouring the TTL. Returns null for unknown/expired ids (idempotent reads — an
     * outcome, once written, is returned on every poll until TTL/Clear removes it).
     */
    public Map<String, Object> readRun(String runId) {
        if (!isRunId(runId)) {
            return null;
        }
        Path path = runsDir.resolve(runId + ".json");
        if (!Files.isRegularFile(path)) {
            return null;
        }
        if (System.currentTimeMillis() - lastModified(path) > TTL_MS) {
            quietDelete(path);
            return null;
        }
        byte[] raw = quietRead(path);
        return raw == null ? null : Json.parse(raw);
    }

    // ── webhook routing record (company-data — single active webhook run) ───────

    /**
     * Persist the single active webhook route {webhookId, runId}, superseding any prior one. A new
     * companydata:webhook run calls this on /start; the old run stops receiving (its file stays readable
     * until TTL/Clear).
     */
    public void writeRoute(String webhookId, String runId) {
        ensureDirs();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("webhookId", webhookId);
        record.put("runId", runId);
        atomicWrite(routePath, Json.writeBytes(record), null);
    }

    /** The active webhook route {webhookId, runId}, or null when none is set. */
    public Map<String, String> readRoute() {
        byte[] raw = quietRead(routePath);
        if (raw == null) {
            return null;
        }
        Map<String, Object> decoded = Json.parse(raw);
        Object w = decoded.get("webhookId");
        Object r = decoded.get("runId");
        if (w == null || r == null) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put("webhookId", String.valueOf(w));
        out.put("runId", String.valueOf(r));
        return out;
    }

    public void clearRoute() {
        quietDelete(routePath);
    }

    // ── the setup snapshot (POST/GET /api/state) ──────────────────────────────

    /**
     * Store the setup snapshot the request carried, VERBATIM. The bytes are OPAQUE here — never parsed,
     * never inspected, never used to run anything — so nothing in this class constrains what they may
     * contain, and an empty body is a snapshot like any other. They stay {@code byte[]} end to end:
     * decoding to a {@code String} and back would re-encode content this store is not allowed to
     * interpret. Carries no TTL (it is setup, not a run); removed by a global clear or the startup wipe.
     */
    public void writeState(byte[] blob) {
        ensureDirs();
        atomicWrite(statePath, blob, null);
    }

    /**
     * The stored snapshot's bytes, or null when NO snapshot file exists — the file's presence is the
     * whole of the answer, since judging the content would be the inspection this store does not do. A
     * file that exists but cannot be read raises, because that is a fault rather than an absence.
     */
    public byte[] readState() {
        if (!Files.isRegularFile(statePath)) {
            return null;
        }
        try {
            return Files.readAllBytes(statePath);
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        }
    }

    public void clearState() {
        quietDelete(statePath);
    }

    // ── clear ─────────────────────────────────────────────────────────────────

    /**
     * Per-scenario clear: delete that scenario's run files AND its config + meta files, then GC any key
     * PEM no surviving config references (content-addressed keys may be shared). Clearing the webhook
     * scenario also drops the routing record; clearing anything wipes the shared pump cache dir.
     */
    public void clearScenario(String scenarioId) {
        try (Stream<Path> files = Files.list(runsDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                Map<String, Object> decoded = Json.parse(quietRead(p));
                if (scenarioId.equals(String.valueOf(decoded.get("scenario")))) {
                    quietDelete(p);
                }
            });
        } catch (IOException ignored) {
            // no runs dir yet — nothing to clear.
        }
        quietDelete(configPathFor(scenarioId));
        quietDelete(metaPathFor(scenarioId));
        if ("companydata:webhook".equals(scenarioId)) {
            clearRoute();
        }
        rmTree(cacheDir);
        gcConfigKeys();
        ensureDirs();
    }

    /**
     * Global clear: wipe all run files and the entire config tree (configs, metas, keys) + route +
     * cache + the saved setup snapshot. The snapshot goes too because it can hold the same credentials
     * the config tree does — a clear that left it behind would leave those sitting on disk.
     */
    public void clearAll() {
        try (Stream<Path> files = Files.list(runsDir)) {
            files.forEach(this::quietDelete);
        } catch (IOException ignored) {
            // no runs dir yet.
        }
        rmTree(configDir);
        rmTree(cacheDir);
        clearRoute();
        clearState();
        ensureDirs();
    }

    /** Delete any key PEM no surviving {@code config/{sid}.json} references by path. */
    private void gcConfigKeys() {
        Set<String> referenced = new java.util.HashSet<>();
        try (Stream<Path> files = Files.list(configDir)) {
            files.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".json") && !n.endsWith(".meta.json");
            }).forEach(p -> {
                Map<String, Object> decoded = Json.parse(quietRead(p));
                for (String field : KEY_FIELDS) {
                    Object v = decoded.get(field);
                    if (v instanceof String s && !s.isEmpty()) {
                        referenced.add(s);
                    }
                }
            });
        } catch (IOException ignored) {
            return;
        }
        if (!Files.isDirectory(configKeysDir)) {
            return;
        }
        try (Stream<Path> keys = Files.list(configKeysDir)) {
            keys.filter(p -> p.getFileName().toString().endsWith(".pem"))
                .filter(p -> !referenced.contains(p.toString()))
                .forEach(this::quietDelete);
        } catch (IOException ignored) {
            // nothing to GC.
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    public static boolean isRunId(String s) {
        return s != null && s.matches("[0-9a-f]{32}");
    }

    /** Write-temp + atomic rename on the same filesystem (crash hygiene: no partial reads). */
    private void atomicWrite(Path finalPath, byte[] contents, Set<PosixFilePermission> mode) {
        byte[] salt = new byte[4];
        RANDOM.nextBytes(salt);
        Path tmp = finalPath.resolveSibling(finalPath.getFileName() + "." + HexFormat.of().formatHex(salt) + ".tmp");
        try {
            Files.write(tmp, contents);
            if (mode != null) {
                trySetPerms(tmp, mode);
            }
            try {
                Files.move(tmp, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, finalPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exc) {
            throw new UncheckedIOException(exc);
        } finally {
            quietDelete(tmp);
        }
    }

    private void rmTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(this::quietDelete);
        } catch (IOException ignored) {
            // best-effort teardown.
        }
    }

    private static Set<PosixFilePermission> owner0600() {
        return EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    private static void chmod0600(Path path) {
        trySetPerms(path, owner0600());
    }

    private static void trySetPerms(Path path, Set<PosixFilePermission> perms) {
        try {
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // non-POSIX filesystem — the private key still rests on the developer's own machine.
        }
    }

    private static String sha1(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exc) {
            throw new RuntimeException(exc);
        }
    }

    private long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException exc) {
            return 0;
        }
    }

    private byte[] quietRead(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (IOException exc) {
            return null;
        }
    }

    private void quietDelete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best-effort.
        }
    }
}
