package com.urlshortener.ratelimiter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final SlidingWindowRateLimiter rateLimiter;
    private final int createLimit;
    private final int redirectLimit;

    public RateLimitInterceptor(SlidingWindowRateLimiter rateLimiter,
                                @org.springframework.beans.factory.annotation.Value("${app.rate-limit.max-requests-per-minute:100}") int createLimit,
                                @org.springframework.beans.factory.annotation.Value("${app.rate-limit.redirect-max-requests-per-minute:1000}") int redirectLimit) {
        this.rateLimiter = rateLimiter;
        this.createLimit = createLimit;
        this.redirectLimit = redirectLimit;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String clientIp = getClientIp(request);
        String method = request.getMethod();
        String path = request.getRequestURI();

        int limit;
        String rateLimitKey;

        if ("POST".equals(method) && path.startsWith("/api/v1/urls")) {
            limit = createLimit;
            rateLimitKey = "create:" + clientIp;
        } else if ("GET".equals(method) && !path.startsWith("/api/") && !path.startsWith("/actuator")) {
            limit = redirectLimit;
            rateLimitKey = "redirect:" + clientIp;
        } else {
            return true;
        }

        if (!rateLimiter.tryAcquire(rateLimitKey, limit, Duration.ofMinutes(1))) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            try {
                response.getWriter().write("{\"error\":\"Rate limit exceeded\",\"message\":\"Too many requests. Try again later.\"}");
            } catch (Exception ignored) {}
            return false;
        }

        return true;
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
