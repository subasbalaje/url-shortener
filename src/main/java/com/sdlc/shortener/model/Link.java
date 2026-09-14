package com.sdlc.shortener.model;

/**
 * A short link. Shapes only — no behaviour (src/README.md).
 *
 * <p>{@code status} is {@code "active"} or {@code "disabled"}; disabling is
 * always a soft delete (DEC-0008) — the row is never removed, only marked.
 */
public record Link(
        String code,
        String targetUrl,
        String createdAt,
        String expiresAt,
        String status,
        long clickCount
) {
    public boolean isActive() {
        return "active".equals(status);
    }
}
