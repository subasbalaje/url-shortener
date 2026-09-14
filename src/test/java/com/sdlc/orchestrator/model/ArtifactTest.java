package com.sdlc.orchestrator.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactTest {

    @Test
    void hashIsStableAcrossMapKeyOrder() {
        // Otherwise map iteration order would register as a content change and
        // trigger spurious re-planning/approval invalidation (see class javadoc).
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("acceptance_criteria", "AC1");
        a.put("assumptions", "A1");

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("assumptions", "A1");
        b.put("acceptance_criteria", "AC1");

        assertThat(Artifact.computeHash(a)).isEqualTo(Artifact.computeHash(b));
    }

    @Test
    void hashDiffersWhenContentDiffers() {
        assertThat(Artifact.computeHash(Map.of("v", 1))).isNotEqualTo(Artifact.computeHash(Map.of("v", 2)));
    }

    @Test
    void hashIsSixteenHexChars() {
        String hash = Artifact.computeHash(Map.of("k", "v"));
        assertThat(hash).hasSize(16);
        assertThat(hash).matches("[0-9a-f]{16}");
    }

    @Test
    void constructingWithoutAnExplicitHashComputesOne() {
        Artifact artifact = new Artifact("requirement_spec", "requirements", Map.of("a", 1), "", "", 1);
        assertThat(artifact.contentHash()).isNotBlank();
        assertThat(artifact.contentHash()).isEqualTo(Artifact.computeHash(Map.of("a", 1)));
    }

    @Test
    void constructingWithoutAnExplicitTimestampSetsOne() {
        Artifact artifact = new Artifact("requirement_spec", "requirements", Map.of("a", 1), "", "", 1);
        assertThat(artifact.producedAt()).isNotBlank();
    }

    @Test
    void explicitHashIsPreservedNotRecomputed() {
        Artifact artifact = new Artifact("requirement_spec", "requirements", Map.of("a", 1), "deadbeefdeadbeef", "", 1);
        assertThat(artifact.contentHash()).isEqualTo("deadbeefdeadbeef");
    }
}
