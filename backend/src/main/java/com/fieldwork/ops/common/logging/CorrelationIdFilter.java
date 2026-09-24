package com.fieldwork.ops.common.logging;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns every inbound request a correlation ID and puts it on the MDC so
 * all log lines for the request — including ones emitted by async listeners
 * and scheduled workers that inherit the context — can be tied together.
 *
 * Clients may supply their own ID via the {@code X-Correlation-Id} header;
 * otherwise one is generated. The ID is echoed back on the response so
 * callers can reference it in support tickets.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter implements Filter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String correlationId = null;
        if (request instanceof HttpServletRequest httpRequest) {
            correlationId = httpRequest.getHeader(CORRELATION_ID_HEADER);
        }
        if (!StringUtils.hasText(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        try {
            if (response instanceof HttpServletResponse httpResponse) {
                httpResponse.setHeader(CORRELATION_ID_HEADER, correlationId);
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CORRELATION_ID_MDC_KEY);
        }
    }
}
