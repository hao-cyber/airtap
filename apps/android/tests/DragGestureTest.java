package io.airtap.preview;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;

/** Executes the production planner/executor against an explicit pointer-contract fake. */
public final class DragGestureTest {
    private static int tests;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static JSONObject point(double x, double y, int duration) { return new JSONObject().put("x", x).put("y", y).put("duration_ms", duration); }
    private static JSONObject request() { return new JSONObject().put("x", 0.2).put("y", 0.3).put("hold_ms", 700)
            .put("path", new JSONArray().put(point(0.98, 0.5, 750)).put(point(0.98, 0.5, 1000)).put(point(0.6, 0.5, 650))); }
    private static final class Result implements TouchGesture.Reply {
        int completions, errors; String code;
        public void completed() { completions++; }
        public void error(String code) { errors++; this.code = code; }
    }
    private static final class Run {
        final AccessibilityService phone = new AccessibilityService(); final Result result = new Result();
        boolean permission = true;
        final TouchGesture drag;
        Run() throws Exception { Handler.reset(); drag = new TouchGesture(phone, GesturePlan.fromDrag(DragPlan.parse(request())), new Rect(0, 0, 1000, 2000),
                () -> { if (!permission) throw new IllegalStateException("revoked"); }, result); }
        void step() { if (phone.pending != null) phone.complete(); else Handler.next(); }
        void drain() { int remaining = 200; while ((phone.pending != null || Handler.pending()) && remaining-- > 0) step(); check(remaining > 0, "unbounded dispatch"); }
    }
    private static void passed(String name) { tests++; System.out.println("PASS " + name); }
    private static void invalid(JSONObject request) throws Exception {
        try { DragPlan.parse(request); throw new AssertionError("invalid plan accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) throws Exception {
        {
            Run run = new Run(); run.drag.start(); run.drain();
            check(run.result.completions == 1 && run.result.errors == 0, "completion exactly once");
            check(run.phone.downs == 1 && run.phone.ups == 1 && !run.phone.down, "one continuous finger");
            for (var segment : run.phone.segments) {
                var stroke = segment.stroke();
                check(stroke.duration <= 100, "bounded movement chunk");
                if (segment.at() < 700) {
                    check(stroke.path.startX == 200 && stroke.path.endX == 200 && stroke.path.startY == 600 && stroke.path.endY == 600, "stationary initial hold");
                }
                if (segment.at() >= 1450 && segment.at() < 2450) {
                    check(stroke.path.startX == 980 && stroke.path.endX == 980 && stroke.path.startY == 1000 && stroke.path.endY == 1000, "edge dwell remains still");
                }
            }
            check(run.phone.events.get(0).at() == 0 && run.phone.events.get(1).at() == 3100, "hold, dwell and duration preserved despite early callbacks");
            check(run.phone.segments.stream().noneMatch(s -> s.at() > 0 && s.at() < 700), "stationary continuation must not be dispatched");
            check(run.phone.x == 600 && run.phone.y == 1000, "final waypoint exact");
            passed("hold, move, edge dwell and subsequent drop share one DOWN and one UP");
        }
        {
            Run run = new Run(); run.permission = false; run.drag.start();
            check(run.phone.history.isEmpty() && run.result.errors == 1, "no unauthorized DOWN");
            passed("authorization failure before first stroke sends no touch");
        }
        for (int time : new int[]{0, 800, 1900}) {
            Run run = new Run(); run.drag.start();
            while (SystemClock.now < time) run.step();
            int before = run.phone.history.size(); run.drag.cancel(); run.drain();
            check(run.phone.history.size() == before + 1, "only release after pending segment");
            var release = run.phone.history.get(run.phone.history.size() - 1);
            check(release.duration == 1 && release.path.startX == release.path.endX && release.path.startY == release.path.endY, "stationary release");
            check(run.phone.downs == 1 && run.phone.ups == 1 && run.result.completions == 0 && run.result.errors == 1, "cancelled continuous pointer cleaned up");
            passed("stop at " + time + "ms prevents all future movement and releases same pointer");
        }
        {
            Run run = new Run(); run.drag.start(); run.phone.complete(); run.permission = false; run.drain();
            check(run.phone.downs == 1 && run.phone.ups == 1 && run.result.completions == 0 && run.result.errors == 1, "permission loss releases");
            passed("permission/window gate failure during hold cleans up without a replacement touch");
        }
        {
            Run run = new Run(); run.drag.start(); run.phone.complete(); int before = run.phone.history.size();
            run.phone.cancelByUser(); run.drag.cancel(); run.drain();
            check(run.phone.downs == 1 && !run.phone.down && run.result.errors == 1 && run.result.completions == 0, "no replacement DOWN after user cancellation");
            passed("real user cancellation never retries or injects a replacement DOWN");
        }
        {
            Run run = new Run(); run.phone.rejectAt = 1; run.drag.start();
            check(run.phone.downs == 0 && "gesture_rejected".equals(run.result.code), "first rejection known");
            passed("first dispatch rejection reports no gesture admission");
        }
        {
            Run run = new Run(); run.phone.rejectAt = 3; run.drag.start(); run.drain();
            check(run.phone.downs == 1 && run.phone.ups == 1 && run.result.errors == 1 && run.result.completions == 0, "rejected continuation cleanup");
            passed("continuation rejection releases prior held stroke and reports partial outcome");
        }
        {
            Run run = new Run(); run.drag.start(); run.drag.cancel(); run.phone.rejectAt = 2; run.drain();
            check("gesture_release_failed_outcome_unknown".equals(run.result.code) && run.result.completions == 0, "release failure not hidden");
            passed("platform rejection of release is explicitly unknown, never success");
        }
        {
            Run run = new Run(); run.drag.cancel(); run.drag.start();
            check(run.phone.history.isEmpty() && run.result.errors == 1, "pre-start stop");
            passed("stop before start is terminal");
        }
        {
            invalid(request().put("hold_ms", 99)); invalid(request().put("hold_ms", 3001)); invalid(request().put("hold_ms", 700.5));
            check(DragPlan.parse(request().put("hold_ms", 700.0)).holdMs == 700, "JSON integer-valued number agrees with relay schema");
            invalid(request().put("x", "0.2")); invalid(request().put("x", Double.NaN)); invalid(request().put("y", 1));
            invalid(request().put("path", new JSONArray()));
            invalid(request().put("path", new JSONArray().put(point(0.5, 0.5, 0))));
            invalid(request().put("path", new JSONArray().put(point(0.5, 0.5, 5001))));
            invalid(request().put("path", new JSONArray().put(point(0.5, 0.5, 100).put("extra", 1))));
            invalid(request().put("path", new JSONArray().put(point(0.5, 0.5, 100).put("duration_ms", true))));
            JSONArray tooMany = new JSONArray(); for (int i = 0; i < 17; i++) tooMany.put(point(0.5, 0.5, 1)); invalid(request().put("path", tooMany));
            invalid(request().put("path", new JSONArray().put(point(0.5, 0.5, 5000)).put(point(0.5, 0.5, 4301))));
            DragPlan limit = DragPlan.parse(request().put("path", new JSONArray().put(point(0.5, 0.5, 5000)).put(point(0.5, 0.5, 4300))));
            check(GesturePlan.fromDrag(limit).frames().stream().mapToInt(GesturePlan.Frame::durationMs).sum() == 10000, "exact duration boundary");
            passed("strict coordinates, integer timings, exact waypoint shape, path length and total timeout budget");
        }
        System.out.println("TOTAL " + tests);
    }
}
