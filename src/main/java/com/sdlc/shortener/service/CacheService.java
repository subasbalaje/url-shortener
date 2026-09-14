package com.sdlc.shortener.service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * TTL + LRU cache over {@code code -> targetUrl} for the redirect hot path,
 * cache-aside. Caches the URL <b>only, never the click count</b> — caching an
 * aggregate would silently serve wrong numbers as clicks accumulate elsewhere.
 *
 * <p>Synchronized rather than a concurrent map: the redirect path is read-heavy
 * and simple locking here is not the bottleneck DEC-0002 already named (SQLite's
 * single-writer is); correctness of LRU ordering under concurrent access matters
 * more than shaving lock contention on a cache this small.
 */
public class CacheService {

    private record CacheEntry(String targetUrl, Instant expiresAt) {}

    private final Duration ttl;
    private final int maxSize;
    private final LinkedHashMap<String, CacheEntry> store;

    public CacheService(Duration ttl, int maxSize) {
        this.ttl = ttl;
        this.maxSize = maxSize;
        // accessOrder=true turns this into an LRU: get() moves the entry to the
        // end, and removeEldestEntry evicts from the front once over capacity.
        this.store = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > CacheService.this.maxSize;
            }
        };
    }

    public synchronized void put(String code, String targetUrl) {
        store.put(code, new CacheEntry(targetUrl, Instant.now().plus(ttl)));
    }

    public synchronized Optional<String> get(String code) {
        CacheEntry entry = store.get(code);
        if (entry == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(entry.expiresAt())) {
            store.remove(code);
            return Optional.empty();
        }
        return Optional.of(entry.targetUrl());
    }

    public synchronized void invalidate(String code) {
        store.remove(code);
    }
}
