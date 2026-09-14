package com.sdlc.shortener.service;

import com.sdlc.shortener.model.Link;
import com.sdlc.shortener.store.LinkStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Create/redirect/disable business logic (DEC-0008, DEC-0009, DEC-0010).
 *
 * <p><b>Stated cache trade-off:</b> the redirect-hot-path cache
 * ({@link CacheService}) is invalidated immediately on {@link #disable} (a
 * disabled link can never keep redirecting from a stale cache entry), but
 * time-based expiry is bounded by the cache's own TTL rather than checked on
 * every cached hit — an expired link can redirect for up to one cache TTL after
 * its {@code expires_at} passes. This is a deliberate, bounded trade-off (not an
 * oversight): disable is the safety-critical case (a link taken down for abuse
 * must stop immediately), or expiry is a soft time boundary where a short bounded
 * staleness window is an acceptable, common trade-off.
 */
@Service
public class LinkService {

    private final LinkStore store;
    private final CodecService codec;
    private final ValidationService validation;
    private final CacheService cache;

    public LinkService(LinkStore store, CodecService codec, ValidationService validation, CacheService cache) {
        this.store = store;
        this.codec = codec;
        this.validation = validation;
        this.cache = cache;
    }

    public Link create(String targetUrl, String customAlias, String expiresAt, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<String> existingCode = store.findByIdempotencyKey(idempotencyKey);
            if (existingCode.isPresent()) {
                // Replay: return the original result, never create a second link.
                return store.get(existingCode.get())
                        .orElseThrow(() -> new IllegalStateException("Idempotency key points at a missing link."));
            }
        }

        ValidationService.ValidationResult urlResult = validation.validateUrl(targetUrl);
        if (!urlResult.valid()) {
            throw new ValidationFailedException(urlResult.reason());
        }

        String code;
        if (customAlias != null && !customAlias.isBlank()) {
            ValidationService.ValidationResult aliasResult = validation.validateAlias(customAlias);
            if (!aliasResult.valid()) {
                throw new ValidationFailedException(aliasResult.reason());
            }
            if (store.exists(customAlias)) {
                throw new ValidationFailedException("Alias '" + customAlias + "' is already in use.");
            }
            code = customAlias;
        } else {
            code = codec.generate();
        }

        Link link = new Link(code, targetUrl, Instant.now().toString(), expiresAt, "active", 0);
        Link created = store.create(link);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            store.recordIdempotencyKey(idempotencyKey, code);
        }
        return created;
    }

    public enum RedirectOutcome { REDIRECT, GONE, NOT_FOUND }

    public record RedirectResult(RedirectOutcome outcome, String targetUrl) {
        static RedirectResult redirect(String url) { return new RedirectResult(RedirectOutcome.REDIRECT, url); }
        static RedirectResult gone() { return new RedirectResult(RedirectOutcome.GONE, null); }
        static RedirectResult notFound() { return new RedirectResult(RedirectOutcome.NOT_FOUND, null); }
    }

    public RedirectResult resolveForRedirect(String code) {
        Optional<String> cached = cache.get(code);
        if (cached.isPresent()) {
            return RedirectResult.redirect(cached.get());
        }

        Optional<Link> link = store.get(code);
        if (link.isEmpty()) {
            return RedirectResult.notFound();
        }
        Link found = link.get();
        if (!found.isActive() || isExpired(found)) {
            return RedirectResult.gone();
        }

        cache.put(code, found.targetUrl());
        return RedirectResult.redirect(found.targetUrl());
    }

    public Optional<Link> getMetadata(String code) {
        return store.get(code);
    }

    public void disable(String code) {
        store.disable(code);
        cache.invalidate(code);
    }

    private boolean isExpired(Link link) {
        return link.expiresAt() != null && Instant.parse(link.expiresAt()).isBefore(Instant.now());
    }

    public static class ValidationFailedException extends RuntimeException {
        public ValidationFailedException(String message) {
            super(message);
        }
    }
}
