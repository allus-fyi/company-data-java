package fyi.allme.allus.flowexample;

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
 * Cross-request state for the demo backend (contract §"Backend state", config-file model).
 *
 * <p>Single-worker server (a single-thread {@code HttpServer} executor) → requests serialize; there is
 * NO concurrency to guard, so there are NO locks, NO tombstones and NO burn-on-read. Everything lives
 * under {@code .runtime/} (git-ignored, wiped at startup):
 * <ul>
 *   <li>{@code config/{id}.json}      — the canonical SDK config file the scenario runs OFF (written by
 *       {@code POST /api/scenarios/{id}/config} from the browser settings; NOT TTL-swept)</li>
 *   <li>{@code config/{id}.meta.json} — demo-only run parameters that are not SDK Config fields
 *       (flow_id, connection_id, fixture)</li>
 *   <li>{@code config/keys/<sha1>.pem} — the service private-key file the config references by path (0600)</li>
 *   <li>{@code runs/{runId}.json}     — the platform flowRunId + accumulating flow shape for one run</li>
 * </ul>
 * The single flow scenario ("flow:run") stores under the internal store id {@code 1} (its public id is
 * not filesystem-shaped). Config files persist across runs (removed only by Clear or the startup wipe);
 * run files are written write-temp + atomic rename and removed by their 30-minute TTL (lazy sweep on any
 * request), by Clear, or by the startup wipe.
 */
final class Runtime {
    /** 30-minute run TTL (millis). Config files are exempt (they are configuration, not runs). */
    static final long TTL_MS = 1800L * 1000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    final Path runtimeDir;
    final Path runsDir;
    final Path configDir;
    final Path configKeysDir;

    Runtime(Path baseDir) {
        this.runtimeDir = baseDir.resolve(".runtime");
        this.runsDir = runtimeDir.resolve("runs");
        this.configDir = runtimeDir.resolve("config");
        this.configKeysDir = configDir.resolve("keys");
    }

    void ensureDirs() {
        for (Path d : List.of(runtimeDir, runsDir, configDir, configKeysDir)) {
            try {
                Files.createDirectories(d);
            } catch (IOException exc) {
                throw new UncheckedIOException(exc);
            }
        }
    }

    /** Startup wipe: remove ALL runtime state (configs + keys + runs), then recreate the empty tree. */
    void wipeAll() {
        rmTree(runtimeDir);
        ensureDirs();
    }

    // ── lazy TTL sweep ──────────────────────────────────────────────────────

    /** Remove expired run files + orphaned *.tmp files. Called on every request. Configs carry NO TTL. */
    void sweep() {
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
    }

    // ── config files ─────────────────────────────────────────────────────────

    Path configPathFor(int scenarioId) {
        return configDir.resolve(scenarioId + ".json");
    }

    Path metaPathFor(int scenarioId) {
        return configDir.resolve(scenarioId + ".meta.json");
    }

    boolean hasConfig(int scenarioId) {
        return Files.isRegularFile(configPathFor(scenarioId));
    }

    /**
     * Write the scenario's canonical SDK config file (config endpoint). Atomic write-temp + rename.
     * Returns the RELATIVE path (for display/inspection in the setup panel).
     */
    String writeConfig(int scenarioId, Map<String, Object> config) {
        ensureDirs();
        atomicWrite(configPathFor(scenarioId), Json.writeBytes(config), null);
        return ".runtime/config/" + scenarioId + ".json";
    }

    void writeConfigMeta(int scenarioId, Map<String, Object> meta) {
        ensureDirs();
        atomicWrite(metaPathFor(scenarioId), Json.writeBytes(meta), null);
    }

    Map<String, Object> readConfigMeta(int scenarioId) {
        byte[] raw = quietRead(metaPathFor(scenarioId));
        return raw == null ? new LinkedHashMap<>() : Json.parse(raw);
    }

    /**
     * Materialize a browser-sent PEM to {@code config/keys/<sha1>.pem} (0600) and return its ABSOLUTE
     * path — the value recorded in the config file (the SDK reads keys by path). Content-addressed:
     * identical PEM reuses the same file. Removed only by Clear or the startup wipe (never TTL).
     */
    String materializeConfigKey(String pem) {
        ensureDirs();
        Path path = configKeysDir.resolve(sha1(pem) + ".pem");
        if (!Files.isRegularFile(path)) {
            atomicWrite(path, pem.getBytes(StandardCharsets.UTF_8), owner0600());
        }
        chmod0600(path);
        return path.toString();
    }

    // ── runs ────────────────────────────────────────────────────────────────

    String newRunId() {
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    void writeRun(String runId, Map<String, Object> data) {
        data.put("runId", runId);
        atomicWrite(runsDir.resolve(runId + ".json"), Json.writeBytes(data), null);
    }

    /**
     * Read a run, honouring the TTL. Returns null for unknown/expired ids (idempotent reads — an
     * outcome, once written, is returned on every poll until TTL/Clear removes it).
     */
    Map<String, Object> readRun(String runId) {
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

    // ── clear ─────────────────────────────────────────────────────────────────

    /**
     * Per-scenario clear: delete that scenario's run files AND its config + meta files, then GC any key
     * PEM no surviving config references (content-addressed keys may be shared).
     */
    void clearScenario(int scenarioId) {
        try (Stream<Path> files = Files.list(runsDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                Map<String, Object> decoded = Json.parse(quietRead(p));
                if (asInt(decoded.get("scenario")) == scenarioId) {
                    quietDelete(p);
                }
            });
        } catch (IOException ignored) {
            // no runs dir yet — nothing to clear.
        }
        quietDelete(configPathFor(scenarioId));
        quietDelete(metaPathFor(scenarioId));
        gcConfigKeys();
    }

    /** Global clear: wipe all run files and the entire config tree (configs, metas, keys). */
    void clearAll() {
        try (Stream<Path> files = Files.list(runsDir)) {
            files.forEach(this::quietDelete);
        } catch (IOException ignored) {
            // no runs dir yet.
        }
        rmTree(configDir);
        ensureDirs();
    }

    /** Delete any key PEM no surviving {@code config/{id}.json} references by path. */
    private void gcConfigKeys() {
        Set<String> referenced = new java.util.HashSet<>();
        try (Stream<Path> files = Files.list(configDir)) {
            files.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".json") && !n.endsWith(".meta.json");
            }).forEach(p -> {
                Map<String, Object> decoded = Json.parse(quietRead(p));
                Object v = decoded.get("service_private_key");
                if (v instanceof String s && !s.isEmpty()) {
                    referenced.add(s);
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

    static boolean isRunId(String s) {
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

    private static int asInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? 0 : Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException exc) {
            return 0;
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
