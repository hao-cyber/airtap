package io.airtap.preview;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/** One bounded, cancellable preparation per command. All methods run on the main thread. */
final class ScreenPreparation {
    static final long TIMEOUT_MS = 6000;
    static final long POLL_MS = 100;
    static final long SETTLE_MS = 250;

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
    }
    interface Completion { void ready(boolean recovered); void error(String code); }
    private final Platform platform;
    private Attempt active;

    private final class Attempt {
        final String token = UUID.randomUUID().toString();
        final BooleanSupplier current;
        final Completion completion;
        final long deadline;
        final Runnable tick = () -> poll(this);
        long closingAt = -1;
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
            platform.launch(attempt.token);
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
        State state = platform.state();
        if (state == State.AUTHENTICATION_REQUIRED || state == State.UNAVAILABLE) {
            finish(attempt, state.error()); return;
        }
        if (state == State.READY) {
            if (attempt.closingAt < 0) {
                attempt.closingAt = platform.now();
                platform.close(attempt.token);
            }
            if (platform.now() - attempt.closingAt >= SETTLE_MS && platform.recoveryWindowGone(attempt.token)) {
                finish(attempt, null); return;
            }
        }
        if (platform.now() >= attempt.deadline) { expire(attempt); return; }
        platform.later(attempt.tick, POLL_MS);
    }

    private void expire(Attempt attempt) {
        String error = platform.state().error();
        finish(attempt, error == null ? "device_wake_failed" : error);
    }

    private void finish(Attempt attempt, String error) {
        if (!live(attempt)) return;
        active = null;
        platform.remove(attempt.tick);
        platform.close(attempt.token);
        platform.clearObservations();
        // close() can deliver lifecycle callbacks. Never complete an invalidated command.
        if (!attempt.current.getAsBoolean()) return;
        if (error == null) attempt.completion.ready(true); else attempt.completion.error(error);
    }

    void cancel() {
        Attempt attempt = active;
        if (attempt == null) return;
        active = null;
        platform.remove(attempt.tick);
        platform.close(attempt.token);
        platform.clearObservations();
    }
}
