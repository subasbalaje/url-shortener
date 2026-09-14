package com.sdlc.shortener.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.function.Predicate;

/**
 * Short-code generation (DEC-0010): random base62 from a CSPRNG, never
 * {@link java.util.Random}, with bounded collision retry.
 *
 * <p>Random over a counter because sequential codes are enumerable — anyone could
 * walk the space and harvest every "unlisted" link, and unpredictability cannot be
 * retrofitted once codes exist. With 62^7 ≈ 3.5×10^12 codes, collision probability
 * at any plausible corpus size is negligible; the retry exists for correctness at
 * the tail, not because collisions are expected.
 */
@Service
public class CodecService {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int CODE_LENGTH = 7;
    private static final int MAX_COLLISION_RETRIES = 5;

    private final SecureRandom random = new SecureRandom();
    private final Predicate<String> exists;

    /** @param exists returns true if the candidate code is already taken (a
     *  {@code LinkStore} lookup in production; a test double in tests). */
    public CodecService(Predicate<String> exists) {
        this.exists = exists;
    }

    public String generate() {
        for (int attempt = 1; attempt <= MAX_COLLISION_RETRIES; attempt++) {
            String candidate = randomCode();
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        throw new CollisionRetriesExhaustedException(MAX_COLLISION_RETRIES);
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Consistent with the orchestrator's own bounded-retry principle: exhausting
     *  the collision budget raises rather than looping unboundedly. */
    public static class CollisionRetriesExhaustedException extends RuntimeException {
        public CollisionRetriesExhaustedException(int maxRetries) {
            super("Exhausted " + maxRetries + " collision retries generating a short code.");
        }
    }
}
