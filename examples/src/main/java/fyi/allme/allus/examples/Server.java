package fyi.allme.allus.examples;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import fyi.allme.allus.examples.companydata.CompanyDataHandlers;
import fyi.allme.allus.examples.flow.FlowHandlers;
import fyi.allme.allus.examples.identity.IdentityHandlers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single demo-backend router (contract, config-file model). One class, one worker: HTTP dispatch →
 * the family handler that owns the scenario. All three scenario families are served on ONE port from ONE
 * frontend bundle:
 * <ul>
 *   <li><b>identity</b> — the eight sign-in / OIDC / 2FA scenarios (int ids {@code 1}…{@code 8}, id 7 a
 *       guide card) + the public {@code GET /callback} redirect leg + {@code /enroll}
 *       ({@link IdentityHandlers})</li>
 *   <li><b>flow</b> — the one poll-driven contract-flow scenario {@code flow:run}
 *       ({@link FlowHandlers})</li>
 *   <li><b>company-data</b> — the five {@code companydata:*} service-role scenarios + the public
 *       {@code POST /webhook} receiver ({@link CompanyDataHandlers})</li>
 * </ul>
 * A scenario request is dispatched to its family purely by the shape of its id (ints → identity,
 * {@code flow:*} → flow, {@code companydata:*} → company-data). Each family keeps its own run-poll shape;
 * {@code GET /api/runs/{id}} routes by the {@code family} field stored in the run.
 *
 * <p>Settings/run flow (unchanged from the per-family examples): the browser POSTs a scenario's setup to
 * {@code POST /api/scenarios/{id}/config}, which writes a canonical SDK config FILE under
 * {@code .runtime/config/}; {@code /start} builds the SDK from that file and runs OFF it; a {@code /start}
 * with no saved config → 409 not_configured.
 */
public final class Server {
    /** The single contract version the whole suite implements (company-data is the highest at v3). */
    public static final int CONTRACT_VERSION = 3;
    static final String SDK = "java";

    private static final Pattern P_CONFIG = Pattern.compile("^/api/scenarios/([\\w:.\\-]+)/config$");
    private static final Pattern P_START = Pattern.compile("^/api/scenarios/([\\w:.\\-]+)/start$");
    private static final Pattern P_ENROLL = Pattern.compile("^/api/scenarios/([\\w:.\\-]+)/enroll$");
    private static final Pattern P_CLEAR = Pattern.compile("^/api/scenarios/([\\w:.\\-]+)/clear$");
    private static final Pattern P_RUN = Pattern.compile("^/api/runs/([0-9a-f]{32})$");

    private final Runtime rt;
    private final Path frontendDir;
    private final String sdkVersion;

    private final IdentityHandlers identity;
    private final FlowHandlers flow;
    private final CompanyDataHandlers companyData;

    public Server(Runtime rt, Path frontendDir, String sdkVersion) {
        this.rt = rt;
        this.frontendDir = frontendDir;
        this.sdkVersion = sdkVersion;
        this.identity = new IdentityHandlers(rt);
        this.flow = new FlowHandlers(rt);
        this.companyData = new CompanyDataHandlers(rt);
    }

    public void attach(HttpServer http) {
        http.createContext("/", this::dispatch);
    }

    // ── dispatch ───────────────────────────────────────────────────────────────

    private void dispatch(HttpExchange ex) throws IOException {
        try {
            rt.ensureDirs();
            rt.sweep(); // lazy TTL sweep on every request

            String method = ex.getRequestMethod();
            String path = URLDecoder.decode(ex.getRequestURI().getPath(), StandardCharsets.UTF_8);
            Matcher m;

            if (path.equals("/api/meta") && method.equals("GET")) {
                meta(ex);
            } else if (path.equals("/callback") && method.equals("GET")) {
                identity.callback(ex); // identity redirect leg (PUBLIC, not under /api/)
            } else if (path.equals("/webhook") && method.equals("POST")) {
                companyData.webhook(ex); // company-data inbound delivery (PUBLIC, not under /api/)
            } else if (path.equals("/api/clear") && method.equals("POST")) {
                rt.clearAll();
                Http.json(ex, 200, Map.of("ok", true));
            } else if ((m = P_CONFIG.matcher(path)).matches() && method.equals("POST")) {
                config(ex, m.group(1));
            } else if ((m = P_START.matcher(path)).matches() && method.equals("POST")) {
                start(ex, m.group(1));
            } else if ((m = P_ENROLL.matcher(path)).matches() && method.equals("POST")) {
                enroll(ex, m.group(1));
            } else if ((m = P_CLEAR.matcher(path)).matches() && method.equals("POST")) {
                clear(ex, m.group(1));
            } else if ((m = P_RUN.matcher(path)).matches() && method.equals("GET")) {
                run(ex, m.group(1));
            } else if (path.startsWith("/api/")) {
                Http.json(ex, 404, Map.of("error", "not_found"));
            } else {
                serveStatic(ex, path);
            }
        } catch (Throwable t) {
            // The reason rides in `error`, the only key the suite renders (#583).
            Http.failure(ex, 500, "server_error", Http.reasonOf(t));
        } finally {
            ex.close();
        }
    }

    // ── family routing ──────────────────────────────────────────────────────────

    /** Resolve the family that owns a scenario id purely by its shape, or null for an unknown id. */
    private String family(String id) {
        if (id.matches("\\d+")) {
            int n = Integer.parseInt(id);
            return (n >= 1 && n <= 8) ? "identity" : null;
        }
        if ("flow:run".equals(id)) {
            return "flow";
        }
        if (id.startsWith("companydata:") && companyData.knows(id)) {
            return "companydata";
        }
        return null;
    }

    private void config(HttpExchange ex, String id) throws IOException {
        switch (String.valueOf(family(id))) {
            case "identity" -> identity.config(ex, Integer.parseInt(id));
            case "flow" -> flow.config(ex, id);
            case "companydata" -> companyData.config(ex, id);
            default -> Http.json(ex, 404, Map.of("error", "not_found"));
        }
    }

    private void start(HttpExchange ex, String id) throws IOException {
        switch (String.valueOf(family(id))) {
            case "identity" -> identity.start(ex, Integer.parseInt(id));
            case "flow" -> flow.start(ex, id);
            case "companydata" -> companyData.start(ex, id);
            default -> Http.json(ex, 404, Map.of("error", "not_found"));
        }
    }

    private void enroll(HttpExchange ex, String id) throws IOException {
        if ("identity".equals(family(id))) {
            identity.enroll(ex, Integer.parseInt(id));
        } else {
            Http.json(ex, 404, Map.of("error", "not_found"));
        }
    }

    private void clear(HttpExchange ex, String id) throws IOException {
        if (family(id) == null) {
            Http.json(ex, 404, Map.of("error", "not_found"));
            return;
        }
        rt.clearScenario(id);
        Http.json(ex, 200, Map.of("ok", true));
    }

    /** GET /api/runs/{runId} — route to the owning family by the run's stored {@code family} marker. */
    private void run(HttpExchange ex, String runId) throws IOException {
        Map<String, Object> runData = rt.readRun(runId);
        if (runData == null) {
            Http.json(ex, 404, Map.of("error", "not_found"));
            return;
        }
        Map<String, Object> out = switch (Util.strOr(runData.get("family"), "")) {
            case "identity" -> identity.pollBody(runId, runData);
            case "flow" -> flow.pollBody(runId, runData);
            case "companydata" -> companyData.pollBody(runId, runData);
            default -> Map.of("error", "not_found");
        };
        Http.json(ex, 200, out);
    }

    // ── GET /api/meta — ALL scenarios of ALL three families ─────────────────────

    private void meta(HttpExchange ex) throws IOException {
        List<Map<String, Object>> scenarios = new ArrayList<>();
        scenarios.addAll(identity.scenarios());
        scenarios.addAll(flow.scenarios());
        scenarios.addAll(companyData.scenarios());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sdk", SDK);
        out.put("sdkVersion", sdkVersion);
        out.put("contractVersion", CONTRACT_VERSION);
        out.put("scenarios", scenarios);
        Http.json(ex, 200, out);
    }

    // ── static bundle (SPA) ──────────────────────────────────────────────────────

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        String rel = path.equals("/") ? "/index.html" : path;
        Path root = frontendDir.toAbsolutePath().normalize();
        Path full = root.resolve(rel.replaceFirst("^/+", "")).normalize();

        // Path-traversal guard + SPA fallback to index.html.
        if (!full.startsWith(root) || !Files.isRegularFile(full)) {
            Path index = root.resolve("index.html");
            if (Files.isRegularFile(index)) {
                byte[] b = Files.readAllBytes(index);
                ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(b);
                }
                return;
            }
            byte[] b = "bundle not found".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain");
            ex.sendResponseHeaders(404, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
            return;
        }

        byte[] b = Files.readAllBytes(full);
        ex.getResponseHeaders().set("Content-Type", mime(full));
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static String mime(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        return switch (ext) {
            case "html" -> "text/html; charset=utf-8";
            case "js", "mjs" -> "text/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "json", "map" -> "application/json; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "ico" -> "image/x-icon";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            case "ttf" -> "font/ttf";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }
}
