package com.bookkeeping.monthly;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/** 内部任务凭据只由桌面主进程经启动环境传递，拒绝浏览器 Origin 和非回环调用。 */
@Component
@Order(-100)
public class MonthlyReportInternalGuard extends OncePerRequestFilter {
    private final String secret;
    public MonthlyReportInternalGuard(@Value("${BOOKKEEPING_AI_SESSION:}") String secret) { this.secret = secret; }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith(request.getContextPath() + "/api/internal/")) {
            String supplied = request.getHeader("X-Monthly-Session");
            if (secret.length() < 32 || supplied == null || supplied.length() > 256
                    || request.getHeader("Origin") != null
                    || !Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(request.getRemoteAddr())
                    || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                response.sendError(403);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
