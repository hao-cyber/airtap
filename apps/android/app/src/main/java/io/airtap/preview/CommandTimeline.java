package io.airtap.preview;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** In-memory timings for the latest authorized file command of each controller. */
final class CommandTimeline {
    enum Phase { admission, preparation, execution, reply_enqueued, reply_rejected, authority_lost, interrupted }
    record Snapshot(long sequence, String action, Phase phase, long elapsedMs, long mainQueueMs,
                    long admissionMs, long preparationMs, long executionReplyMs) {}
    private final LongSupplier clock;
    private final Map<String, Span> latest = new LinkedHashMap<>();
    CommandTimeline() { this(System::nanoTime); }
    CommandTimeline(LongSupplier clock) { this.clock = clock; }

    synchronized Span begin(String owner, String action, long sequence, long received, long mainStarted) {
        Span span = new Span(action, sequence, received, mainStarted);
        latest.remove(owner); latest.put(owner, span);
        while (latest.size() > 8) latest.remove(latest.keySet().iterator().next());
        return span;
    }
    synchronized Snapshot snapshot(String owner) {
        Span span = latest.get(owner); return span == null ? null : span.snapshot();
    }
    synchronized void interrupt() {
        for (Span span : latest.values()) span.finish(Phase.interrupted);
    }
    final class Span {
        private final String action;
        private final long sequence, received, mainStarted;
        private long admitted, executing, ended;
        private boolean hasAdmitted, hasExecuting, finished;
        private Phase phase = Phase.admission;
        private Span(String action, long sequence, long received, long mainStarted) {
            this.action = action; this.sequence = sequence; this.received = received; this.mainStarted = mainStarted;
        }
        synchronized void preparing() {
            if (finished || hasAdmitted) return;
            admitted = clock.getAsLong(); hasAdmitted = true; phase = Phase.preparation;
        }
        synchronized void executing() {
            if (finished || hasExecuting) return;
            executing = clock.getAsLong(); hasExecuting = true; phase = Phase.execution;
        }
        synchronized void finish(Phase result) {
            if (result == Phase.admission || result == Phase.preparation || result == Phase.execution)
                throw new IllegalArgumentException("terminal phase required");
            if (finished) return;
            ended = clock.getAsLong(); finished = true; phase = result;
        }
        synchronized Snapshot snapshot() {
            long end = finished ? ended : clock.getAsLong();
            return new Snapshot(sequence, action, phase, ms(end - received), ms(mainStarted - received),
                    ms((hasAdmitted ? admitted : end) - mainStarted),
                    hasAdmitted ? ms((hasExecuting ? executing : end) - admitted) : 0,
                    hasExecuting ? ms(end - executing) : 0);
        }
    }
    private static long ms(long nanos) { return Math.max(0, nanos) / 1_000_000; }
}
