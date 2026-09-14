package com.sdlc.shortener.store;

import com.sdlc.shortener.model.Link;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * Plain JDBC (src/README.md, DEC-0002) — deliberately no JPA/Hibernate: an ORM's
 * auto-DDL would hide exactly the schema change the {@code apply_migration} human
 * gate exists to review. Try-with-resources on every {@code Connection}/
 * {@code PreparedStatement}; parameterized queries only.
 */
@Repository
public class SqliteLinkStore implements LinkStore {

    private final DataSource dataSource;

    public SqliteLinkStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void initSchema() {
        String sql = """
                CREATE TABLE IF NOT EXISTS links (
                  code        TEXT PRIMARY KEY,
                  target_url  TEXT NOT NULL,
                  created_at  TEXT NOT NULL,
                  expires_at  TEXT,
                  status      TEXT NOT NULL DEFAULT 'active',
                  click_count INTEGER NOT NULL DEFAULT 0
                );
                CREATE INDEX IF NOT EXISTS idx_links_status ON links(status);
                CREATE TABLE IF NOT EXISTS idempotency_keys (
                  idempotency_key TEXT PRIMARY KEY,
                  code            TEXT NOT NULL,
                  created_at      TEXT NOT NULL
                );
                """;
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            for (String statement : sql.split(";")) {
                if (!statement.isBlank()) {
                    stmt.execute(statement);
                }
            }
        } catch (SQLException e) {
            throw new LinkStoreException("Failed to initialise schema", e);
        }
    }

    @Override
    public Link create(Link link) {
        String sql = "INSERT INTO links (code, target_url, created_at, expires_at, status, click_count) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, link.code());
            ps.setString(2, link.targetUrl());
            ps.setString(3, link.createdAt());
            ps.setString(4, link.expiresAt());
            ps.setString(5, link.status());
            ps.setLong(6, link.clickCount());
            ps.executeUpdate();
            return link;
        } catch (SQLException e) {
            throw new LinkStoreException("Failed to create link '" + link.code() + "'", e);
        }
    }

    @Override
    public Optional<Link> get(String code) {
        String sql = "SELECT code, target_url, created_at, expires_at, status, click_count FROM links WHERE code = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Link(
                        rs.getString("code"), rs.getString("target_url"), rs.getString("created_at"),
                        rs.getString("expires_at"), rs.getString("status"), rs.getLong("click_count")));
            }
        } catch (SQLException e) {
            throw new LinkStoreException("Failed to read link '" + code + "'", e);
        }
    }

    @Override
    public boolean exists(String code) {
        String sql = "SELECT 1 FROM links WHERE code = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new LinkStoreException("Failed to check existence of '" + code + "'", e);
        }
    }

    @Override
    public void disable(String code) {
        String sql = "UPDATE links SET status = 'disabled' WHERE code = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LinkStoreException("Failed to disable link '" + code + "'", e);
        }
    }

    @Override
    public void incrementClickCount(String code) {
        String sql = "UPDATE links SET click_count = click_count + 1 WHERE code = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LinkStoreException("Failed to increment click count for '" + code + "'", e);
        }
    }

    @Override
    public Optional<String> findByIdempotencyKey(String idempotencyKey) {
        String sql = "SELECT code FROM idempotency_keys WHERE idempotency_key = ?";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("code")) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LinkStoreException("Failed to look up idempotency key", e);
        }
    }

    @Override
    public void recordIdempotencyKey(String idempotencyKey, String code) {
        String sql = "INSERT INTO idempotency_keys (idempotency_key, code, created_at) VALUES (?, ?, ?)";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, idempotencyKey);
            ps.setString(2, code);
            ps.setString(3, java.time.Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LinkStoreException("Failed to record idempotency key", e);
        }
    }

    public static class LinkStoreException extends RuntimeException {
        public LinkStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
