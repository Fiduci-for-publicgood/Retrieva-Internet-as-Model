package io.retrieva.core.sources;

import java.io.IOException;

/** Per-source rate spacing and a circuit breaker, so 32 concurrent crawlers cannot hammer one host or keep retrying a dead one. */
public final class Politeness {
    private final long minIntervalNanos;
    private final int failureThreshold;
    private final long openNanos;
    private long nextSlot = System.nanoTime();
    private int failures;
    private long openUntil;

    public Politeness(long minIntervalMillis, int failureThreshold, long openMillis) {
        this.minIntervalNanos = minIntervalMillis * 1_000_000L;
        this.failureThreshold = failureThreshold;
        this.openNanos = openMillis * 1_000_000L;
    }

    /** Blocks until this caller's slot. Throws if the breaker is open. Interruptible (the swarm cancels on deadline). */
    public void acquire() throws IOException, InterruptedException {
        long wait;
        synchronized (this) {
            long now = System.nanoTime();
            if (openUntil - now > 0) throw new IOException("circuit open");
            long slot = Math.max(now, nextSlot);
            nextSlot = slot + minIntervalNanos;
            wait = slot - now;
        }
        if (wait > 0) Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
    }

    public synchronized void success() {
        failures = 0;
    }

    public synchronized void failure() {
        if (++failures >= failureThreshold) {
            openUntil = System.nanoTime() + openNanos;
            failures = 0;
        }
    }
}
