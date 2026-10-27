package io.airtap.preview;

import java.util.function.Supplier;

/** No Android dependency: the actual snapshot lifetime and dirty-content decision. */
final class SnapshotGuard {
    static final long TTL_MS = 30000;
    /** Diagnostic-only labels. They never participate in the lifetime or authorization checks. */
    enum Reason {
        NONE, EXTERNAL_CLEAR, CONSUMED, CONTENT_DIRTY,
        EVENT_WINDOW_STATE, EVENT_WINDOWS, EVENT_SCROLL, EVENT_TEXT,
        MISSING, ID_MISMATCH, PACKAGE_MISMATCH, WINDOW_CHANGED, EXPIRED, CLOCK_REVERSED,
        REVISION_CHANGED, READ_INCONSISTENT, ORIGINAL_CHANGED, SECOND_SCAN_CHANGED,
        SCAN_BUDGET, INCOMPLETE, TARGET_MISSING, TARGET_REFRESH, TARGET_WINDOW,
        TARGET_PACKAGE, TARGET_HIDDEN, TARGET_DISABLED, TARGET_SENSITIVE, TARGET_IDENTITY,
        DEVICE_LOCKED, NOT_FOREGROUND, UNEXPECTED_WINDOW, SENSITIVE_INPUT, INSPECTION_LIMIT,
        NOT_ACTIONABLE, NOT_EDITABLE, NOT_SCROLLABLE, UNSUPPORTED_ACTION,
        PERMISSION_DENIED, ACTION_REJECTED, UNEXPECTED_FAILURE
    }
    private Reason failureReason = Reason.NONE;
    private Reason lastInvalidation = Reason.NONE;
    private long revision;
    private String id;
    private String packageName;
    private int window = -1;
    private long deadline;
    private byte[] fingerprint;
    private boolean dirty;

    /** The whole Android action pipeline uses this boundary, including checks after verify(). */
    static <T> T action(Supplier<T> operation, Runnable invalidate) {
        try { return operation.get(); }
        catch (RuntimeException | Error failure) {
            invalidate.run();
            throw failure;
        }
    }

    void clear() {
        if (lastInvalidation == Reason.NONE) lastInvalidation = Reason.EXTERNAL_CLEAR;
        revision++;
        id = null;
        packageName = null;
        window = -1;
        deadline = 0;
        fingerprint = null;
        dirty = false;
    }

    long beginRead(int nextWindow) {
        clear(); window = nextWindow;
        lastInvalidation = Reason.NONE;
        failureReason = Reason.NONE;
        return revision;
    }
    boolean hasContext() { return window != -1; }
    Reason failureReason() { return failureReason; }
    Reason lastInvalidation() { return lastInvalidation; }
    void recordInvalidation(Reason reason) {
        // A later missing-snapshot request must not erase the event that invalidated it.
        if (reason != Reason.NONE && reason != Reason.MISSING) lastInvalidation = reason;
    }
    private boolean failed(Reason reason) { failureReason = reason; return false; }
    long revision() { return revision; }
    int window() { return window; }
    boolean dirty() { return dirty; }
    void contentChanged(int changedWindow) {
        if (changedWindow != window) return;
        revision++;
        if (id != null) dirty = true;
    }
    boolean unchanged(long value) { return revision == value; }

    boolean publish(String nextId, String nextPackage, int nextWindow, byte[] original,
                    byte[] repeated, long startedAt, long now, long capturedRevision) {
        // Same short-circuit order as the policy; labels do not add observations.
        if (!unchanged(capturedRevision)) return failed(Reason.REVISION_CHANGED);
        if (window != nextWindow) return failed(Reason.WINDOW_CHANGED);
        if (now < startedAt) return failed(Reason.CLOCK_REVERSED);
        if (now - startedAt >= TTL_MS) return failed(Reason.EXPIRED);
        if (!CompleteTree.same(original, repeated)) return failed(Reason.READ_INCONSISTENT);
        id = nextId;
        packageName = nextPackage;
        fingerprint = original.clone();
        deadline = startedAt + TTL_MS; // Scanning and later validation never renew this deadline.
        dirty = false;
        failureReason = Reason.NONE;
        lastInvalidation = Reason.NONE;
        return true;
    }

    boolean current(String requestedId, String requestedPackage, int currentWindow, long now) {
        if (id == null) return failed(Reason.MISSING);
        if (!id.equals(requestedId)) return failed(Reason.ID_MISMATCH);
        if (!packageName.equals(requestedPackage)) return failed(Reason.PACKAGE_MISMATCH);
        if (window != currentWindow) return failed(Reason.WINDOW_CHANGED);
        if (now >= deadline) return failed(Reason.EXPIRED);
        failureReason = Reason.NONE;
        return true;
    }

    boolean verify(byte[] first, byte[] repeated, long capturedRevision, long now) {
        if (id == null) return failed(Reason.MISSING);
        if (!unchanged(capturedRevision)) return failed(Reason.REVISION_CHANGED);
        if (now >= deadline) return failed(Reason.EXPIRED);
        if (!CompleteTree.same(fingerprint, first)) return failed(Reason.ORIGINAL_CHANGED);
        if (!CompleteTree.same(first, repeated)) return failed(Reason.SECOND_SCAN_CHANGED);
        dirty = false;
        failureReason = Reason.NONE;
        return true;
    }

    long remaining(long now) { return Math.max(0, deadline - now); }
}
