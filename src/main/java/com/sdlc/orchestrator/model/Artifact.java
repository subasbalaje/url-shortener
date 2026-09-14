package com.sdlc.orchestrator.model;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A value produced by a node, with the identity needed for staleness.
 *
 * <p>{@code contentHash} is what makes two things work: staleness detection (did this
 * artifact actually change, or was it merely regenerated identically?) and approval
 * binding ({@code ApprovalRecord.artifactRef}, so an approval cannot silently
 * transfer to different content).
 *
 * <p>Hashing is SHA-256 over <b>canonical</b> JSON. Key ordering must be stable
 * ({@link MapperFeature#SORT_PROPERTIES_ALPHABETICALLY} +
 * {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS}) — otherwise map iteration
 * order would register as a content change and trigger spurious re-planning and
 * approval invalidation.
 */
public class Artifact {

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final String key;
    private final String producedBy;
    private final Object content;
    private final String contentHash;
    private final String producedAt;
    private final int version;

    public Artifact(String key, String producedBy, Object content, String contentHash, String producedAt, int version) {
        this.key = key;
        this.producedBy = producedBy;
        this.content = content;
        this.contentHash = (contentHash == null || contentHash.isBlank()) ? computeHash(content) : contentHash;
        this.producedAt = (producedAt == null || producedAt.isBlank()) ? Instant.now().toString() : producedAt;
        this.version = version;
    }

    /**
     * Stable SHA-256 over the canonical JSON form, truncated to 16 hex characters —
     * enough to make accidental collisions astronomically unlikely for this
     * artifact-identity use case while keeping ids short in logs and lineage.
     */
    public static String computeHash(Object content) {
        try {
            byte[] blob = CANONICAL_MAPPER.writeValueAsBytes(content);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(blob);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM per the Java security spec;
            // treating its absence as anything other than a fatal misconfiguration
            // would only hide a broken JVM further down the stack.
            throw new IllegalStateException("SHA-256 unavailable", e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Artifact content is not JSON-serialisable", e);
        }
    }

    public String key() { return key; }
    public String producedBy() { return producedBy; }
    public Object content() { return content; }
    public String contentHash() { return contentHash; }
    public String producedAt() { return producedAt; }
    public int version() { return version; }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", key);
        map.put("produced_by", producedBy);
        map.put("content", content);
        map.put("content_hash", contentHash);
        map.put("produced_at", producedAt);
        map.put("version", version);
        return map;
    }

    public static Artifact fromMap(Map<String, Object> map) {
        return new Artifact((String) map.get("key"), (String) map.get("produced_by"), map.get("content"),
                (String) map.get("content_hash"), (String) map.get("produced_at"),
                ((Number) map.getOrDefault("version", 1)).intValue());
    }
}
