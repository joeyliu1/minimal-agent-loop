package com.agentloop.agent;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that injects traceId and sessionId into MDC for every HTTP request.
 * <p>Also adds X-Trace-Id response header for client-side debugging.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        try {
            HttpServletRequest httpReq = (HttpServletRequest) request;
            String traceId = httpReq.getHeader("X-Trace-Id");
            if (traceId == null || traceId.isBlank()) {
                traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            }
            String sessionId = httpReq.getHeader("X-Session-Id");
            if (sessionId == null) sessionId = "";

            MDC.put("traceId", traceId);
            MDC.put("sessionId", sessionId);

            if (response instanceof jakarta.servlet.http.HttpServletResponse httpResp) {
                httpResp.setHeader("X-Trace-Id", traceId);
            }

            chain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
            MDC.remove("sessionId");
        }
    }

    @Override
    public void init(FilterConfig filterConfig) {}
    @Override
    public void destroy() {}
}
