package com.paymesh.logging;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class CorrelationIdLoggingFilterTest {

    private static final String HEADER = "X-Correlation-Id";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final CorrelationIdLoggingFilter filter = new CorrelationIdLoggingFilter();
    private final AtomicReference<String> loggedId = new AtomicReference<>();
    private final FilterChain recordingChain = (request, response) -> loggedId.set(MDC.get("correlationId"));

    @Test
    void logsTheIdTheGatewayForwarded() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(requestWith("gw-1234"), response, recordingChain);

        assertThat(loggedId.get()).isEqualTo("gw-1234");
        assertThat(response.getHeader(HEADER)).isEqualTo("gw-1234");
    }

    @Test
    void generatesAnIdForDirectCallsWithoutOne() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), recordingChain);

        assertThat(loggedId.get()).matches(UUID_PATTERN);
    }

    @Test
    void neverLogsAnUnsafeClientValue() throws Exception {
        filter.doFilter(requestWith("forged\nINFO fake log line"), new MockHttpServletResponse(), recordingChain);

        assertThat(loggedId.get()).matches(UUID_PATTERN);
    }

    @Test
    void clearsTheIdAfterTheRequestEvenOnFailure() {
        FilterChain failingChain = (request, response) -> {
            throw new IllegalStateException("boom");
        };

        assertThatIllegalStateException().isThrownBy(
                () -> filter.doFilter(requestWith("gw-1234"), new MockHttpServletResponse(), failingChain));
        assertThat(MDC.get("correlationId")).isNull();
    }

    private static MockHttpServletRequest requestWith(String correlationId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, correlationId);
        return request;
    }
}
