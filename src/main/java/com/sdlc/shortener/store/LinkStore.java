package com.sdlc.shortener.store;

import com.sdlc.shortener.model.Link;

import java.util.Optional;

/**
 * Storage seam (src/README.md): the interface that makes "swap to Postgres" a
 * swap rather than a rewrite. SQLite's single-writer limit (DEC-0002) is a
 * stated, accepted trade-off for a prototype, addressed here if it ever needs to
 * change.
 */
public interface LinkStore {

    void initSchema();

    Link create(Link link);

    Optional<Link> get(String code);

    boolean exists(String code);

    /** Soft delete only — sets status, never removes the row (DEC-0008). */
    void disable(String code);

    void incrementClickCount(String code);

    /** @return the code already associated with this idempotency key, if any. */
    Optional<String> findByIdempotencyKey(String idempotencyKey);

    void recordIdempotencyKey(String idempotencyKey, String code);
}
