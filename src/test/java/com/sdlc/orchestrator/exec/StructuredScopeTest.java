package com.sdlc.orchestrator.exec;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredScopeTest {

    @Test
    void runsAllTasksConcurrentlyAndReturnsEveryOutcome() throws InterruptedException {
        Map<String, Callable<String>> tasks = new LinkedHashMap<>();
        tasks.put("testing", () -> "test_results");
        tasks.put("documentation", () -> "docs");

        List<StructuredScope.Outcome<String>> outcomes = StructuredScope.forkAll(tasks, Duration.ofSeconds(5));

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes.get(0).label()).isEqualTo("testing");
        assertThat(outcomes.get(0).value()).isEqualTo("test_results");
        assertThat(outcomes.get(0).succeeded()).isTrue();
        assertThat(outcomes.get(1).label()).isEqualTo("documentation");
        assertThat(outcomes.get(1).value()).isEqualTo("docs");
    }

    @Test
    void aFailingTaskDoesNotCancelOrDiscardASiblingsCompletedWork() throws InterruptedException {
        // The Java equivalent of asyncio.gather(return_exceptions=True): testing and
        // documentation are independent, so a testing failure must not discard
        // completed documentation work.
        Map<String, Callable<String>> tasks = new LinkedHashMap<>();
        tasks.put("testing", () -> { throw new IllegalStateException("exit gate failed"); });
        tasks.put("documentation", () -> "docs completed fine");

        List<StructuredScope.Outcome<String>> outcomes = StructuredScope.forkAll(tasks, Duration.ofSeconds(5));

        StructuredScope.Outcome<String> testingOutcome = outcomes.get(0);
        StructuredScope.Outcome<String> docsOutcome = outcomes.get(1);

        assertThat(testingOutcome.succeeded()).isFalse();
        assertThat(testingOutcome.error()).isInstanceOf(IllegalStateException.class);

        assertThat(docsOutcome.succeeded()).isTrue();
        assertThat(docsOutcome.value()).isEqualTo("docs completed fine");
    }

    @Test
    void aTaskExceedingTimeoutIsCancelledAndReportedAsFailed() throws InterruptedException {
        AtomicBoolean interrupted = new AtomicBoolean(false);
        Map<String, Callable<String>> tasks = new LinkedHashMap<>();
        tasks.put("slow", () -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return "too late";
        });

        List<StructuredScope.Outcome<String>> outcomes = StructuredScope.forkAll(tasks, Duration.ofMillis(200));

        assertThat(outcomes.get(0).succeeded()).isFalse();
        assertThat(outcomes.get(0).error()).isInstanceOf(TimeoutException.class);
    }

    @Test
    void tasksActuallyRunConcurrentlyNotSequentially() throws InterruptedException {
        CountDownLatch bothStarted = new CountDownLatch(2);
        Map<String, Callable<String>> tasks = new LinkedHashMap<>();
        tasks.put("a", () -> { bothStarted.countDown(); bothStarted.await(2, TimeUnit.SECONDS); return "a"; });
        tasks.put("b", () -> { bothStarted.countDown(); bothStarted.await(2, TimeUnit.SECONDS); return "b"; });

        // If forkAll ran these sequentially, "a" would block forever waiting for
        // "b" to start (and vice versa), and this would time out.
        List<StructuredScope.Outcome<String>> outcomes = StructuredScope.forkAll(tasks, Duration.ofSeconds(3));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.succeeded()).isTrue());
    }
}
