package io.airtap.preview;

public final class TaskLeaseTest {
    private static final String A = "a".repeat(32), B = "b".repeat(32), C = "c".repeat(32), D = "d".repeat(32);
    private static int checks;
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static final class Effects implements TaskLease.Effects {
        boolean quiet = true, approved = true, storage = true; int invalidations, persists;
        Runnable onPersist = () -> {};
        public void invalidate() { invalidations++; }
        public boolean quiescent() { return quiet; }
        public boolean approved(String claim) { return approved; }
        public void requireLease(String claim) throws Exception { if (!storage) throw new Exception(); persists++; onPersist.run(); }
    }
    private static TaskLease.Fence fence(String operation, long epoch, long renewal, TaskLease.Holder holder, long ttl) {
        return new TaskLease.Fence(operation, epoch, renewal, holder, ttl);
    }
    public static void main(String[] args) {
        long[] now = {1000}; Effects effects = new Effects(); TaskLease lease = new TaskLease(() -> now[0], effects);
        var a = new TaskLease.Holder(A, A, A); var b = new TaskLease.Holder(B, B, B);
        var first = fence(A, 1, 0, a, 120000);
        check("stale_fence".equals(lease.apply(first)), "no control before hello");
        lease.connected(); check(lease.idle(), "first connection idle");
        effects.quiet = false;
        check("command_in_progress".equals(lease.apply(first)), "pending native action fences acquire");
        check(lease.ticket(A, A, 1) == null, "pending is not authority");
        int invalidations = effects.invalidations;
        now[0] += 500; effects.quiet = true;
        check(lease.apply(first) == null, "same intent completes after drain");
        check(effects.invalidations == invalidations, "retry does not invalidate a second time");
        var ticket = lease.ticket(A, A, 1); check(lease.current(ticket), "approved ticket current");
        check(A.equals(lease.claim()), "current holder can be shown to owner");
        check(lease.ticket(B, A, 1) == null && lease.ticket(A, B, 1) == null && lease.ticket(A, A, 2) == null, "identity tuple required");
        now[0] = 120999; check(lease.apply(first) == null, "duplicate ACK before deadline");
        now[0] = 121000; check(!lease.current(ticket), "retry did not extend original deadline");
        check(lease.claim() == null, "expired holder is not displayed as active");
        check("stale_fence".equals(lease.apply(first)), "expired duplicate cannot revive");
        check("stale_fence".equals(lease.apply(fence(B, 1, 1, a, 120000))), "renew cannot revive expired lease");
        var next = fence(B, 2, 0, a, 120000); check(lease.apply(next) == null, "new epoch recovers");
        ticket = lease.ticket(A, A, 2); now[0] += 1000;
        var renewal = fence(C, 2, 1, a, 120000); check(lease.apply(renewal) == null, "renew accepted");
        check(lease.current(ticket), "renew preserves context");
        check("stale_fence".equals(lease.apply(next)), "old acquire cannot renew");
        check("stale_fence".equals(lease.apply(fence(D, 2, 1, a, 120000))), "equal renewal sequence different id rejected");
        check("stale_fence".equals(lease.apply(fence(C, 2, 2, a, 120000))), "operation id content mutation rejected");
        check("stale_fence".equals(lease.apply(fence(C, 3, 0, b, 120000))), "same operation cannot change epoch and holder");
        check(lease.current(ticket), "rejected operation mutation preserves current holder");
        check("stale_fence".equals(lease.apply(fence(D, 2, 2, b, 120000))), "same epoch cannot switch holder");
        now[0] += 1000; effects.quiet = false;
        var transfer = fence(D, 3, 0, b, 120000);
        check("command_in_progress".equals(lease.apply(transfer)), "transfer waits for IO after timeout");
        check(!lease.current(ticket), "old holder revoked before drain");
        effects.quiet = true; effects.approved = false;
        check("grant_unavailable".equals(lease.apply(transfer)), "remote grant cannot create consent");
        effects.approved = true; effects.storage = false;
        check("storage_failure".equals(lease.apply(transfer)), "persist gate before ACK");
        check(lease.ticket(B, B, 3) == null, "storage failure not ready");
        effects.storage = true; check(lease.apply(transfer) == null, "same intent can retry storage");
        var beforeDisconnect = lease.ticket(B, B, 3); lease.disconnect();
        check(lease.claim() == null, "disconnected holder is not displayed as active");
        check(!lease.current(beforeDisconnect), "disconnect revokes authority");
        lease.connected(); check("stale_fence".equals(lease.apply(transfer)), "same epoch cannot revive after reconnect");
        var release = fence(A, 4, 0, null, 0); check(lease.apply(release) == null && lease.idle(), "higher clear fence ready");
        check(lease.apply(release) == null, "release retry idempotent");
        check("stale_fence".equals(lease.apply(first)), "lower epoch rejected");
        lease.connected();
        check("stale_fence".equals(lease.apply(release)) && !lease.idle(), "old clear intent cannot acknowledge after reconnect");
        var newClear = fence(B, 5, 0, null, 0);
        check(lease.apply(newClear) == null && lease.idle(), "reconnect accepts higher clear fence");
        effects.onPersist = () -> now[0] += 120000;
        check("stale_fence".equals(lease.apply(fence(C, 6, 0, a, 120000))), "persistence finishing after deadline cannot ACK ready");
        check(lease.ticket(A, A, 6) == null, "slow persistence creates no authority");
        effects.onPersist = () -> {};
        check(effects.persists > 0, "grant requirement was persisted");
        long[] renewNow = {1000}; Effects renewEffects = new Effects();
        TaskLease timed = new TaskLease(() -> renewNow[0], renewEffects); timed.connected();
        check(timed.apply(fence(A, 1, 0, a, 120000)) == null, "timed renewal starts ready");
        renewNow[0] = 2000; renewEffects.onPersist = () -> renewNow[0] += 5000;
        check(timed.apply(fence(B, 1, 1, a, 120000)) == null, "bounded persistence renewal succeeds");
        var timedTicket = timed.ticket(A, A, 1); check(timed.current(timedTicket), "renewal ticket current after write");
        renewNow[0] = 122000;
        check(!timed.current(timedTicket), "renewal TTL starts before durable write");
        renewEffects.onPersist = () -> {};
        check(timed.apply(fence(C, 2, 0, a, 120000)) == null, "new epoch after timed expiry");
        renewNow[0] = 123000; renewEffects.onPersist = () -> renewNow[0] += 119000;
        check("stale_fence".equals(timed.apply(fence(D, 2, 1, a, 120000))), "renewal cannot revive lease expiring during persistence");
        check(timed.ticket(A, A, 2) == null, "expired write gives no renewal authority");
        Effects draining = new Effects(); TaskLease recovered = new TaskLease(() -> renewNow[0], draining);
        recovered.connected(); check(recovered.apply(fence(A, 1, 0, a, 120000)) == null, "recovery fixture starts ready");
        var oldTicket = recovered.ticket(A, A, 1); draining.quiet = false;
        check("command_in_progress".equals(recovered.apply(fence(B, 2, 0, null, 0))), "release cannot acknowledge native IO still running");
        recovered.disconnect(); recovered.connected();
        check(!recovered.current(oldTicket), "retired socket cannot restore old native authority");
        var freshAfterRecovery = fence(C, 3, 0, b, 120000);
        check("command_in_progress".equals(recovered.apply(freshAfterRecovery)), "fresh connection does not bypass native drain");
        check(recovered.ticket(B, B, 3) == null, "fresh task has no authority before drain");
        draining.quiet = true;
        check(recovered.apply(freshAfterRecovery) == null, "same fresh fence can finish once native work drains");
        check(recovered.current(recovered.ticket(B, B, 3)) && !recovered.current(oldTicket), "only the newly acknowledged task can act");
        for (long ttl : new long[]{-1,0,120001}) {
            try { fence(A,5,0,a,ttl); throw new AssertionError("bad ttl accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        System.out.println("TaskLease: " + checks + " checks passed");
    }
}
