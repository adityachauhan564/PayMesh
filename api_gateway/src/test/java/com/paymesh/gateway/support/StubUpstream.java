package com.paymesh.gateway.support;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A fake downstream service (JDK HttpServer, no extra test dependency) that records what the gateway forwarded. */
public final class StubUpstream implements AutoCloseable {

    public record RecordedRequest(String method, String pathAndQuery, Headers headers, String body) {
        public String header(String name) {
            return headers.getFirst(name);
        }
    }

    private final String name;
    private final Duration responseDelay;
    private final HttpServer server;
    private final BlockingQueue<RecordedRequest> received = new LinkedBlockingQueue<>();

    private StubUpstream(String name, Duration responseDelay) throws IOException {
        this.name = name;
        this.responseDelay = responseDelay;
        this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    public static StubUpstream start(String name) {
        return start(name, Duration.ZERO);
    }

    /** A slow service, for timeout tests. */
    public static StubUpstream start(String name, Duration responseDelay) {
        try {
            return new StubUpstream(name, responseDelay);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** Waits briefly, because the gateway forwards asynchronously. Returns null if nothing arrived. */
    public RecordedRequest takeRequest() throws InterruptedException {
        return received.poll(5, TimeUnit.SECONDS);
    }

    public boolean receivedNothing() {
        return received.isEmpty();
    }

    public void reset() {
        received.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                exchange.getRequestHeaders(), body));
        sleep(responseDelay);
        byte[] response = ("{\"upstream\":\"" + name + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        // Like the real services (CorrelationIdLoggingFilter), echo the correlation id back
        String correlationId = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
        if (correlationId != null) {
            exchange.getResponseHeaders().add("X-Correlation-Id", correlationId);
        }
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
