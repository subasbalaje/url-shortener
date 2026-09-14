package com.sdlc.shortener.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CacheServiceTest {

    @Test
    void cachesAndReturnsAUrl() {
        CacheService cache = new CacheService(Duration.ofMinutes(5), 100);
        cache.put("abc1234", "https://example.com");
        assertThat(cache.get("abc1234")).contains("https://example.com");
    }

    @Test
    void missingEntryReturnsEmpty() {
        CacheService cache = new CacheService(Duration.ofMinutes(5), 100);
        assertThat(cache.get("nope")).isEmpty();
    }

    @Test
    void invalidateRemovesAnEntryImmediately() {
        // Must invalidate on disable/expire -- a stale cache entry would keep
        // redirecting a link that was taken down.
        CacheService cache = new CacheService(Duration.ofMinutes(5), 100);
        cache.put("abc1234", "https://example.com");
        cache.invalidate("abc1234");
        assertThat(cache.get("abc1234")).isEmpty();
    }

    @Test
    void entryExpiresAfterItsTtl() throws InterruptedException {
        CacheService cache = new CacheService(Duration.ofMillis(20), 100);
        cache.put("abc1234", "https://example.com");
        Thread.sleep(50);
        assertThat(cache.get("abc1234")).isEmpty();
    }

    @Test
    void evictsTheLeastRecentlyUsedEntryWhenOverCapacity() {
        CacheService cache = new CacheService(Duration.ofMinutes(5), 2);
        cache.put("a", "urlA");
        cache.put("b", "urlB");
        cache.get("a"); // touch "a" so "b" becomes the least recently used
        cache.put("c", "urlC"); // forces an eviction

        assertThat(cache.get("a")).isPresent();
        assertThat(cache.get("b")).isEmpty();
        assertThat(cache.get("c")).isPresent();
    }
}
