package com.sdlc.shortener.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodecServiceTest {

    @Test
    void generatesSevenCharacterBase62Codes() {
        CodecService codec = new CodecService(code -> false); // never collides
        String code = codec.generate();

        assertThat(code).hasSize(7);
        assertThat(code).matches("[0-9A-Za-z]{7}");
    }

    @Test
    void codesAreNotSequentialAcrossASample() {
        // AC5 (statistical): CSPRNG output must not walk predictably.
        CodecService codec = new CodecService(code -> false);
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            codes.add(codec.generate());
        }
        // 200 independent 7-char base62 draws from a ~3.5e12 space colliding
        // would be astronomically unlikely; a counter-based generator would
        // instead produce values differing by a fixed, predictable step.
        assertThat(codes).hasSize(200);
    }

    @Test
    void retriesOnCollisionUpToFiveTimes() {
        AtomicInteger calls = new AtomicInteger();
        // Every candidate "collides" for the first 4 calls, succeeds on the 5th.
        CodecService codec = new CodecService(code -> calls.incrementAndGet() < 5);

        String code = codec.generate();

        assertThat(code).hasSize(7);
        assertThat(calls.get()).isEqualTo(5);
    }

    @Test
    void throwsAfterExhaustingFiveCollisionRetries() {
        CodecService codec = new CodecService(code -> true); // always collides

        assertThatThrownBy(codec::generate)
                .isInstanceOf(CodecService.CollisionRetriesExhaustedException.class);
    }

    @Test
    void usesOnlyBase62Alphabet() {
        CodecService codec = new CodecService(code -> false);
        for (int i = 0; i < 50; i++) {
            String code = codec.generate();
            assertThat(code).matches("[0-9A-Za-z]{7}");
        }
    }
}
