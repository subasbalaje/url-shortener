package com.sdlc.shortener.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketTest {

    /** A fake nanosecond clock the test fully controls, so refill amounts are
     *  exact rather than dependent on real wall-clock timing between calls
     *  (which is not reliably sub-millisecond in a JIT-warming test JVM). */
    private static final class FakeClock implements LongSupplier {
        private long nanos = 0;
        @Override public long getAsLong() { return nanos; }
        void advance(long millis) { nanos += millis * 1_000_000L; }
    }

    private TokenBucket bucketWithClock(int capacity, double refillPerSecond, FakeClock clock) {
        try {
            Constructor<TokenBucket> ctor = TokenBucket.class.getDeclaredConstructor(int.class, double.class, LongSupplier.class);
            ctor.setAccessible(true);
            return ctor.newInstance(capacity, refillPerSecond, clock);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void allowsRequestsUpToCapacity() {
        TokenBucket bucket = new TokenBucket(3, 0.0); // no refill
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isFalse(); // capacity exhausted
    }

    @Test
    void refillsOverTime() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = bucketWithClock(1, 100.0, clock); // 100 tokens/sec
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isFalse();

        clock.advance(30); // 100/sec * 0.03s = 3 tokens' worth, capped at capacity 1

        assertThat(bucket.tryConsume()).isTrue();
    }

    @Test
    void neverExceedsCapacityEvenAfterALongIdlePeriod() {
        FakeClock clock = new FakeClock();
        TokenBucket bucket = bucketWithClock(2, 1000.0, clock);
        clock.advance(20); // would refill far more than capacity at this rate
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isFalse(); // no time advanced since -> no refill
    }
}
