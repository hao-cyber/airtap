package io.airtap.preview;

import java.util.function.LongSupplier;

/** One non-renewable screen lease per approved generation, measured with a monotonic clock. */
final class SessionScreenLease {
    static final long MAX_MILLIS = 15 * 60 * 1000L;
    interface Lock {
        void acquire(long timeoutMillis);
        boolean isHeld();
        void release();
    }

    private final Lock lock;
    private final LongSupplier elapsed;
    private int generation = -1;
    private long deadline;
    private boolean ended = true;

    SessionScreenLease(Lock lock, LongSupplier elapsed) {
        this.lock = lock;
        this.elapsed = elapsed;
    }

    boolean begin(int run, long remainingMillis, boolean approved, boolean unlocked) {
        if (!approved || !unlocked || remainingMillis <= 0) {
            end();
            return false;
        }
        if (generation == run) return active(run);
        end();
        generation = run;
        deadline = elapsed.getAsLong() + Math.min(remainingMillis, MAX_MILLIS);
        ended = false;
        try {
            lock.acquire(Math.min(remainingMillis, MAX_MILLIS));
            if (active(run)) return true;
        } catch (RuntimeException ignored) { /* The caller must stop the grant, never retry. */ }
        end();
        return false;
    }

    boolean active(int run) {
        return run == generation && !ended && elapsed.getAsLong() < deadline && lock.isHeld();
    }

    void end() {
        ended = true;
        deadline = 0;
        if (lock.isHeld()) lock.release();
    }
}
