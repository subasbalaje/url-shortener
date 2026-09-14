package com.sdlc.shortener.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RateLimitFilterTest {

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter(2, 0.0, 5, 0.0); // create: 2/burst, redirect: 5/burst, no refill
    }

    private HttpServletRequest request(String method, String path, String remoteAddr) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getMethod()).thenReturn(method);
        when(req.getRequestURI()).thenReturn(path);
        when(req.getRemoteAddr()).thenReturn(remoteAddr);
        when(req.getHeader(anyString())).thenReturn(null);
        return req;
    }

    @Test
    void allowsRequestsWithinTheCreateBudget() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);

        filter.doFilter(request("POST", "/api/v1/links", "1.2.3.4"), resp, chain);
        filter.doFilter(request("POST", "/api/v1/links", "1.2.3.4"), resp, chain);

        verify(chain, times(2)).doFilter(any(), any());
        verify(resp, never()).setStatus(429);
    }

    @Test
    void rejectsWithA429AndRetryAfterOnceCreateBudgetIsExhausted() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);

        filter.doFilter(request("POST", "/api/v1/links", "1.2.3.4"), resp, chain);
        filter.doFilter(request("POST", "/api/v1/links", "1.2.3.4"), resp, chain);
        filter.doFilter(request("POST", "/api/v1/links", "1.2.3.4"), resp, chain); // 3rd: over budget

        verify(chain, times(2)).doFilter(any(), any());
        verify(resp).setStatus(429);
        verify(resp).setHeader(eq("Retry-After"), anyString());
    }

    @Test
    void createAndRedirectHaveIndependentBudgetsForTheSameCaller() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);

        filter.doFilter(request("POST", "/api/v1/links", "1.2.3.4"), resp, chain);
        filter.doFilter(request("POST", "/api/v1/links", "1.2.3.4"), resp, chain); // exhausts create budget (2)

        // Redirect traffic from the same IP is unaffected -- stricter on create
        // than redirect means separate budgets, not a shared one.
        filter.doFilter(request("GET", "/abc1234", "1.2.3.4"), resp, chain);

        verify(chain, times(3)).doFilter(any(), any());
        verify(resp, never()).setStatus(429);
    }

    @Test
    void differentCallersHaveIndependentBudgets() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);

        filter.doFilter(request("POST", "/api/v1/links", "1.1.1.1"), resp, chain);
        filter.doFilter(request("POST", "/api/v1/links", "1.1.1.1"), resp, chain);
        filter.doFilter(request("POST", "/api/v1/links", "2.2.2.2"), resp, chain); // different caller, fresh budget

        verify(chain, times(3)).doFilter(any(), any());
        verify(resp, never()).setStatus(429);
    }

    @Test
    void prefersAnApiKeyHeaderOverRemoteAddrAsTheRateLimitKey() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);

        HttpServletRequest req1 = request("POST", "/api/v1/links", "1.1.1.1");
        when(req1.getHeader("X-API-Key")).thenReturn("key-1");
        HttpServletRequest req2 = request("POST", "/api/v1/links", "9.9.9.9"); // different IP, same key
        when(req2.getHeader("X-API-Key")).thenReturn("key-1");
        HttpServletRequest req3 = request("POST", "/api/v1/links", "1.1.1.1");
        when(req3.getHeader("X-API-Key")).thenReturn("key-1");

        filter.doFilter(req1, resp, chain);
        filter.doFilter(req2, resp, chain);
        filter.doFilter(req3, resp, chain); // same key's 3rd call -> over budget despite IPs differing

        verify(chain, times(2)).doFilter(any(), any());
        verify(resp).setStatus(429);
    }
}
