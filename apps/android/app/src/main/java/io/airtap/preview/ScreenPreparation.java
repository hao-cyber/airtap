package io.airtap.preview;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/** One bounded, cancellable preparation per command. All methods run on the main thread. */
final class ScreenPreparation {
    static final long TIMEOUT_MS = 6000;
    static final long POLL_MS = 100;
    static final long SETTLE_MS = 250;
    static final long WAKE_FALLBACK_MS = 1200;

    enum Diagnostic { window_not_ready, bootstrap_requested, dismiss_requested, api_success, api_error, api_cancelled, api_exception, timeout }

    enum State {
        READY("ready"), SCREEN_OFF("screen_off"), DISMISSIBLE_KEYGUARD("dismissible_keyguard"),
        AUTHENTICATION_REQUIRED("authentication_required"), UNAVAILABLE("unavailable");
        final String wireName;
        State(String wireName) { this.wireName = wireName; }
        String error() {
            return switch (this) {
                case READY -> null;
                case SCREEN_OFF -> "device_wake_failed";
                case DISMISSIBLE_KEYGUARD -> "device_keyguard_dismiss_failed";
                case AUTHENTICATION_REQUIRED -> "device_authentication_required";
                case UNAVAILABLE -> "device_state_unavailable";
            };
        }
        static State of(boolean available, boolean interactive, boolean keyguard, boolean deviceLocked) {
            if (!available) return UNAVAILABLE;
            if (deviceLocked) return AUTHENTICATION_REQUIRED;
            if (!interactive) return SCREEN_OFF;
            return keyguard ? DISMISSIBLE_KEYGUARD : READY;
        }
    }

    interface Platform {
        State state();
        long now();
        void later(Runnable callback, long delayMillis);
        void remove(Runnable callback);
        void launch(String token);
        void close(String token);
        boolean recoveryWindowGone(String token);
        void clearObservations();
        default void diagnostic(Diagnostic branch) { }
        default void progress(String token) { }
        default void relocked(String token) { }
        default void wakeFallback(String token, long remainingMillis) { }
        default void releaseWake(String token) { }
        default boolean recoveryStopped(String token) { return false; }
        default String windowDiagnostic(String token) { return "unavailable"; }
    }
    interface Completion {
        void ready(boolean recovered);
        void error(String code);
        default void error(String code, String detail) { error(code); }
    }
    private final Platform platform;
    private Attempt active;

    private final class Attempt {
        final String token = UUID.randomUUID().toString();
        final BooleanSupplier current;
        final Completion completion;
        final long deadline;
        final Runnable tick = () -> poll(this);
        long readySince = -1;
        long closingAt = -1;
        long goneSince = -1;
        long fallbackWakeAt = -1;
        int dismissRequests, launches;
        boolean dismissRetryAllowed;
        boolean wakeFallbackRequested;
        boolean fallbackWakeObserved;
        Diagnostic lastDiagnostic;
        Attempt(BooleanSupplier current, Completion completion) {
            this.current = current; this.completion = completion;
            deadline = platform.now() + TIMEOUT_MS;
        }
    }

    ScreenPreparation(Platform platform) { this.platform = platform; }

    void prepare(BooleanSupplier current, Completion completion) {
        if (!current.getAsBoolean()) return;
        if (active != null) { completion.error("busy"); return; }
        State state = platform.state();
        if (state == State.READY) { completion.ready(false); return; }
        // A lock transition invalidates the coordinates/nodes even when recovery needs authentication.
        platform.clearObservations();
        if (state == State.AUTHENTICATION_REQUIRED || state == State.UNAVAILABLE) {
            completion.error(state.error()); return;
        }
        Attempt attempt = new Attempt(current, completion); active = attempt;
        try {
            launch(attempt);
            if (live(attempt)) platform.later(attempt.tick, POLL_MS);
        } catch (RuntimeException failure) { finish(attempt, "device_wake_failed"); }
    }

    /** Must succeed immediately before every wake/dismiss API, including a delayed Activity start. */
    boolean permit(String token) {
        Attempt attempt = active;
        if (attempt == null || !attempt.token.equals(token) || !live(attempt)) return false;
        if (platform.now() >= attempt.deadline) { expire(attempt); return false; }
        State state = platform.state();
        if (state == State.AUTHENTICATION_REQUIRED || state == State.UNAVAILABLE) {
            finish(attempt, state.error()); return false;
        }
        return attempt.closingAt < 0;
    }

    /** A verified fallback wake may unblock the remaining request without a first-draw dependency. */
    boolean canBootstrap(String token) {
        return permit(token) && active != null && active.token.equals(token)
                && (active.dismissRequests == 0 || (active.fallbackWakeObserved
                && active.dismissRetryAllowed && active.dismissRequests < 2));
    }

    void recordDiagnostic(Diagnostic branch) {
        // Timeout already has a separate elapsed/deadline outcome; retain the last useful event.
        if (active != null && branch != Diagnostic.timeout) active.lastDiagnostic = branch;
    }

    void allowDismissRetry(String token) {
        if (permit(token) && active != null && active.token.equals(token)) active.dismissRetryAllowed = true;
    }

    /** Shared by all Activity instances belonging to this attempt. */
    int reserveDismiss(String token) {
        if (!permit(token)) return 0;
        Attempt attempt = active;
        if (attempt.dismissRequests >= 2) {
            finish(attempt, "device_keyguard_dismiss_failed");
            return 0;
        }
        if (attempt.dismissRequests > 0 && !attempt.dismissRetryAllowed) return 0;
        attempt.dismissRetryAllowed = false;
        return ++attempt.dismissRequests;
    }

    void failed(String token, String code) {
        Attempt attempt = active;
        if (attempt != null && attempt.token.equals(token) && live(attempt)) {
            State state = platform.state();
            finish(attempt, state == State.AUTHENTICATION_REQUIRED || state == State.UNAVAILABLE ? state.error() : code);
        }
    }

    private boolean live(Attempt attempt) {
        if (active != attempt) return false;
        if (!attempt.current.getAsBoolean()) { cancel(); return false; }
        return true;
    }

    private void poll(Attempt attempt) {
        if (!live(attempt)) return;
        if (platform.now() >= attempt.deadline) { expire(attempt); return; }
        State state = platform.state();
        if (state == State.AUTHENTICATION_REQUIRED || state == State.UNAVAILABLE) {
            finish(attempt, state.error()); return;
        }
        if (state == State.SCREEN_OFF && !attempt.wakeFallbackRequested
                && platform.now() >= attempt.deadline - TIMEOUT_MS + WAKE_FALLBACK_MS
                && attempt.closingAt < 0 && permit(attempt.token)) {
            attempt.wakeFallbackRequested = true;
            platform.wakeFallback(attempt.token, attempt.deadline - platform.now());
            if (!live(attempt)) return;
        }
        if (state == State.DISMISSIBLE_KEYGUARD && attempt.wakeFallbackRequested && !attempt.fallbackWakeObserved) {
            // A dismissal sent while dozing can remain pending without a callback. An observed
            // screen-on transition permits one new native dismissal within the original two-call cap.
            attempt.fallbackWakeObserved = true;
            attempt.fallbackWakeAt = platform.now();
            attempt.dismissRetryAllowed = true;
            platform.relocked(attempt.token);
            if (!live(attempt)) return;
        }
        if (state == State.READY) {
            if (attempt.readySince < 0) attempt.readySince = platform.now();
            if (attempt.closingAt < 0 && platform.now() - attempt.readySince >= SETTLE_MS) {
                attempt.closingAt = platform.now();
                platform.close(attempt.token);
            }
            if (attempt.closingAt >= 0) {
                if (!platform.recoveryWindowGone(attempt.token)) attempt.goneSince = -1;
                else {
                    if (attempt.goneSince < 0) attempt.goneSince = platform.now();
                    if (platform.now() - attempt.goneSince >= SETTLE_MS) {
                        finish(attempt, null); return;
                    }
                }
            }
        } else {
            attempt.goneSince = -1;
            if (attempt.readySince >= 0) {
                attempt.readySince = -1;
                attempt.dismissRetryAllowed = true;
                platform.relocked(attempt.token);
                if (!live(attempt)) return;
            }
            if (state == State.DISMISSIBLE_KEYGUARD && attempt.fallbackWakeObserved
                    && attempt.closingAt < 0 && attempt.launches < 2 && attempt.dismissRequests < 2
                    && platform.now() - attempt.fallbackWakeAt >= SETTLE_MS
                    && platform.recoveryStopped(attempt.token) && permit(attempt.token)) {
                // A native wake can leave the pre-wake Activity stopped behind keyguard.
                // Give its lifecycle time to resume, then replace only that stale window.
                // The existing close/reopen path waits for removal and keeps both budgets.
                attempt.closingAt = platform.now();
                platform.close(attempt.token);
            }
            if (attempt.closingAt >= 0) {
                // Reopen only after the previous temporary window is gone; keep the same budget.
                if (platform.recoveryWindowGone(attempt.token)) {
                    attempt.closingAt = -1;
                    try { launch(attempt); }
                    catch (RuntimeException failure) { finish(attempt, "device_wake_failed"); }
                }
            } else {
                platform.progress(attempt.token);
            }
        }
        if (!live(attempt)) return;
        if (platform.now() >= attempt.deadline) { expire(attempt); return; }
        platform.later(attempt.tick, POLL_MS);
    }

    private void launch(Attempt attempt) {
        if (!live(attempt)) return;
        if (platform.now() >= attempt.deadline) { expire(attempt); return; }
        State state = platform.state();
        if (state == State.AUTHENTICATION_REQUIRED || state == State.UNAVAILABLE) {
            finish(attempt, state.error()); return;
        }
        if (!live(attempt)) return;
        if (platform.now() >= attempt.deadline) { expire(attempt); return; }
        if (attempt.launches >= 2) {
            String error = state.error();
            finish(attempt, error == null ? "device_wake_failed" : error);
            return;
        }
        attempt.launches++;
        platform.launch(attempt.token);
    }

    private void expire(Attempt attempt) {
        platform.diagnostic(Diagnostic.timeout);
        String error = platform.state().error();
        finish(attempt, error == null ? "device_wake_failed" : error);
    }

    private void finish(Attempt attempt, String error) {
        if (!live(attempt)) return;
        // Platform state/window queries can cross the deadline before returning.
        if (error == null && platform.now() >= attempt.deadline) {
            platform.diagnostic(Diagnostic.timeout);
            error = platform.state().error();
            if (error == null) error = "device_wake_failed";
        }
        String detail = "Screen preparation: state=" + platform.state().wireName
                + ", elapsed_ms=" + Math.max(0, Math.min(60000, platform.now() - attempt.deadline + TIMEOUT_MS))
                + ", launches=" + attempt.launches + ", dismiss_requests=" + attempt.dismissRequests
                + ", wake_fallback=" + attempt.wakeFallbackRequested
                + ", last_event=" + (attempt.lastDiagnostic == null ? "none" : attempt.lastDiagnostic)
                + ", window=[" + platform.windowDiagnostic(attempt.token) + "]"
                + ". The requested action did not run; retain the pairing and inspect recovery before retrying.";
        active = null;
        platform.remove(attempt.tick);
        platform.releaseWake(attempt.token);
        platform.close(attempt.token);
        platform.clearObservations();
        // close() can deliver lifecycle callbacks. Never complete an invalidated command.
        if (!attempt.current.getAsBoolean()) return;
        if (error == null) attempt.completion.ready(true); else attempt.completion.error(error, detail);
    }

    void cancel() {
        Attempt attempt = active;
        if (attempt == null) return;
        active = null;
        platform.remove(attempt.tick);
        platform.releaseWake(attempt.token);
        platform.close(attempt.token);
        platform.clearObservations();
    }
}
