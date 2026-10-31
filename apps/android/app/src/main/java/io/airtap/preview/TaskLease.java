package io.airtap.preview;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Phone-side task fence. All methods run on the control thread; time is monotonic. */
final class TaskLease {
    static final long MAX_TTL_MS = 120000;
    static final long MAX_INTEGER = 9007199254740991L;
    record Holder(String claim, String task, String lease) {
        Holder { hex(claim); hex(task); hex(lease); }
    }
    record Fence(String operation, long epoch, long renewalSequence, Holder holder, long ttlMs) {
        Fence {
            hex(operation);
            if (epoch < 1 || epoch > MAX_INTEGER || renewalSequence < 0 || renewalSequence > MAX_INTEGER
                    || (holder == null ? ttlMs != 0 || renewalSequence != 0 : ttlMs < 1 || ttlMs > MAX_TTL_MS))
                throw new IllegalArgumentException("invalid_task_fence");
        }
    }
    record Ticket(Holder holder, long epoch, long connection) {}
    interface Effects {
        /** Invalidates snapshots, command gates and shares before attempting to transfer ownership. */
        void invalidate();
        /** Includes gestures and file/provider IO even if their HTTP response already timed out. */
        boolean quiescent();
        boolean approved(String claim) throws Exception;
        /** Persist before ACK. Once true, the old no-lease path can never resume. */
        void requireLease(String claim) throws Exception;
    }
    private final LongSupplier clock;
    private final Effects effects;
    private long connection, epoch, deadline, fenceConnection = -1;
    private Fence latest;
    private boolean ready, connected;

    TaskLease(LongSupplier clock, Effects effects) { this.clock = clock; this.effects = effects; }
    private static void hex(String value) {
        if (value == null || !value.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("invalid_task_identity");
    }
    void connected() {
        // Retain the epoch watermark in this process. Reconnection cannot revive the old lease.
        disconnect(); connected = true;
    }
    void disconnect() {
        connected = false; ready = false; deadline = 0; connection++;
        effects.invalidate();
    }
    private boolean live() { return connected && ready && latest != null && latest.holder != null && clock.getAsLong() < deadline; }
    String apply(Fence next) {
        if (!connected) return "stale_fence";
        boolean identical = next.equals(latest);
        if (identical) {
            if (fenceConnection != connection) return "stale_fence";
            if (ready) return latest.holder == null || live() ? null : "stale_fence";
            // Retry a fence that was waiting for an old gesture/provider call to finish.
            return finish();
        }
        if (latest != null && next.operation.equals(latest.operation)) return "stale_fence";
        if (next.epoch < epoch) return "stale_fence";
        if (next.epoch == epoch) {
            // Only renewal may reuse an epoch. A delayed older renewal cannot extend the clock.
            if (!live() || next.holder == null || !next.holder.equals(latest.holder)
                    || next.renewalSequence <= latest.renewalSequence
                    || next.operation.equals(latest.operation)) return "stale_fence";
            long renewedDeadline = clock.getAsLong() + next.ttlMs;
            try {
                if (!effects.approved(next.holder.claim)) { disconnect(); return "grant_unavailable"; }
                effects.requireLease(next.holder.claim);
            } catch (Exception failure) { disconnect(); return "storage_failure"; }
            // A blocked durable write cannot revive the old lease or add its
            // elapsed time to the advertised TTL.
            if (!live() || clock.getAsLong() >= renewedDeadline) return "stale_fence";
            latest = next; deadline = renewedDeadline;
            return null;
        }
        // New epoch: revoke old authority immediately, before looking at the next grant.
        ready = false; epoch = next.epoch; latest = next; fenceConnection = connection;
        deadline = next.holder == null ? 0 : clock.getAsLong() + next.ttlMs;
        effects.invalidate();
        return finish();
    }
    private String finish() {
        if (!effects.quiescent()) return "command_in_progress";
        if (latest.holder != null) {
            if (clock.getAsLong() >= deadline) return "stale_fence";
            try {
                if (!effects.approved(latest.holder.claim)) return "grant_unavailable";
                effects.requireLease(latest.holder.claim);
            } catch (Exception failure) { return "storage_failure"; }
            if (clock.getAsLong() >= deadline) return "stale_fence";
        }
        ready = true;
        return null;
    }
    Ticket ticket(String claim, String lease, long expectedEpoch) {
        if (!live() || expectedEpoch != epoch || !latest.holder.claim.equals(claim) || !latest.holder.lease.equals(lease)) return null;
        return new Ticket(latest.holder, epoch, connection);
    }
    boolean current(Ticket ticket) {
        return ticket != null && live() && ticket.connection == connection && ticket.epoch == epoch && Objects.equals(ticket.holder, latest.holder);
    }
    boolean idle() { return connected && (latest == null || ready && latest.holder == null); }
    String claim() { return live() ? latest.holder.claim : null; }
    long epoch() { return epoch; }
}
