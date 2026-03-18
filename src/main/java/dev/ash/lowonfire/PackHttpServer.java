package dev.ash.lowonfire;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class PackHttpServer {
    private final HttpServer server;
    private final ExecutorService executor;
    private final String path;

    public PackHttpServer(final String bindAddress, final String path, final byte[] body) throws IOException {
        this.path = normalizePath(path);
        this.server = HttpServer.create(new InetSocketAddress(bindAddress, 0), 0);
        this.executor = Executors.newSingleThreadExecutor(new PackThreadFactory());
        this.server.setExecutor(this.executor);
        this.server.createContext(this.path, exchange -> handle(exchange, body));
    }

    public void start() {
        this.server.start();
    }

    public void stop() {
        this.server.stop(0);
        this.executor.shutdownNow();
    }

    public String path() {
        return this.path;
    }

    public int port() {
        return this.server.getAddress().getPort();
    }

    private void handle(final HttpExchange exchange, final byte[] body) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "application/zip");
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=300");
        exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));

        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }

        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }

    private static String normalizePath(final String path) {
        if (path == null || path.isBlank()) {
            return "/low-on-fire.zip";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private static final class PackThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(final Runnable runnable) {
            final Thread thread = new Thread(runnable, "LowOnFire-PackServer");
            thread.setDaemon(true);
            return thread;
        }
    }
}
