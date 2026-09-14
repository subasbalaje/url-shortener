package com.sdlc.shortener.store;

import com.sdlc.shortener.model.Link;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteLinkStoreTest {

    private SqliteLinkStore store;
    private String dbUrl;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        dbUrl = "jdbc:sqlite:" + dir.resolve("test.db");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(dbUrl);
        store = new SqliteLinkStore(dataSource);
        store.initSchema();
    }

    @Test
    void createThenGetRoundTrips() {
        Link link = new Link("abc1234", "https://example.com", "2026-09-13T00:00:00Z", null, "active", 0);
        store.create(link);

        Optional<Link> loaded = store.get("abc1234");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().targetUrl()).isEqualTo("https://example.com");
        assertThat(loaded.get().status()).isEqualTo("active");
    }

    @Test
    void getReturnsEmptyForAnUnknownCode() {
        assertThat(store.get("nosuch1")).isEmpty();
    }

    @Test
    void existsReflectsWhatWasCreated() {
        store.create(new Link("abc1234", "https://example.com", "2026-09-13T00:00:00Z", null, "active", 0));
        assertThat(store.exists("abc1234")).isTrue();
        assertThat(store.exists("nosuch1")).isFalse();
    }

    @Test
    void disableIsASoftDeleteNeverARowRemoval() {
        store.create(new Link("abc1234", "https://example.com", "2026-09-13T00:00:00Z", null, "active", 0));
        store.disable("abc1234");

        Optional<Link> loaded = store.get("abc1234");
        assertThat(loaded).isPresent(); // still there
        assertThat(loaded.get().status()).isEqualTo("disabled");
    }

    @Test
    void incrementClickCountAccumulates() {
        store.create(new Link("abc1234", "https://example.com", "2026-09-13T00:00:00Z", null, "active", 0));
        store.incrementClickCount("abc1234");
        store.incrementClickCount("abc1234");

        assertThat(store.get("abc1234").get().clickCount()).isEqualTo(2);
    }

    @Test
    void mappingSurvivesAFreshConnection() {
        // AC6: persistence survives "restart" -- simulated here by opening a
        // second store instance against the same file rather than reusing the
        // connection this test's setUp already established.
        store.create(new Link("abc1234", "https://example.com", "2026-09-13T00:00:00Z", null, "active", 0));

        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(dbUrl);
        SqliteLinkStore reopened = new SqliteLinkStore(dataSource);

        assertThat(reopened.get("abc1234")).isPresent();
    }

    @Test
    void idempotencyKeyRoundTrips() {
        store.create(new Link("abc1234", "https://example.com", "2026-09-13T00:00:00Z", null, "active", 0));
        store.recordIdempotencyKey("key-1", "abc1234");

        assertThat(store.findByIdempotencyKey("key-1")).contains("abc1234");
        assertThat(store.findByIdempotencyKey("no-such-key")).isEmpty();
    }

    @Test
    void createRejectsADuplicateCode() {
        store.create(new Link("abc1234", "https://example.com", "2026-09-13T00:00:00Z", null, "active", 0));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> store.create(new Link("abc1234", "https://other.com", "2026-09-13T00:00:00Z", null, "active", 0)));
    }
}
