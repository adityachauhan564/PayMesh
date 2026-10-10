package com.paymesh.gateway.error;

import com.paymesh.gateway.correlation.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.util.Map;

/**
 * Turns routing failures into ProblemDetail responses. Runs before Boot's default handler (order -1),
 * which would otherwise return its own JSON shape. Internal details are logged, never returned.
 */
@Component
@Order(-2)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private static final Map<HttpStatus, String> CLIENT_MESSAGES = Map.of(
            HttpStatus.NOT_FOUND, "Resource not found",
            HttpStatus.SERVICE_UNAVAILABLE, "The service is temporarily unavailable, please retry",
            HttpStatus.GATEWAY_TIMEOUT, "The service did not respond in time",
            HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected gateway error");

    private final ProblemDetailWriter problemDetailWriter;

    public GatewayErrorHandler(ProblemDetailWriter problemDetailWriter) {
        this.problemDetailWriter = problemDetailWriter;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(error);
        }
        HttpStatus status = statusFor(error);
        logFailure(exchange, status, error);
        return problemDetailWriter.write(exchange, status, CLIENT_MESSAGES.getOrDefault(status, status.getReasonPhrase()));
    }

    static HttpStatus statusFor(Throwable error) {
        if (causedBy(error, ConnectException.class)) {
            return HttpStatus.SERVICE_UNAVAILABLE; // also covers connect timeouts (a ConnectException subclass)
        }
        if (error instanceof ResponseStatusException statusException) {
            HttpStatus status = HttpStatus.resolve(statusException.getStatusCode().value());
            return status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    // Path only: query strings can carry personal data (e.g. ?mobile=).
    private static void logFailure(ServerWebExchange exchange, HttpStatus status, Throwable error) {
        String correlationId = exchange.getResponse().getHeaders().getFirst(CorrelationIdFilter.HEADER);
        String request = exchange.getRequest().getMethod() + " " + exchange.getRequest().getPath().value();
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            log.error("[{}] {} failed", correlationId, request, error);
        } else if (status.is5xxServerError()) {
            log.warn("[{}] {} -> {}: {}", correlationId, request, status.value(), error.getMessage());
        }
    }
}
