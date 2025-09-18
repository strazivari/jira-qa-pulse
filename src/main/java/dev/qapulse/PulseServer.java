package dev.qapulse;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class PulseServer {
    private record Resource(String contentType, byte[] bytes) {}

    private PulseServer() {}

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path root = args.length > 1 ? Path.of(args[1]) : Path.of(".");
        var server = start(root, port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        System.out.println("Jira QA Pulse: http://127.0.0.1:" + server.getAddress().getPort());
    }

    static HttpServer start(Path root, int port) throws IOException {
        var data = PulseData.load(root.resolve("data"));
        var web = root.resolve("web");
        var resources = Map.of(
                "/", resource(web.resolve("index.html"), "text/html; charset=utf-8"),
                "/styles.css", resource(web.resolve("styles.css"), "text/css; charset=utf-8"),
                "/app.js", resource(web.resolve("app.js"), "text/javascript; charset=utf-8"));
        var queue = jsonResource(ApiPayload.queue(data));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> handle(exchange, resources, queue, data));
        server.start();
        return server;
    }

    private static void handle(HttpExchange exchange, Map<String, Resource> resources, Resource queue, PulseData data)
            throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                respond(exchange, 405, jsonResource("{\"error\":\"Method not allowed\"}"));
                return;
            }

            String path = exchange.getRequestURI().getPath();
            if (resources.containsKey(path)) {
                respond(exchange, 200, resources.get(path));
                return;
            }
            if ("/api/queue".equals(path)) {
                respond(exchange, 200, queue);
                return;
            }
            if ("/api/failures".equals(path)) {
                String query = exchange.getRequestURI().getRawQuery();
                int days = switch (query == null ? "" : query) {
                    case "", "days=7" -> 7;
                    case "days=30" -> 30;
                    case "days=all" -> 0;
                    default -> -1;
                };
                if (days < 0) {
                    respond(exchange, 400, jsonResource("{\"error\":\"days must be 7, 30 or all\"}"));
                } else {
                    respond(exchange, 200, jsonResource(ApiPayload.failures(data, days)));
                }
                return;
            }
            respond(exchange, 404, jsonResource("{\"error\":\"Not found\"}"));
        }
    }

    private static Resource resource(Path path, String contentType) throws IOException {
        return new Resource(contentType, Files.readAllBytes(path));
    }

    private static Resource jsonResource(String text) {
        return new Resource("application/json; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, Resource resource) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", resource.contentType());
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, resource.bytes().length);
        exchange.getResponseBody().write(resource.bytes());
    }
}
