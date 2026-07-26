package fyi.allme.allus.identityexample;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * One-command launcher for the identity example (contract §"Contract versioning" + §"Backend state").
 *
 * <p>Run it with: {@code mvn -q compile exec:java} (JDK 21 + Maven on your PATH)
 *
 * <p>Steps:
 * <ol>
 *   <li>wipe {@code .runtime/} (fresh state each boot)</li>
 *   <li>on a missing/stale bundle: fetch the pinned frontend release ({@code frontend.lock}), VERIFY
 *       its sha256, unpack to {@code .frontend/<tag>/} (a present, verified bundle is a cache hit)</li>
 *   <li>assert the bundle's {@code contract.json} version == the backend's implemented contractVersion</li>
 *   <li>refuse a busy port with a clear message</li>
 *   <li>serve {@code http://localhost:${PORT:-8091}} on a SINGLE-thread executor (single worker)</li>
 * </ol>
 */
public final class Main {
    private static final String RELEASE_BASE = "https://github.com/allme-sdk/example-test-suite/releases/download";

    public static void main(String[] args) throws Exception {
        Path base = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        System.err.println("identity example — starting up");

        Runtime rt = new Runtime(base);
        rt.wipeAll(); // 1. fresh runtime state

        // 2. frontend bundle (pinned release, checksum-verified, per-tag cache)
        Map<String, Object> lock = Json.parse(readOrEmpty(base.resolve("frontend.lock")));
        String tag = str(lock.get("tag"));
        String wantSha = str(lock.get("sha256")).toLowerCase();
        if (tag.isEmpty() || wantSha.isEmpty()) {
            fail("frontend.lock missing or malformed (need {\"tag\",\"sha256\"}).");
        }
        Path frontend = base.resolve(".frontend").resolve(tag);
        if (cacheValid(frontend, wantSha)) {
            System.err.println("frontend " + tag + " present + checksum-verified (cache hit) — skipping fetch");
        } else {
            fetchBundle(base, frontend, tag, wantSha);
        }

        // 3. contract guard
        Map<String, Object> bundleContract = Json.parse(readOrEmpty(frontend.resolve("contract.json")));
        int bundleVersion = bundleContract.get("contractVersion") instanceof Number n ? n.intValue() : -1;
        if (bundleVersion != Server.CONTRACT_VERSION) {
            fail("contract mismatch: bundle contractVersion=" + bundleVersion + ", backend implements "
                + Server.CONTRACT_VERSION + ".\n"
                + "Bump the frontend.lock pin to a release whose contract.json matches, or update the backend.");
        }

        // 4. port
        int port = envPort();
        if (!portFree(port)) {
            fail("port " + port + " is busy. Set PORT=<n> to use another port "
                + "(one browser origin is shared across SDK examples, so only one runs at a time).");
        }

        // 5. serve — SINGLE WORKER (single-thread executor)
        HttpServer http = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        http.setExecutor(Executors.newSingleThreadExecutor());
        new Server(rt, frontend, sdkVersion(), port).attach(http);
        http.start();
        System.err.println("serving http://localhost:" + port + "  (Ctrl-C to stop)");

        // Keep the JVM alive (HttpServer runs on its own executor thread).
        Thread.currentThread().join();
    }

    // ── frontend fetch (curl + tar, sha256-verified) ─────────────────────────

    private static boolean cacheValid(Path frontend, String wantSha) {
        String markSha = readTrim(frontend.resolve(".sha")).toLowerCase();
        return Files.isRegularFile(frontend.resolve("index.html"))
            && Files.isRegularFile(frontend.resolve("contract.json"))
            && !markSha.isEmpty()
            && constantTimeEquals(wantSha, markSha);
    }

    private static void fetchBundle(Path base, Path frontend, String tag, String wantSha) throws Exception {
        String url = RELEASE_BASE + "/" + tag + "/dist.tar.gz";
        System.err.println("fetching frontend " + tag + " → " + url);
        Path tmp = base.resolve(".frontend.download.tar.gz");
        Files.deleteIfExists(tmp);

        int rc = run(base, "curl", "-fsSL", url, "-o", tmp.toString());
        if (rc != 0 || !Files.isRegularFile(tmp)) {
            fail("could not download the pinned frontend release (" + url + ").\n"
                + "If the release does not exist yet, seed it manually: build the frontend, then\n"
                + "  mkdir -p " + frontend + " && tar -xzf dist.tar.gz -C " + frontend + "\n"
                + "  printf %s " + wantSha + " > " + frontend.resolve(".sha")
                + "   # the recorded checksum makes it a verified cache-hit");
        }

        String gotSha = sha256(tmp).toLowerCase();
        if (!constantTimeEquals(wantSha, gotSha)) {
            Files.deleteIfExists(tmp);
            fail("frontend checksum MISMATCH.\n  expected " + wantSha + "\n  got      " + gotSha + "\n"
                + "Refusing to serve an unverified bundle. Fix frontend.lock or re-download.");
        }

        rmTree(frontend);
        Files.createDirectories(frontend);
        int urc = run(base, "tar", "-xzf", tmp.toString(), "-C", frontend.toString());
        Files.deleteIfExists(tmp);
        if (urc != 0 || !Files.isRegularFile(frontend.resolve("index.html"))) {
            fail("failed to unpack the frontend bundle.");
        }
        Files.writeString(frontend.resolve(".sha"), wantSha, StandardCharsets.UTF_8);
        System.err.println("frontend " + tag + " verified + unpacked → " + frontend);
    }

    // ── the SDK's own version (read from the resolved dependency's pom.properties) ──

    private static String sdkVersion() {
        try (InputStream in = Main.class.getResourceAsStream(
            "/META-INF/maven/fyi.allme.allus/company-data/pom.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("version");
                if (v != null && !v.isEmpty()) {
                    return v;
                }
            }
        } catch (IOException ignored) {
            // fall through to "unknown"
        }
        return "unknown";
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static int envPort() {
        String p = System.getenv("PORT");
        if (p != null && p.matches("\\d+")) {
            return Integer.parseInt(p);
        }
        return 8091;
    }

    private static boolean portFree(int port) {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(false);
            s.bind(new InetSocketAddress("localhost", port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int run(Path cwd, String... cmd) throws IOException, InterruptedException {
        return new ProcessBuilder(cmd).directory(cwd.toFile()).inheritIO().start().waitFor();
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(Files.readAllBytes(file));
        return HexFormat.of().formatHex(md.digest());
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readOrEmpty(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static String readTrim(Path p) {
        return new String(readOrEmpty(p), StandardCharsets.UTF_8).trim();
    }

    private static void rmTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((x, y) -> y.getNameCount() - x.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static void fail(String msg) {
        System.err.println("\nERROR: " + msg);
        System.exit(1);
    }
}
