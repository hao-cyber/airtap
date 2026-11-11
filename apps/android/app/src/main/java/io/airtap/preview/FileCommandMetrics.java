package io.airtap.preview;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Bounded duration/count metadata. Never holds a path, payload or connection identity. */
final class FileCommandMetrics {
    record Snapshot(long elapsedMs, long checkCount, long checkMs, long ioMs, boolean ioFinished) {}
    private final LongSupplier clock;
    private final long started;
    private final AtomicLong checks = new AtomicLong(), checkNanos = new AtomicLong();
    private volatile long ioStarted, ioFinished;
    private volatile boolean beganIo, finishedIo;
    FileCommandMetrics() { this(System::nanoTime); }
    FileCommandMetrics(LongSupplier clock) { this.clock = clock; started = clock.getAsLong(); }
    void check(Runnable action) {
        long before = clock.getAsLong(); checks.incrementAndGet();
        try { action.run(); }
        finally { checkNanos.addAndGet(Math.max(0, clock.getAsLong() - before)); }
    }
    void beginIo() { ioStarted = clock.getAsLong(); beganIo = true; }
    void endIo() { ioFinished = clock.getAsLong(); finishedIo = true; }
    Snapshot snapshot() {
        boolean began = beganIo, finished = finishedIo;
        long now = clock.getAsLong(), begin = ioStarted, end = ioFinished;
        return new Snapshot(millis(now - started), checks.get(), millis(checkNanos.get()),
                !began ? 0 : millis((!finished ? now : end) - begin), finished);
    }
    private static long millis(long nanos) { return Math.max(0, nanos) / 1_000_000; }
}
