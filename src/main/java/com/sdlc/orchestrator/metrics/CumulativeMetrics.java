package com.sdlc.orchestrator.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A thin cumulative roll-up across runs (docs/architecture.md §5.3, §8 limitation
 * 4). Percentiles are withheld below a 10-run threshold: publishing a p99 (or even
 * a p50) from six samples invites confident conclusions from noise.
 */
public final class CumulativeMetrics {

    private static final int MIN_RUNS_FOR_PERCENTILES = 10;

    private final List<Double> latencies = new ArrayList<>();

    public void record(double endToEndSeconds) {
        latencies.add(endToEndSeconds);
    }

    public int runCount() {
        return latencies.size();
    }

    public Double p50() {
        return percentile(50);
    }

    public Double max() {
        if (latencies.size() < MIN_RUNS_FOR_PERCENTILES) {
            return null;
        }
        return Collections.max(latencies);
    }

    private Double percentile(int p) {
        if (latencies.size() < MIN_RUNS_FOR_PERCENTILES) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
