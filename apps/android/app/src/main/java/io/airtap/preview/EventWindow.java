package io.airtap.preview;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bounded per-grant event history. Cursors never acknowledge or consume another reader's data. */
final class EventWindow {
    static final int MAX_EVENTS = 64, MAX_TEXT = 256;
    static final long RETENTION_MS = 24 * 60 * 60 * 1000L;
    record Entry(long sequence, long at, String kind, String source, String key, String title, String text, boolean truncated) {}
    record Page(String epoch, long next, boolean gap, List<Entry> entries) {}
    final String epoch;
    long sequence;
    final List<Entry> entries = new ArrayList<>();
    EventWindow() { this(UUID.randomUUID().toString().replace("-", ""), 0, List.of()); }
    EventWindow(String epoch, long sequence, List<Entry> entries) {
        if (!epoch.matches("[a-f0-9]{32}") || sequence < 0 || entries.size() > MAX_EVENTS) throw new IllegalArgumentException();
        this.epoch = epoch; this.sequence = sequence;
        long previous = 0;
        for (Entry e : entries) {
            if (e.sequence <= previous || e.sequence > sequence || e.at < 0 || e.title.length() > MAX_TEXT || e.text.length() > MAX_TEXT) throw new IllegalArgumentException();
            previous = e.sequence; this.entries.add(e);
        }
    }
    static String clip(String s) {
        if (s == null) return "";
        int end = Math.min(MAX_TEXT, s.length());
        if (end < s.length() && end > 0 && Character.isHighSurrogate(s.charAt(end-1))) end--;
        return s.substring(0, end);
    }
    void prune(long now) { entries.removeIf(e -> e.at < now - RETENTION_MS); }
    void append(long now, String kind, String source, String key, String title, String text) {
        prune(now);
        String t = clip(title), body = clip(text);
        if(kind.equals("notification"))for(int i=entries.size()-1;i>=0;i--){
            Entry last=entries.get(i);
            if(last.kind.equals(kind)&&last.source.equals(source)&&last.key.equals(key)){
                if(last.title.equals(t)&&last.text.equals(body))return;
                break;
            }
        }
        if (sequence >= 9007199254740991L) throw new IllegalStateException("event_sequence_exhausted");
        entries.add(new Entry(++sequence, now, kind, source, key, t, body,
                title != null && title.length() > t.length() || text != null && text.length() > body.length()));
        if (entries.size() > MAX_EVENTS) entries.remove(0);
    }
    Page read(String cursorEpoch, long after, int limit, long now) {
        if (after < 0 || after > 9007199254740991L || limit < 1 || limit > 16) throw new IllegalArgumentException();
        prune(now);
        boolean same = epoch.equals(cursorEpoch);
        long start = same ? after : 0;
        if (same && after > sequence) throw new IllegalArgumentException("future_event_cursor");
        long first = entries.isEmpty() ? sequence + 1 : entries.get(0).sequence;
        boolean gap = (!cursorEpoch.isEmpty() && !same) || start < first - 1;
        List<Entry> page = entries.stream().filter(e -> e.sequence > start).limit(limit).toList();
        return new Page(epoch, page.isEmpty() ? sequence : page.get(page.size()-1).sequence, gap, page);
    }
}
