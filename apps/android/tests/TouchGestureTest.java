package io.airtap.preview;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Rect;
import android.os.Handler;
import org.json.JSONArray;
import org.json.JSONObject;

/** Timing and pointer lifetimes, including staggered multi-touch and true double-tap. */
public final class TouchGestureTest {
    private static int tests;
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static JSONObject point(double x, double y, int ms) { return new JSONObject().put("x", x).put("y", y).put("duration_ms", ms); }
    private static JSONObject track(int start, double x, double y, JSONObject... points) {
        JSONArray path = new JSONArray(); for (JSONObject point : points) path.put(point);
        return new JSONObject().put("start_ms", start).put("x", x).put("y", y).put("path", path);
    }
    private static JSONObject request(JSONObject... tracks) { JSONArray a = new JSONArray(); for (JSONObject track : tracks) a.put(track); return new JSONObject().put("tracks", a); }
    private static JSONObject pinch() { return request(track(0, .3, .5, point(.1, .5, 700)), track(0, .7, .5, point(.9, .5, 700))); }
    private static JSONObject doubleTap() { return request(track(0, .5, .5, point(.5, .5, 60)), track(160, .5, .5, point(.5, .5, 60))); }
    private static final class Run implements TouchGesture.Reply {
        final AccessibilityService phone = new AccessibilityService(); final TouchGesture touch;
        boolean allowed = true; int success, errors; String code;
        Run(JSONObject request) throws Exception {
            this(GesturePlan.parse(request, GestureDescription.getMaxStrokeCount()), new Rect(0, 0, 1000, 2000));
        }
        Run(GesturePlan plan, Rect bounds) {
            Handler.reset(); touch = new TouchGesture(phone, plan, bounds,
                    () -> { if (!allowed) throw new IllegalStateException("blocked"); }, this);
        }
        public void completed() { success++; }
        public void error(String code) { errors++; this.code = code; }
        void drain() {
            int remaining = 1000;
            while ((phone.pending != null || Handler.pending()) && remaining-- > 0) {
                if (phone.pending != null) phone.complete(); else Handler.next();
            }
            check(remaining > 0, "bounded timeline");
        }
        void startAndDrain() { touch.start(); drain(); }
    }
    private static void passed(String name) { tests++; System.out.println("PASS " + name); }
    private static void invalid(JSONObject value, int max) throws Exception {
        try { GesturePlan.parse(value, max); throw new AssertionError("invalid plan accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) throws Exception {
        for (int duration : new int[]{60, 700, 5000}) {
            boolean swipe = duration == 5000;
            Run run = new Run(GesturePlan.single(.2, .3, swipe ? .8 : .2, swipe ? .7 : .3, duration), new Rect(0, 0, 1000, 2000));
            run.startAndDrain();
            check(run.success == 1 && run.errors == 0 && run.phone.downs == 1 && run.phone.ups == 1, "one original pointer lifetime");
            check(run.phone.events.get(1).at() == duration, "original duration preserved");
            check(run.phone.x == (swipe ? 800 : 200) && run.phone.y == (swipe ? 1400 : 600), "original endpoint preserved");
            for (var stroke : run.phone.history) check(stroke.duration <= 100, "single touch uses bounded frames");
            passed("legacy " + (duration == 60 ? "tap" : duration == 700 ? "long press" : "swipe") + " uses one uninterrupted DOWN/UP with its original duration");
        }
        for (int duration : new int[]{60, 700, 5000}) {
            Run run = new Run(GesturePlan.single(.99999999, .99999999, .99999999, .99999999, duration), new Rect(10, 20, 1010, 2020));
            run.startAndDrain();
            for (var stroke : run.phone.history) check(stroke.path.startX == 1009 && stroke.path.endX == 1009 && stroke.path.startY == 2019 && stroke.path.endY == 2019, "edge clamp respects origin and final pixel");
            passed("legacy single-touch duration " + duration + " clamps every point to the final valid pixel of offset bounds");
        }
        for (int duration : new int[]{60, 700, 5000}) {
            Run run = new Run(GesturePlan.single(.1, .1, .9, .9, duration), new Rect(0, 0, 1000, 2000));
            run.touch.cancel(); run.touch.start(); run.drain();
            check(run.phone.downs == 0 && run.errors == 1 && run.success == 0, "stop before first contact");
            passed("stop before legacy duration " + duration + " sends no touch");
        }
        for (int duration : new int[]{60, 700, 5000}) for (boolean permissionLoss : new boolean[]{false, true}) {
            Run run = new Run(GesturePlan.single(.1, .1, .9, .9, duration), new Rect(0, 0, 1000, 2000));
            run.touch.start();
            if (permissionLoss) run.allowed = false; else run.touch.cancel();
            run.drain();
            check(run.errors == 1 && run.success == 0 && run.phone.downs == 1 && run.phone.ups == 1 && !run.phone.down, "single contact cleaned up");
            check(run.phone.events.get(1).at() <= 101, "no remaining legacy movement after current bounded frame");
            if (duration > 100) {
                check(run.phone.history.size() == 2, "only current frame and one cleanup");
                var release = run.phone.history.get(1);
                check(release.duration == 1 && release.path.startX == release.path.endX && release.path.startY == release.path.endY && !release.more, "stationary release without another DOWN");
            }
            passed((permissionLoss ? "command/window/permission gate loss" : "stop") + " during legacy duration " + duration + " suppresses future movement and reports unknown outcome");
        }
        {
            Run run = new Run(doubleTap()); run.startAndDrain();
            check(run.success == 1 && run.phone.downs == 2 && run.phone.ups == 2 && run.phone.maxDown == 1, "two sequential fingers");
            long[] times = {0, 60, 160, 220}; String[] kinds = {"DOWN", "UP", "DOWN", "UP"};
            for (int i = 0; i < 4; i++) check(run.phone.events.get(i).at() == times[i] && run.phone.events.get(i).action().equals(kinds[i]), "double-tap gap and lifetime");
            check(run.phone.events.get(0).pointer() != run.phone.events.get(2).pointer(), "double-tap must lift and start anew");
            passed("double-tap has separate DOWN/UP pairs with a real no-contact interval");
        }
        {
            Run run = new Run(pinch()); run.startAndDrain();
            check(run.success == 1 && run.phone.downs == 2 && run.phone.ups == 2 && run.phone.maxDown == 2, "parallel pinch");
            for (var g : run.phone.gestures) check(g.strokes.size() == 2, "both fingers move simultaneously");
            check(run.phone.events.get(2).x() == 100 && run.phone.events.get(3).x() == 900, "pinch endpoints");
            passed("two-finger pinch preserves both continued stroke identities through all frames");
        }
        {
            Run run = new Run(request(track(0, .999999, .999999, point(.999999, .999999, 60)))); run.startAndDrain();
            check(run.success == 1 && run.phone.events.get(0).x() == 999 && run.phone.events.get(0).y() == 1999, "normalized edge must stay inside physical display");
            passed("normalized points near one stay within the final valid display pixel");
        }
        {
            Run run = new Run(request(
                track(0, .2, .5, point(.3, .3, 200), point(.5, .2, 200)),
                track(0, .8, .5, point(.7, .7, 200), point(.5, .8, 200))));
            run.startAndDrain();
            check(run.success == 1 && run.phone.downs == 2 && run.phone.ups == 2, "rotation pointers");
            check(run.phone.events.get(2).y() == 400 && run.phone.events.get(3).y() == 1600, "rotation endpoints");
            passed("piecewise two-finger rotation preserves all waypoints without lifting");
        }
        {
            Run run = new Run(request(track(0, .2, .2, point(.8, .8, 500)),
                    track(150, .4, .4, point(.6, .4, 100)), track(200, .7, .2, point(.7, .6, 250))));
            run.startAndDrain();
            check(run.success == 1 && run.phone.downs == 3 && run.phone.ups == 3 && run.phone.maxDown == 3, "staggered pointer count");
            long[] times = {0, 151, 201, 250, 450, 500};
            for (int i = 0; i < times.length; i++) check(run.phone.events.get(i).at() == times[i], "staggered down/up timing " + i);
            check(run.phone.events.get(1).pointer() == run.phone.events.get(3).pointer(), "second finger lifts first");
            check(run.phone.events.get(0).pointer() == run.phone.events.get(5).pointer(), "first finger remains held until last");
            passed("staggered starts enter 1ms after continued points, and early releases preserve remaining pointer identities");
        }
        {
            JSONArray tracks = new JSONArray();
            for (int i = 0; i < 10; i++) tracks.put(track(0, (i + .5) / 10, .4, point((i + .5) / 10, .7, 200)));
            Run run = new Run(new JSONObject().put("tracks", tracks)); run.startAndDrain();
            check(run.success == 1 && run.phone.maxDown == 10 && run.phone.ups == 10, "ten-track engineering bound");
            check(GesturePlan.trackLimit(3) == 3 && GesturePlan.trackLimit(20) == 10, "runtime Android stroke cap respected");
            invalid(new JSONObject().put("tracks", tracks), 9);
            passed("protocol track limit is additionally constrained by actual Android stroke limit");
        }
        for (boolean revoke : new boolean[]{false, true}) {
            Run run = new Run(pinch()); run.touch.start(); run.phone.complete();
            if (revoke) run.allowed = false; else run.touch.cancel();
            int before = run.phone.gestures.size(); run.drain();
            check(run.errors == 1 && run.success == 0 && run.phone.downs == 2 && run.phone.ups == 2 && !run.phone.down, "all held pointers released");
            check(run.phone.gestures.size() == before + 1, "only cleanup after in-flight bounded frame");
            for (var s : run.phone.gestures.get(before).strokes) check(!s.more && s.duration == 1 && s.path.startX == s.path.endX && s.path.startY == s.path.endY, "stationary multi-release");
            passed((revoke ? "permission loss" : "stop") + " releases every held pointer without additional movement");
        }
        {
            Run run = new Run(doubleTap()); run.touch.start(); run.phone.complete();
            check(Handler.pending(), "between taps"); run.touch.cancel(); run.drain();
            check(run.errors == 1 && run.phone.downs == 1 && run.phone.ups == 1 && !Handler.pending(), "second tap suppressed");
            passed("stop during a no-contact gap cancels the pending future touch");
        }
        {
            Run run = new Run(request(track(500, .5, .5, point(.5, .5, 60)))); run.touch.start();
            run.allowed = false; run.drain(); check(run.errors == 1 && run.phone.downs == 0, "no touch after pre-start authorization loss");
            passed("authorization is rechecked after a delayed first touch");
        }
        {
            Run run = new Run(pinch()); run.touch.start(); run.phone.complete(); int before = run.phone.gestures.size();
            run.phone.cancelByUser(); run.drain();
            check(run.errors == 1 && run.success == 0 && !run.phone.down && run.phone.downs == 2, "cancelled pointers never restored");
            for (int i = before; i < run.phone.gestures.size(); i++) for (var s : run.phone.gestures.get(i).strokes)
                check(s.parent != null && !s.more && s.path.startX == s.path.endX && s.path.startY == s.path.endY, "only continued stationary release attempts");
            passed("platform cancellation only attempts continued stationary UP, never a replacement DOWN");
        }
        {
            Run run = new Run(pinch()); run.phone.rejectAt = 3; run.startAndDrain();
            check(run.errors == 1 && run.success == 0 && run.phone.downs == 2 && run.phone.ups == 2, "multi cleanup after rejection");
            passed("rejected multi-pointer continuation cleans up previous tracks and returns unknown outcome");
        }
        {
            Run run = new Run(doubleTap()); run.phone.rejectAt = 2; run.startAndDrain();
            check(run.phone.downs == 1 && run.phone.ups == 1 && "gesture_interrupted_outcome_unknown".equals(run.code), "completed first tap must remain a partial outcome");
            passed("rejected second tap does not mislabel a partially completed gesture as never admitted");
        }
        {
            invalid(request(), 20);
            invalid(request(track(-1, .5, .5, point(.5, .5, 100))), 20);
            invalid(request(track(9950, .5, .5, point(.5, .5, 100))), 20);
            invalid(request(track(0, .5, .5, point(.5, .5, 0))), 20);
            invalid(request(track(0, .5, .5, point(.5, .5, 100)).put("start_ms", true)), 20);
            invalid(request(track(0, .5, .5, point(.5, .5, 100)).put("x", Double.POSITIVE_INFINITY)), 20);
            invalid(request(track(0, .5, .5, point(.5, .5, 100).put("pressure", 1))), 20);
            JSONArray many = new JSONArray(); for (int i = 0; i < 33; i++) many.put(point(.5, .5, 1));
            invalid(request(track(0, .5, .5).put("path", many)), 20);
            GesturePlan boundary = GesturePlan.parse(request(track(9999, .5, .5, point(.5, .5, 1))), 20);
            check(boundary.frames().stream().mapToInt(GesturePlan.Frame::durationMs).sum() == 10000, "full timeline budget");
            passed("strict multi-touch shape and finite coordinates, integer timings and complete timeline budget");
        }
        System.out.println("TOTAL " + tests);
    }
}
