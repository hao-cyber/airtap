package io.airtap.preview;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** Timed touch tracks. Each track owns one DOWN/UP and may overlap other tracks. */
final class GesturePlan {
    static final int MAX_TRACKS = 10, MAX_POINTS = 32, MAX_TOTAL_MS = 10000, CHUNK_MS = 100;
    record Point(double x, double y, int durationMs) {}
    record Track(int startMs, double x, double y, List<Point> path) {
        Track { path = List.copyOf(path); }
        int endMs() { return startMs + path.stream().mapToInt(Point::durationMs).sum(); }
        double[] at(int time) {
            int start = startMs; double fromX = x, fromY = y;
            for (Point point : path) {
                int end = start + point.durationMs;
                if (time <= end) {
                    if (time <= start) return new double[]{fromX, fromY};
                    if (time == end) return new double[]{point.x, point.y};
                    double fraction = (double)(time - start) / point.durationMs;
                    return new double[]{fromX + (point.x - fromX) * fraction, fromY + (point.y - fromY) * fraction};
                }
                start = end; fromX = point.x; fromY = point.y;
            }
            return new double[]{fromX, fromY};
        }
    }
    record Span(int track, double fromX, double fromY, double toX, double toY, boolean more) {}
    record Frame(int durationMs, List<Span> spans) { Frame { spans = List.copyOf(spans); } }
    final List<Track> tracks;
    private GesturePlan(List<Track> tracks) { this.tracks = List.copyOf(tracks); }
    static int trackLimit(int androidStrokeLimit) { return Math.min(MAX_TRACKS, androidStrokeLimit); }
    static GesturePlan parse(JSONObject request, int androidStrokeLimit) throws Exception {
        JSONArray source = request.getJSONArray("tracks");
        if (source.length() < 1 || source.length() > trackLimit(androidStrokeLimit)) throw invalid();
        List<Track> tracks = new ArrayList<>();
        for (int i = 0; i < source.length(); i++) {
            JSONObject value = source.getJSONObject(i);
            if (!exact(value, "start_ms", "x", "y", "path")) throw invalid();
            int start = integer(value, "start_ms", 0, MAX_TOTAL_MS - 1), end = start;
            double x = coordinate(value, "x"), y = coordinate(value, "y");
            JSONArray path = value.getJSONArray("path");
            if (path.length() < 1 || path.length() > MAX_POINTS) throw invalid();
            List<Point> points = new ArrayList<>();
            for (int j = 0; j < path.length(); j++) {
                JSONObject point = path.getJSONObject(j);
                if (!exact(point, "x", "y", "duration_ms")) throw invalid();
                int duration = integer(point, "duration_ms", 1, 5000); end += duration;
                if (end > MAX_TOTAL_MS) throw invalid();
                points.add(new Point(coordinate(point, "x"), coordinate(point, "y"), duration));
            }
            tracks.add(new Track(start, x, y, points));
        }
        return new GesturePlan(tracks);
    }
    static GesturePlan fromDrag(DragPlan drag) {
        List<Point> points = new ArrayList<>(); points.add(new Point(drag.x, drag.y, drag.holdMs));
        for (DragPlan.Point point : drag.path) points.add(new Point(point.x(), point.y(), point.durationMs()));
        return new GesturePlan(List.of(new Track(0, drag.x, drag.y, points)));
    }
    /** Existing tap/long_press/swipe requests use the same cancellable timeline. */
    static GesturePlan single(double x, double y, double endX, double endY, int durationMs) {
        for (double value : new double[]{x, y, endX, endY})
            if (!Double.isFinite(value) || value < 0 || value >= 1) throw invalid();
        if (durationMs < 1 || durationMs > 5000) throw invalid();
        return new GesturePlan(List.of(new Track(0, x, y, List.of(new Point(endX, endY, durationMs)))));
    }
    private static boolean exact(JSONObject value, String... keys) {
        if (value.length() != keys.length) return false;
        for (String key : keys) if (!value.has(key)) return false;
        return true;
    }
    private static int integer(JSONObject value, String key, int min, int max) throws Exception {
        Object raw = value.get(key); if (!(raw instanceof Number)) throw invalid();
        double n = ((Number)raw).doubleValue();
        if (!Double.isFinite(n) || n != Math.rint(n) || n < min || n > max) throw invalid();
        return (int)n;
    }
    private static double coordinate(JSONObject value, String key) throws Exception {
        Object raw = value.get(key); if (!(raw instanceof Number)) throw invalid();
        double n = ((Number)raw).doubleValue();
        if (!Double.isFinite(n) || n < 0 || n >= 1) throw invalid(); return n;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_gesture"); }
    List<Frame> frames() {
        // Split at every down, up, waypoint and <=100ms interval. A continuing
        // track is present in every frame until its own UP; other tracks can
        // enter or leave independently without breaking that pointer's identity.
        TreeSet<Integer> events = new TreeSet<>(); events.add(0);
        for (Track track : tracks) {
            int time = track.startMs; events.add(time);
            for (Point point : track.path) { time += point.durationMs; events.add(time); }
        }
        List<Integer> boundaries = new ArrayList<>(events); List<Frame> frames = new ArrayList<>();
        for (int i = 1; i < boundaries.size(); i++) {
            int from = boundaries.get(i - 1), target = boundaries.get(i);
            while (from < target) {
                int to = Math.min(target, from + CHUNK_MS); List<Span> spans = new ArrayList<>();
                for (int j = 0; j < tracks.size(); j++) {
                    Track track = tracks.get(j);
                    if (track.startMs > from || track.endMs() <= from) continue;
                    double[] start = track.at(from), end = track.at(to);
                    spans.add(new Span(j, start[0], start[1], end[0], end[1], track.endMs() > to));
                }
                frames.add(new Frame(to - from, spans)); from = to;
            }
        }
        return frames;
    }
}
