package com.sdlc.orchestrator.exec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Structured fan-out over virtual threads.
 *
 * <p>Wraps {@code Executors.newVirtualThreadPerTaskExecutor()} (JEP 444, final in
 * Java 21) rather than {@code StructuredTaskScope}, which remains a preview API
 * through JDK 26 and would force {@code --enable-preview} on anyone running the
 * tests (DEC-0013). This class is the single point of change when it finalizes.
 *
 * <p><b>Failures do not cancel siblings</b> — the Java equivalent of asyncio's
 * {@code return_exceptions=True}. Testing and documentation are independent
 * branches: a testing failure must not discard completed documentation work. Each
 * result is routed to success/failure handling individually.
 */
public final class StructuredScope {

    private StructuredScope() {}

    public record Outcome<T>(String label, T value, Throwable error) {
        public boolean succeeded() { return error == null; }
    }

    /** Run all tasks concurrently, await all, and return every outcome — in the
     *  same order {@code tasks} was iterated. */
    public static <T> List<Outcome<T>> forkAll(Map<String, Callable<T>> tasks, Duration timeout)
            throws InterruptedException {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<T>> futures = new LinkedHashMap<>();
            tasks.forEach((label, task) -> futures.put(label, executor.submit(task)));

            List<Outcome<T>> outcomes = new ArrayList<>();
            for (var entry : futures.entrySet()) {
                try {
                    outcomes.add(new Outcome<>(entry.getKey(),
                            entry.getValue().get(timeout.toMillis(), TimeUnit.MILLISECONDS), null));
                } catch (ExecutionException e) {
                    outcomes.add(new Outcome<>(entry.getKey(), null, e.getCause()));
                } catch (TimeoutException e) {
                    entry.getValue().cancel(true);
                    outcomes.add(new Outcome<>(entry.getKey(), null, e));
                }
            }
            return outcomes;
        }
    }
}
