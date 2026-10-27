package io.airtap.preview;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only documented report fields enter the UI. Never render server JSON or unknown fields. */
final class FeedbackReport {
    static final class Summary {
        final String id, title, status;
        final long createdAt, updatedAt, expiresAt;
        Summary(JSONObject value) throws Exception {
            id = string(value, "report_id", 36, true);
            if (!validId(id)) throw new IllegalArgumentException();
            title = string(value, "title", 160, true);
            status = string(value, "status", 32, true);
            createdAt = time(value, "created_at"); updatedAt = time(value, "updated_at"); expiresAt = time(value, "expires_at");
        }
    }
    static final class Page {
        final List<Summary> reports;
        final String nextCursor;
        Page(JSONObject body) throws Exception {
            version(body);
            JSONArray source = body.getJSONArray("reports");
            if (source.length() > 50) throw new IllegalArgumentException();
            List<Summary> rows = new ArrayList<>();
            for (int i = 0; i < source.length(); i++) rows.add(new Summary(source.getJSONObject(i)));
            reports = Collections.unmodifiableList(rows);
            if (!body.has("next_cursor")) throw new IllegalArgumentException();
            nextCursor = body.isNull("next_cursor") ? null : string(body, "next_cursor", 256, true);
            if (nextCursor != null && !validCursor(nextCursor)) throw new IllegalArgumentException();
        }
    }
    static final class Section {
        final String label, text;
        Section(String label, String text) { this.label = label; this.text = text; }
    }
    static final class Supplement {
        final String text;
        final long createdAt;
        Supplement(JSONObject value) throws Exception { text = string(value, "text", 4000, true); createdAt = time(value, "created_at"); }
    }
    static final class Detail {
        final Summary summary;
        final List<Section> sections;
        final List<Supplement> supplements;
        final String reply;
        Detail(JSONObject body, String expectedId) throws Exception {
            version(body);
            JSONObject source = body.getJSONObject("report");
            summary = new Summary(source);
            if (!summary.id.equals(expectedId)) throw new IllegalArgumentException();
            List<Section> fields = new ArrayList<>();
            fields.add(new Section("问题描述", string(source, "description", 8000, true)));
            add(fields, source, "expected", "期望结果", 4000);
            add(fields, source, "actual", "实际结果", 4000);
            add(fields, source, "steps", "复现步骤", 4000);
            add(fields, source, "phase", "发生阶段", 160);
            add(fields, source, "error_code", "错误代码", 160);
            add(fields, source, "app_version", "App 版本", 160);
            add(fields, source, "agent", "个人 AI", 160);
            add(fields, source, "screen_state", "屏幕状态", 160);
            sections = Collections.unmodifiableList(fields);
            JSONArray additions = source.getJSONArray("supplements");
            if (additions.length() > 5) throw new IllegalArgumentException();
            List<Supplement> more = new ArrayList<>();
            for (int i = 0; i < additions.length(); i++) more.add(new Supplement(additions.getJSONObject(i)));
            supplements = Collections.unmodifiableList(more);
            reply = string(source, "maintenance_reply", 4000, false);
        }
    }

    static boolean deleted(JSONObject body, String id) {
        try {
            version(body);
            return Boolean.TRUE.equals(body.get("ok")) && Boolean.TRUE.equals(body.get("deleted"))
                    && id.equals(string(body, "report_id", 36, true));
        } catch (Exception invalid) { return false; }
    }
    static boolean validId(String value) { return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"); }
    static boolean validCursor(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,256}"); }
    private static void version(JSONObject value) throws Exception {
        if (value == null || integer(value.get("protocol_version")) != 4) throw new IllegalArgumentException();
    }
    private static long time(JSONObject value, String field) throws Exception {
        long timestamp = integer(value.get(field));
        if (timestamp < 0 || timestamp > 253402300799L) throw new IllegalArgumentException();
        return timestamp;
    }
    private static long integer(Object value) {
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException();
        return ((Number) value).longValue();
    }
    private static String string(JSONObject value, String field, int max, boolean required) throws Exception {
        if (value.isNull(field)) { if (required) throw new IllegalArgumentException(); return ""; }
        Object text = value.get(field);
        if (!(text instanceof String)) throw new IllegalArgumentException();
        String plain = (String) text;
        if (plain.codePointCount(0, plain.length()) > max || (required && plain.isEmpty())) throw new IllegalArgumentException();
        return (String) text;
    }
    private static void add(List<Section> fields, JSONObject value, String key, String label, int max) throws Exception {
        String text = string(value, key, max, false);
        if (!text.isEmpty()) fields.add(new Section(label, text));
    }
}
