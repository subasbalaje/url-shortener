package com.sdlc.shortener.config;

import java.util.function.LongSupplier;

/** Classic token bucket: {@code capacity} tokens max, refilled continuously at
 *  {@code refillPerSecond}. Thread-safe via a single lock — buckets are small,
 *  short-lived, and per-key, so contention is not a concern. */
public class TokenBucket {

    private final int capacity;
    private final double refillPerSecond;
    private final LongSupplier nanoClock;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(int capacity, double refillPerSecond) {
        this(capacity, refillPerSecond, System::nanoTime);
    }

    /** @param nanoClock injectable for deterministic tests — real wall-clock
     *  timing between two consecutive calls is not reliably sub-millisecond in a
     *  JIT-warming test JVM, which makes real-time-based tests of a high refill
     *  rate flaky for reasons unrelated to the bucket's own logic. */
    TokenBucket(int capacity, double refillPerSecond, LongSupplier nanoClock) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.nanoClock = nanoClock;
        this.tokens = capacity;
        this.lastRefillNanos = nanoClock.getAsLong();
    }

    public synchronized boolean tryConsume() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = nanoClock.getAsLong();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        lastRefillNanos = now;
        tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
    }
}
