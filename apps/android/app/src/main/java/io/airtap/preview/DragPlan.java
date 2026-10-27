package io.airtap.preview;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** One pointer, an initial hold, then timed waypoints; repeated points mean dwell. */
final class DragPlan {
    static final int MAX_POINTS = 16, MAX_TOTAL_MS = 10000;
    record Point(double x, double y, int durationMs) {}
    final double x, y;
    final int holdMs;
    final List<Point> path;

    private DragPlan(double x, double y, int holdMs, List<Point> path) {
        this.x = x; this.y = y; this.holdMs = holdMs; this.path = List.copyOf(path);
    }

    static DragPlan parse(JSONObject request) throws Exception {
        double x = coordinate(request, "x"), y = coordinate(request, "y");
        int hold = duration(request, "hold_ms", 100, 3000), total = hold;
        JSONArray values = request.getJSONArray("path");
        if (values.length() < 1 || values.length() > MAX_POINTS) throw invalid();
        List<Point> path = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject point = values.getJSONObject(i);
            if (point.length() != 3 || !point.has("x") || !point.has("y") || !point.has("duration_ms")) throw invalid();
            int duration = duration(point, "duration_ms", 1, 5000);
            total += duration;
            if (total > MAX_TOTAL_MS) throw invalid();
            path.add(new Point(coordinate(point, "x"), coordinate(point, "y"), duration));
        }
        return new DragPlan(x, y, hold, path);
    }

    private static double coordinate(JSONObject value, String key) throws Exception {
        Object raw = value.get(key);
        if (!(raw instanceof Number)) throw invalid();
        double n = ((Number)raw).doubleValue();
        if (!Double.isFinite(n) || n < 0 || n >= 1) throw invalid();
        return n;
    }
    private static int duration(JSONObject value, String key, int min, int max) throws Exception {
        Object raw = value.get(key);
        if (!(raw instanceof Number)) throw invalid();
        double n = ((Number)raw).doubleValue();
        if (!Double.isFinite(n) || n != Math.rint(n) || n < min || n > max) throw invalid();
        return (int)n;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_drag"); }

}
