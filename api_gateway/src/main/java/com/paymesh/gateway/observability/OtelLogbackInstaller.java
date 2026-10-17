package com.paymesh.gateway.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sends log events to the OpenTelemetry SDK, which exports them over OTLP (to Loki), with the trace and span ids
 * of the request that logged them. Attached in code, so no service needs its own logback configuration.
 * A copy of paymesh-common's: the gateway can't depend on that servlet-based library.
 */
@Configuration
@ConditionalOnClass(name = "io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender")
public class OtelLogbackInstaller {

    @Bean
    InitializingBean otelLogbackAppender(OpenTelemetry openTelemetry) {
        return () -> {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            OpenTelemetryAppender appender = new OpenTelemetryAppender();
            appender.setContext(context);
            appender.setName("OTEL");
            appender.setOpenTelemetry(openTelemetry);
            appender.start();
            Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            if (root.getAppender("OTEL") == null) {
                root.addAppender(appender);
            }
        };
    }
}
