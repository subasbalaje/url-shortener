package com.sdlc.shortener.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Token bucket per API key (preferred) or IP, stricter on create than redirect
 * (DEC-0008). {@code 429} + {@code Retry-After} on exhaustion.
 *
 * <p>Create and redirect traffic from the same caller draw from <b>separate</b>
 * buckets — bulk redirect traffic (normal usage of an existing link) must not
 * exhaust the budget for creating new ones, and vice versa.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final double createCapacity;
    private final double createRefillPerSecond;
    private final double redirectCapacity;
    private final double redirectRefillPerSecond;

    private final ConcurrentMap<String, TokenBucket> createBuckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TokenBucket> redirectBuckets = new ConcurrentHashMap<>();

    public RateLimitFilter() {
        this(10, 1.0, 100, 10.0); // 10 creates/burst refilling 1/sec; 100 redirects/burst refilling 10/sec
    }

    RateLimitFilter(double createCapacity, double createRefillPerSecond, double redirectCapacity, double redirectRefillPerSecond) {
        this.createCapacity = createCapacity;
        this.createRefillPerSecond = createRefillPerSecond;
        this.redirectCapacity = redirectCapacity;
        this.redirectRefillPerSecond = redirectRefillPerSecond;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean isCreate = "POST".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().startsWith("/api/v1/links");

        String key = callerKey(request);
        ConcurrentMap<String, TokenBucket> buckets = isCreate ? createBuckets : redirectBuckets;
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> isCreate
                ? new TokenBucket((int) createCapacity, createRefillPerSecond)
                : new TokenBucket((int) redirectCapacity, redirectRefillPerSecond));

        if (bucket.tryConsume()) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setHeader("Retry-After", "1");
        }
    }

    private String callerKey(HttpServletRequest request) {
        String apiKey = request.getHeader("X-API-Key");
        return (apiKey != null && !apiKey.isBlank()) ? "key:" + apiKey : "ip:" + request.getRemoteAddr();
    }
}
