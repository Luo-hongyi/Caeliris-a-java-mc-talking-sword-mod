package dreamdust.talking_weapon.basic_talking_weapon.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Studio 与游戏之间的本地 loopback HTTP bridge。
 * <p>
 * 设计目标：
 * <ul>
 * <li>只监听 127.0.0.1，避免暴露到局域网</li>
 * <li>保留 manifest 轮询机制作为兜底</li>
 * <li>让桌面版 Studio 可以在导出后显式请求一次 publish</li>
 * </ul>
 */
public final class StudioHttpBridgeServer {

    public static final String HOST = "127.0.0.1";
    public static final int PORT = 47852;
    public static final String API_BASE = "/api/studio";

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private static volatile HttpServer server;

    private StudioHttpBridgeServer() {
    }

    public static synchronized void startIfNeeded() {
        if (server != null) {
            return;
        }

        try {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress(HOST, PORT), 0);
            httpServer.createContext(API_BASE + "/health", new JsonHandler(StudioHttpBridgeServer::handleHealth));
            httpServer.createContext(API_BASE + "/export", new JsonHandler(StudioHttpBridgeServer::handleExport));
            httpServer.createContext(API_BASE + "/publish", new JsonHandler(StudioHttpBridgeServer::handlePublish));
            httpServer.setExecutor(Executors.newCachedThreadPool(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "talking-weapon-studio-http");
                    thread.setDaemon(true);
                    return thread;
                }
            }));
            httpServer.start();
            server = httpServer;
            System.out.println("[TalkingWeapon/Studio] HTTP bridge started at http://" + HOST + ":" + PORT + API_BASE);
        } catch (Exception e) {
            Path logPath = StudioErrorLogger.log("start studio http bridge", e);
            if (logPath != null) {
                System.err.println("[TalkingWeapon/Studio] Failed to start HTTP bridge: "
                        + e + " | detailed log: " + logPath.toAbsolutePath());
            } else {
                System.err.println("[TalkingWeapon/Studio] Failed to start HTTP bridge: " + e);
            }
            e.printStackTrace();
        }
    }

    private static Response handleHealth(HttpExchange exchange) {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            return Response.methodNotAllowed();
        }

        StudioWorkspaceManifestFile manifest = null;
        try {
            manifest = StudioWorkspaceManifestFile.readIfExists(StudioWorkspaceManifestFile.getPath());
        } catch (IOException e) {
            StudioErrorLogger.log("read studio manifest for health", e);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("serverTime", OffsetDateTime.now().toString());
        body.put("workspaceRoot", StudioWorkspaceExporter.getWorkspaceRoot().toAbsolutePath().toString());
        body.put("manifestPath", StudioWorkspaceManifestFile.getPath().toAbsolutePath().toString());
        body.put("latestErrorLogPath", StudioErrorLogger.getLatestLogPath().toAbsolutePath().toString());
        body.put("manifest", manifest);
        return Response.ok(body);
    }

    private static Response handleExport(HttpExchange exchange) {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            return Response.methodNotAllowed();
        }

        try {
            StudioWorkspaceExporter.ExportResult result = StudioWorkspaceExporter.exportWorkspace();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("workspaceRoot", result.workspaceRoot.toAbsolutePath().toString());
            body.put("fileCount", result.fileCount);
            body.put("snapshotEntryCount", result.snapshotEntryCount);
            body.put("runtimeTagCount", result.runtimeTagCount);
            body.put("renderedIcons", result.renderedIcons);
            body.put("placeholderIcons", result.placeholderIcons);
            return Response.ok(body);
        } catch (Exception e) {
            return Response.failure("export studio workspace", e);
        }
    }

    private static Response handlePublish(HttpExchange exchange) {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            return Response.methodNotAllowed();
        }

        try {
            StudioWorkspacePublisher.PublishResult result = StudioWorkspacePublisher.publishWorkspace();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("workspaceRoot", result.workspaceRoot.toAbsolutePath().toString());
            body.put("publishedFiles", result.publishedFiles);
            body.put("customTagCount", result.customTagCount);
            body.put("descriptionCount", result.descriptionCount);
            body.put("relationCount", result.relationCount);
            body.put("personaCount", result.personaCount);
            body.put("memoryCount", result.memoryCount);
            return Response.ok(body);
        } catch (Exception e) {
            return Response.failure("publish studio workspace", e);
        }
    }

    private static void writeJson(HttpExchange exchange, int statusCode, Object payload) throws IOException {
        byte[] body = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private interface ExchangeHandler {
        Response handle(HttpExchange exchange) throws Exception;
    }

    private static final class JsonHandler implements HttpHandler {
        private final ExchangeHandler delegate;

        private JsonHandler(ExchangeHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 200, Map.of());
                return;
            }

            Response response;
            try {
                response = delegate.handle(exchange);
            } catch (Exception e) {
                response = Response.failure("handle studio http request", e);
            }
            writeJson(exchange, response.statusCode, response.body);
        }
    }

    private static final class Response {
        private final int statusCode;
        private final Map<String, Object> body;

        private Response(int statusCode, Map<String, Object> body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        private static Response ok(Map<String, Object> body) {
            return new Response(200, body);
        }

        private static Response methodNotAllowed() {
            return new Response(405, Map.of(
                    "ok", false,
                    "error", "method_not_allowed"));
        }

        private static Response failure(String action, Exception error) {
            Path logPath = StudioErrorLogger.log(action, error);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", false);
            body.put("error", error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName());
            body.put("errorType", error.getClass().getName());
            body.put("latestErrorLogPath", StudioErrorLogger.getLatestLogPath().toAbsolutePath().toString());
            if (logPath != null) {
                body.put("errorLogPath", logPath.toAbsolutePath().toString());
            }
            return new Response(500, body);
        }
    }
}
