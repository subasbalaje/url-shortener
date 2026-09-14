package com.sdlc.shortener.service;

import com.sdlc.shortener.model.Link;
import com.sdlc.shortener.store.LinkStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LinkServiceTest {

    private LinkStore store;
    private ValidationService validation;
    private CacheService cache;
    private LinkService service;
    private final Map<String, Link> backingStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        store = Mockito.mock(LinkStore.class);
        validation = new ValidationService(host -> {
            try {
                return new InetAddress[] { InetAddress.getByName("93.184.216.34") };
            } catch (UnknownHostException e) {
                throw new IllegalStateException("Test resolver failed", e);
            }
        }, "localhost");
        cache = new CacheService(Duration.ofMinutes(5), 100);
        CodecService codec = new CodecService(store::exists);
        service = new LinkService(store, codec, validation, cache);

        when(store.exists(anyString())).thenReturn(false);
        when(store.create(any(Link.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createValidatesTheUrlBeforeStoring() {
        assertThatThrownBy(() -> service.create("bad", null, null, null))
                .isInstanceOf(LinkService.ValidationFailedException.class)
                .hasMessageContaining("Scheme not allowed: null");

        verify(store, never()).create(any());
    }

    @Test
    void createGeneratesACodeWhenNoAliasGiven() {
        Link created = service.create("https://example.com", null, null, null);
        assertThat(created.code()).hasSize(7);
        verify(store).create(any(Link.class));
    }

    @Test
    void createUsesTheCustomAliasWhenValidAndUnused() {
        Link created = service.create("https://example.com", "my-campaign", null, null);
        assertThat(created.code()).isEqualTo("my-campaign");
    }

    @Test
    void createRejectsAReservedOrCollidingAlias() {
        assertThatThrownBy(() -> service.create("https://example.com", "api", null, null))
                .isInstanceOf(LinkService.ValidationFailedException.class)
                .hasMessageContaining("reserved word");
    }

    @Test
    void createReplaysTheOriginalResultForARepeatedIdempotencyKey() {
        Link original = new Link("abc1234", "https://example.com", Instant.now().toString(), null, "active", 0);
        when(store.findByIdempotencyKey("key-1")).thenReturn(Optional.of("abc1234"));
        when(store.get("abc1234")).thenReturn(Optional.of(original));

        Link result = service.create("https://different.com", null, null, "key-1");

        assertThat(result).isEqualTo(original);
        verify(store, never()).create(any()); // no second link created
    }

    @Test
    void redirectReturnsNotFoundForAnUnknownCode() {
        when(store.get("nosuch1")).thenReturn(Optional.empty());
        LinkService.RedirectResult result = service.resolveForRedirect("nosuch1");
        assertThat(result.outcome()).isEqualTo(LinkService.RedirectOutcome.NOT_FOUND);
    }

    @Test
    void redirectReturnsGoneForADisabledLink() {
        Link disabled = new Link("abc1234", "https://example.com", Instant.now().toString(), null, "disabled", 0);
        when(store.get("abc1234")).thenReturn(Optional.of(disabled));
        LinkService.RedirectResult result = service.resolveForRedirect("abc1234");
        assertThat(result.outcome()).isEqualTo(LinkService.RedirectOutcome.GONE);
    }

    @Test
    void redirectReturnsGoneForAnExpiredLink() {
        Link expired = new Link("abc1234", "https://example.com",
                Instant.now().minusSeconds(3600).toString(), Instant.now().minusSeconds(60).toString(), "active", 0);
        when(store.get("abc1234")).thenReturn(Optional.of(expired));
        LinkService.RedirectResult result = service.resolveForRedirect("abc1234");
        assertThat(result.outcome()).isEqualTo(LinkService.RedirectOutcome.GONE);
    }

    @Test
    void redirectReturnsTheTargetForAnActiveLinkAndCachesIt() {
        Link active = new Link("abc1234", "https://example.com", Instant.now().toString(), null, "active", 0);
        when(store.get("abc1234")).thenReturn(Optional.of(active));

        LinkService.RedirectResult first = service.resolveForRedirect("abc1234");
        assertThat(first.outcome()).isEqualTo(LinkService.RedirectOutcome.REDIRECT);
        assertThat(first.targetUrl()).isEqualTo("https://example.com");

        service.resolveForRedirect("abc1234");
        // Second call served from cache -- store.get() called only once.
        verify(store, times(1)).get("abc1234");
    }

    @Test
    void disableInvalidatesTheCache() {
        Link active = new Link("abc1234", "https://example.com", Instant.now().toString(), null, "active", 0);
        when(store.get("abc1234")).thenReturn(Optional.of(active));
        service.resolveForRedirect("abc1234"); // populates the cache

        service.disable("abc1234");
        verify(store).disable("abc1234");

        // Force the store to now report disabled, and confirm the (invalidated)
        // cache does not mask it -- a stale cache entry must not keep a disabled
        // link redirecting.
        Link disabled = new Link("abc1234", "https://example.com", Instant.now().toString(), null, "disabled", 0);
        when(store.get("abc1234")).thenReturn(Optional.of(disabled));
        assertThat(service.resolveForRedirect("abc1234").outcome()).isEqualTo(LinkService.RedirectOutcome.GONE);
    }
}
