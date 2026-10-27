package io.airtap.preview;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Host JVM tests of the production state machine; no Android device or instrumentation required. */
public final class ScreenPreparationTest {
    private static int scenarios;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static final class Reply implements ScreenPreparation.Completion {
        int ready, errors;
        boolean recovered;
        String code;
        public void ready(boolean recovered) { ready++; this.recovered = recovered; }
        public void error(String code) { errors++; this.code = code; }
    }
    private static final class Host implements ScreenPreparation.Platform {
        record Scheduled(long at, Runnable callback) { }
        ScreenPreparation.State screen = ScreenPreparation.State.SCREEN_OFF;
        long time;
        int cleared, launches;
        String token;
        boolean windowGone = true;
        final List<String> closed = new ArrayList<>();
        final List<Scheduled> queue = new ArrayList<>();
        public ScreenPreparation.State state() { return screen; }
        public long now() { return time; }
        public void later(Runnable callback, long delayMillis) { queue.add(new Scheduled(time + delayMillis, callback)); }
        public void remove(Runnable callback) { queue.removeIf(item -> item.callback == callback); }
        public void launch(String token) { this.token = token; launches++; }
        public void close(String token) { closed.add(token); }
        public boolean recoveryWindowGone(String token) { return windowGone; }
        public void clearObservations() { cleared++; }
        void advance(long millis) {
            long end = time + millis;
            while (true) {
                queue.sort(Comparator.comparingLong(Scheduled::at));
                if (queue.isEmpty() || queue.get(0).at > end) break;
                Scheduled next = queue.remove(0); time = next.at; next.callback.run();
            }
            time = end;
        }
    }

    private static void classifications() {
        check(ScreenPreparation.State.of(true, false, true, true) == ScreenPreparation.State.AUTHENTICATION_REQUIRED, "authentication wins over display state");
        check(ScreenPreparation.State.of(true, false, true, false) == ScreenPreparation.State.SCREEN_OFF, "sleep differs from authentication");
        check(ScreenPreparation.State.of(true, true, true, false) == ScreenPreparation.State.DISMISSIBLE_KEYGUARD, "trusted/swipe keyguard may be dismissed");
        check(ScreenPreparation.State.of(true, true, false, false) == ScreenPreparation.State.READY, "ready");
        check(ScreenPreparation.State.of(false, true, false, false) == ScreenPreparation.State.UNAVAILABLE, "unknown is not ready");
        scenarios++;
    }
    private static void readyDoesNotLaunchOrClear() {
        Host host = new Host(); host.screen = ScreenPreparation.State.READY;
        Reply reply = new Reply(); new ScreenPreparation(host).prepare(() -> true, reply);
        check(reply.ready == 1 && !reply.recovered && host.launches == 0 && host.cleared == 0, "ordinary commands preserve current observations");
        scenarios++;
    }
    private static void authenticationDoesNotLaunch() {
        Host host = new Host(); host.screen = ScreenPreparation.State.AUTHENTICATION_REQUIRED;
        Reply reply = new Reply(); new ScreenPreparation(host).prepare(() -> true, reply);
        check(reply.errors == 1 && "device_authentication_required".equals(reply.code) && host.launches == 0 && host.cleared == 1, "no authentication UI or wake is attempted");
        scenarios++;
    }
    private static void waitsForActivityAndWindowToDisappear() {
        Host host = new Host(); host.windowGone = false;
        ScreenPreparation preparation = new ScreenPreparation(host); Reply reply = new Reply();
        preparation.prepare(() -> true, reply);
        check(preparation.permit(host.token), "initial activity allowed");
        host.screen = ScreenPreparation.State.READY; host.advance(1000);
        check(reply.ready == 0 && host.closed.contains(host.token), "do not execute through our temporary overlay");
        check(!preparation.permit(host.token), "closing activity cannot request another wake");
        host.windowGone = true; host.advance(100);
        check(reply.ready == 1 && reply.recovered && host.cleared == 2 && host.queue.isEmpty(), "finish once after window removal and clear old observations");
        scenarios++;
    }
    private static void cancelledDelayedActivityAndCallbacksCannotResume() {
        Host host = new Host(); ScreenPreparation preparation = new ScreenPreparation(host); Reply reply = new Reply();
        preparation.prepare(() -> true, reply); String old = host.token;
        Runnable queued = host.queue.get(0).callback;
        preparation.cancel(); host.screen = ScreenPreparation.State.READY;
        check(!preparation.permit(old), "late activity must not set wake flags");
        queued.run(); preparation.failed(old, "device_wake_failed"); host.advance(10000);
        check(reply.ready == 0 && reply.errors == 0 && host.closed.contains(old) && host.queue.isEmpty(), "cancel invalidates every delayed completion");
        scenarios++;
    }
    private static void invalidatedCommandDoesNotNeedAnotherPollToFenceActivity() {
        Host host = new Host(); ScreenPreparation preparation = new ScreenPreparation(host); Reply reply = new Reply();
        boolean[] valid = {true}; preparation.prepare(() -> valid[0], reply); valid[0] = false;
        check(!preparation.permit(host.token), "stop/disconnect fences a not-yet-started activity immediately");
        check(host.closed.contains(host.token) && host.queue.isEmpty() && reply.ready == 0 && reply.errors == 0, "no stale outcome is sent");
        scenarios++;
    }
    private static void oldFailureAndTimerCannotCompleteNextCommand() {
        Host host = new Host(); ScreenPreparation preparation = new ScreenPreparation(host);
        Reply oldReply = new Reply(), newReply = new Reply();
        preparation.prepare(() -> true, oldReply); String old = host.token; Runnable oldTick = host.queue.get(0).callback;
        preparation.cancel(); preparation.prepare(() -> true, newReply); String current = host.token;
        check(!old.equals(current), "tokens are unique across command lifetimes");
        oldTick.run(); preparation.failed(old, "device_keyguard_dismiss_failed");
        check(preparation.permit(current) && newReply.errors == 0 && newReply.ready == 0, "old callback cannot release or fail new busy command");
        host.screen = ScreenPreparation.State.READY; host.advance(500);
        check(oldReply.ready == 0 && oldReply.errors == 0 && newReply.ready == 1, "only the current command resumes");
        scenarios++;
    }
    private static void finiteTimeoutAndLateActivity() {
        Host host = new Host(); ScreenPreparation preparation = new ScreenPreparation(host); Reply reply = new Reply();
        preparation.prepare(() -> true, reply); String token = host.token; host.advance(ScreenPreparation.TIMEOUT_MS);
        check(reply.errors == 1 && "device_wake_failed".equals(reply.code) && host.queue.isEmpty(), "wake failure finishes inside relay timeout");
        check(!preparation.permit(token), "an activity launched after timeout cannot wake");
        host.screen = ScreenPreparation.State.READY; host.advance(10000);
        check(reply.ready == 0 && reply.errors == 1, "timeout cannot later execute input");
        scenarios++;
    }
    private static void expiredPermitEvenIfTimerWasDelayed() {
        Host host = new Host(); ScreenPreparation preparation = new ScreenPreparation(host); Reply reply = new Reply();
        preparation.prepare(() -> true, reply); host.time = ScreenPreparation.TIMEOUT_MS + 1;
        check(!preparation.permit(host.token) && reply.errors == 1 && host.queue.isEmpty(), "deadline is checked before APIs, not only by handler scheduling");
        scenarios++;
    }
    private static void authenticationTransitionCancelsRecovery() {
        Host host = new Host(); ScreenPreparation preparation = new ScreenPreparation(host); Reply reply = new Reply();
        preparation.prepare(() -> true, reply); host.screen = ScreenPreparation.State.AUTHENTICATION_REQUIRED; host.advance(100);
        check(reply.errors == 1 && "device_authentication_required".equals(reply.code) && host.queue.isEmpty(), "secure transition during recovery requires system authentication");
        check(!preparation.permit(host.token), "no further dismiss after secure transition");
        scenarios++;
    }
    private static void keyguardTimeoutIsDistinct() {
        Host host = new Host(); host.screen = ScreenPreparation.State.DISMISSIBLE_KEYGUARD;
        ScreenPreparation preparation = new ScreenPreparation(host); Reply reply = new Reply();
        preparation.prepare(() -> true, reply); host.advance(ScreenPreparation.TIMEOUT_MS);
        check("device_keyguard_dismiss_failed".equals(reply.code), "visible unresolved swipe lock is not mislabeled authentication");
        scenarios++;
    }
    private static void unavailableAndInvalidCommandsDoNotWake() {
        Host host = new Host(); host.screen = ScreenPreparation.State.UNAVAILABLE;
        ScreenPreparation preparation = new ScreenPreparation(host); Reply reply = new Reply();
        preparation.prepare(() -> false, reply);
        check(reply.errors == 0 && host.launches == 0, "revoked command is ignored");
        preparation.prepare(() -> true, reply);
        check("device_state_unavailable".equals(reply.code) && host.launches == 0, "missing system state is reported accurately");
        scenarios++;
    }
    public static void main(String[] args) {
        classifications(); readyDoesNotLaunchOrClear(); authenticationDoesNotLaunch();
        waitsForActivityAndWindowToDisappear(); cancelledDelayedActivityAndCallbacksCannotResume();
        invalidatedCommandDoesNotNeedAnotherPollToFenceActivity(); oldFailureAndTimerCannotCompleteNextCommand();
        finiteTimeoutAndLateActivity(); expiredPermitEvenIfTimerWasDelayed(); authenticationTransitionCancelsRecovery();
        keyguardTimeoutIsDistinct(); unavailableAndInvalidCommandsDoNotWake();
        System.out.println("ScreenPreparation: " + scenarios + " scenarios passed");
    }
}
