package io.airtap.preview;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One serialized Android gesture timeline; each track keeps its own continued stroke. */
final class TouchGesture {
    interface Gate { void check(); }
    interface Reply { void completed(); void error(String code); }
    private record Pointer(GestureDescription.StrokeDescription stroke, float x, float y) {}
    private final AccessibilityService service;
    private final Gate gate;
    private final Reply reply;
    private final Rect bounds;
    private final List<GesturePlan.Frame> frames;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Integer, Pointer> held = new LinkedHashMap<>();
    private final Runnable afterGap = () -> { waiting = false; advance(); };
    private int next;
    private boolean finished, inFlight, waiting, admitted;
    private String cancelled;
    TouchGesture(AccessibilityService service, GesturePlan plan, Rect bounds, Gate gate, Reply reply) {
        this.service = service; this.gate = gate; this.reply = reply; this.bounds = new Rect(bounds); frames = plan.frames();
    }
    void start() { advance(); }
    void cancel() {
        if (finished) return;
        cancelled = "gesture_cancelled_outcome_unknown";
        if (waiting) { handler.removeCallbacks(afterGap); waiting = false; }
        if (!inFlight) stop(cancelled);
    }
    // Android rounds gesture coordinates to pixels; a value just below 1 must
    // not round past the display's last valid pixel when targeting an edge.
    private float px(double value) { return Math.min(bounds.right - 1, Math.round(bounds.left + value * bounds.width())); }
    private float py(double value) { return Math.min(bounds.bottom - 1, Math.round(bounds.top + value * bounds.height())); }
    private void waitUntil(long deadline) {
        if (cancelled != null) { stop(cancelled); return; }
        long remaining = deadline - SystemClock.uptimeMillis();
        if (remaining <= 0) { advance(); return; }
        waiting = true; handler.postDelayed(afterGap, remaining);
    }
    private void advance() {
        if (finished) return;
        if (cancelled != null) { stop(cancelled); return; }
        try { gate.check(); }
        catch (RuntimeException denied) { stop("gesture_interrupted_outcome_unknown"); return; }
        if (next >= frames.size()) { finished = true; reply.completed(); return; }
        GesturePlan.Frame frame = frames.get(next++);
        long started = SystemClock.uptimeMillis();
        if (frame.spans().isEmpty()) {
            // Gaps have real elapsed time, no held pointer and no synthetic touch.
            if (!held.isEmpty()) { stop("gesture_interrupted_outcome_unknown"); return; }
            waitUntil(started + frame.durationMs()); return;
        }
        boolean adding = !held.isEmpty() && frame.spans().stream().anyMatch(span -> !held.containsKey(span.track()));
        int duration = Math.max(frame.durationMs(), adding ? 2 : 1);
        boolean unchanged = frame.spans().size() == held.size();
        for (GesturePlan.Span span : frame.spans()) {
            Pointer previous = held.get(span.track());
            if (previous == null || !span.more() || previous.x != px(span.toX()) || previous.y != py(span.toY())) unchanged = false;
        }
        // AOSP emits no MotionEvent for a stationary continuation without UP. It reports
        // cancellation for that empty event list while leaving the earlier pointer DOWN.
        // Hold the existing stroke locally instead; the next timer tick rechecks the gate.
        if (unchanged) { waitUntil(started + duration); return; }
        Map<Integer, Pointer> strokes = new LinkedHashMap<>();
        try {
            for (GesturePlan.Span span : frame.spans()) {
                float fromX = px(span.fromX()), fromY = py(span.fromY()), toX = px(span.toX()), toY = py(span.toY());
                Pointer previous = held.get(span.track());
                Path path = new Path(); path.moveTo(fromX, fromY);
                if (fromX != toX || fromY != toY) path.lineTo(toX, toY);
                // A continuation's time-zero touch points must contain only previously held
                // fingers. A new finger enters one millisecond later, within the same dispatch.
                int offset = previous == null && adding ? 1 : 0;
                GestureDescription.StrokeDescription stroke = previous == null
                        ? new GestureDescription.StrokeDescription(path, offset, duration - offset, span.more())
                        : previous.stroke.continueStroke(path, 0, duration, span.more());
                strokes.put(span.track(), new Pointer(stroke, toX, toY));
            }
            dispatch(strokes, false, null, started + duration);
        } catch (RuntimeException failure) { stop("gesture_interrupted_outcome_unknown"); }
    }
    private void dispatch(Map<Integer, Pointer> strokes, boolean release, String error, long deadline) {
        GestureDescription.Builder builder = new GestureDescription.Builder();
        for (Pointer pointer : strokes.values()) builder.addStroke(pointer.stroke);
        GestureDescription gesture = builder.build(); inFlight = true;
        boolean accepted;
        try {
            accepted = service.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
                @Override public void onCompleted(GestureDescription ignored) {
                    if (finished) return;
                    inFlight = false; held.clear();
                    for (var entry : strokes.entrySet()) if (entry.getValue().stroke.willContinue()) held.put(entry.getKey(), entry.getValue());
                    // DOWN-only segments can complete at time zero, before their declared
                    // duration. Do not turn that early callback into an early move or lift.
                    if (release) finish(error); else waitUntil(deadline);
                }
                @Override public void onCancelled(GestureDescription ignored) {
                    if (finished) return;
                    inFlight = false;
                    if (release) { finish("gesture_release_failed_outcome_unknown"); return; }
                    // Failure is not proof that all pointers were lifted. Try only continued
                    // stationary UP strokes; never start a replacement DOWN or replay movement.
                    held.clear();
                    for (var entry : strokes.entrySet()) if (entry.getValue().stroke.willContinue()) held.put(entry.getKey(), entry.getValue());
                    stop("gesture_cancelled_outcome_unknown");
                }
            }, null);
        } catch (RuntimeException failure) { accepted = false; }
        if (accepted) admitted = true;
        if (!accepted && !finished) {
            inFlight = false;
            if (release) finish("gesture_release_failed_outcome_unknown");
            else stop(!admitted ? "gesture_rejected" : "gesture_interrupted_outcome_unknown");
        }
    }
    private void stop(String error) {
        if (finished) return;
        if (held.isEmpty()) { finish(error); return; }
        Map<Integer, Pointer> releases = new LinkedHashMap<>();
        try {
            for (var entry : held.entrySet()) {
                Pointer pointer = entry.getValue(); Path path = new Path(); path.moveTo(pointer.x, pointer.y);
                releases.put(entry.getKey(), new Pointer(pointer.stroke.continueStroke(path, 0, 1, false), pointer.x, pointer.y));
            }
            // Only UP at each last endpoint: no new DOWN or movement after stop.
            dispatch(releases, true, error, 0);
        } catch (RuntimeException failure) { finish("gesture_release_failed_outcome_unknown"); }
    }
    private void finish(String error) {
        if (finished) return;
        finished = true; handler.removeCallbacks(afterGap); reply.error(error);
    }
}
