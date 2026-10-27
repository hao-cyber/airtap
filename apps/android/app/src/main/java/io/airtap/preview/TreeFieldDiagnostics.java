package io.airtap.preview;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;

/** Observes already-captured bytes only. Never participates in snapshot validity. */
final class TreeFieldDiagnostics {
    enum Field {
        TOPOLOGY,
        WINDOW_ID, CHILD_COUNT, PACKAGE_NAME, CLASS_NAME, VIEW_ID, UNIQUE_ID,
        TEXT, CONTENT_DESCRIPTION, STATE_DESCRIPTION, HINT_TEXT, ERROR, TOOLTIP_TEXT, PANE_TITLE, CONTAINER_TITLE,
        VISIBLE, ENABLED, CHECKABLE, CHECKED, SELECTED, EDITABLE, CLICKABLE, LONG_CLICKABLE,
        CONTEXT_CLICKABLE, SCROLLABLE, PASSWORD, FOCUSABLE, FOCUSED, ACCESSIBILITY_FOCUSED,
        CONTENT_INVALID, MULTI_LINE, DISMISSABLE, CAN_OPEN_POPUP, HEADING, SCREEN_READER_FOCUSABLE,
        SHOWING_HINT_TEXT, TEXT_ENTRY_KEY, TEXT_SELECTABLE, IMPORTANT_FOR_ACCESSIBILITY, ACCESSIBILITY_DATA_SENSITIVE,
        INPUT_TYPE, TEXT_SELECTION_START, TEXT_SELECTION_END, MAX_TEXT_LENGTH, LIVE_REGION,
        MOVEMENT_GRANULARITIES, DRAWING_ORDER,
        SCREEN_LEFT, SCREEN_TOP, SCREEN_RIGHT, SCREEN_BOTTOM,
        PARENT_LEFT, PARENT_TOP, PARENT_RIGHT, PARENT_BOTTOM,
        WINDOW_LEFT, WINDOW_TOP, WINDOW_RIGHT, WINDOW_BOTTOM,
        ACTION_COUNT, ACTION_ID, ACTION_LABEL,
        RANGE_PRESENT, RANGE_TYPE, RANGE_MIN, RANGE_MAX, RANGE_CURRENT,
        COLLECTION_PRESENT, COLLECTION_ROWS, COLLECTION_COLUMNS, COLLECTION_HIERARCHICAL, COLLECTION_SELECTION_MODE,
        ITEM_PRESENT, ITEM_ROW, ITEM_COLUMN, ITEM_ROW_SPAN, ITEM_COLUMN_SPAN, ITEM_HEADING, ITEM_SELECTED,
        CHECKED_STATE, EXPANDED_STATE, FIELD_REQUIRED, SUPPLEMENTAL_DESCRIPTION,
        UNKNOWN, NONE
    }
    static final int LOGICAL_FIELDS = 82;
    static final int MAX_LOG_CHARS = 3072;
    private static final int HASHED_FIELDS = Field.UNKNOWN.ordinal();

    /** Fixed-size digests only; no node references, raw attributes or application text retained. */
    static final class Summary {
        private final byte[][] digests;
        private Summary(byte[][] digests) { this.digests = digests; }
    }
    private static final Summary UNKNOWN = new Summary(null);

    static <N, D extends CompleteTree.Description> Summary summarize(CompleteTree.Capture<N, D> capture) {
        try {
            if (capture == null || capture.entries == null || capture.entries.isEmpty()
                    || capture.entries.size() > CompleteTree.MAX_NODES) return UNKNOWN;
            MessageDigest[] hashes = new MessageDigest[HASHED_FIELDS];
            for (int i = 0; i < hashes.length; i++) hashes[i] = sha256();
            int bytes = 0, pending = 1, index = 0;
            byte[] header = new byte[8];
            for (CompleteTree.Entry<N, D> entry : capture.entries) {
                if (entry == null || entry.description == null) return UNKNOWN;
                CompleteTree.Description description = entry.description;
                byte[] attributes = description.attributes;
                if (attributes == null || attributes.length > CompleteTree.MAX_BYTES - bytes
                        || description.children < 0 || pending == 0) return UNKNOWN;
                bytes += attributes.length;
                pending += description.children - 1;
                if (pending < 0 || pending > capture.entries.size() - index - 1) return UNKNOWN;
                // BFS child counts and order completely describe the ordered rooted topology.
                writeInt(header, 0, index); writeInt(header, 4, description.children);
                hashes[Field.TOPOLOGY.ordinal()].update(header);
                for (int i = 1; i < hashes.length; i++) {
                    hashes[i].update(header, 0, 4);
                }
                parse(new Cursor(attributes, hashes), description.children);
                index++;
            }
            if (pending != 0) return UNKNOWN;
            byte[][] result = new byte[hashes.length][];
            for (int i = 0; i < result.length; i++) result[i] = hashes[i].digest();
            return new Summary(result);
        } catch (RuntimeException failure) {
            // Malformed/unsupported diagnostic encoding cannot alter the existing policy result.
            return UNKNOWN;
        }
    }

    static EnumSet<Field> difference(Summary left, Summary right) {
        if (left == null || right == null || left.digests == null || right.digests == null) {
            return EnumSet.of(Field.UNKNOWN);
        }
        if (!Arrays.equals(left.digests[0], right.digests[0])) {
            // Node alignment is lost; other category differences would be ambiguous.
            return EnumSet.of(Field.TOPOLOGY);
        }
        EnumSet<Field> result = EnumSet.noneOf(Field.class);
        for (int i = 1; i < HASHED_FIELDS; i++) {
            if (!Arrays.equals(left.digests[i], right.digests[i])) result.add(Field.values()[i]);
        }
        return result.isEmpty() ? EnumSet.of(Field.NONE) : result;
    }

    static String format(SnapshotDiagnostics.Stage stage, EnumSet<Field> fields, long elapsed) {
        String prefix = "snapshot_fields elapsed_ms=" + elapsed + " stage=" + stage.name().toLowerCase(Locale.ROOT)
                + " fields=";
        if (fields == null || fields.isEmpty()) fields = EnumSet.of(Field.UNKNOWN);
        StringBuilder result = new StringBuilder(prefix);
        for (Field field : fields) {
            if (result.length() > prefix.length()) result.append(',');
            result.append(field.name().toLowerCase(Locale.ROOT));
        }
        return result.length() <= MAX_LOG_CHARS ? result.toString() : prefix + "unknown";
    }

    /** Every logical field in ScopedUi.attributes, in its original byte order. No node calls. */
    private static void parse(Cursor c, int children) {
        c.integer(Field.WINDOW_ID);
        if (c.integer(Field.CHILD_COUNT) != children) throw new IllegalArgumentException();
        c.text(Field.PACKAGE_NAME); c.text(Field.CLASS_NAME); c.text(Field.VIEW_ID); c.text(Field.UNIQUE_ID);
        c.text(Field.TEXT); c.text(Field.CONTENT_DESCRIPTION); c.text(Field.STATE_DESCRIPTION); c.text(Field.HINT_TEXT);
        c.text(Field.ERROR); c.text(Field.TOOLTIP_TEXT); c.text(Field.PANE_TITLE); c.text(Field.CONTAINER_TITLE);
        c.bool(Field.VISIBLE); c.bool(Field.ENABLED); c.bool(Field.CHECKABLE); c.bool(Field.CHECKED); c.bool(Field.SELECTED);
        c.bool(Field.EDITABLE); c.bool(Field.CLICKABLE); c.bool(Field.LONG_CLICKABLE); c.bool(Field.CONTEXT_CLICKABLE);
        c.bool(Field.SCROLLABLE); c.bool(Field.PASSWORD); c.bool(Field.FOCUSABLE); c.bool(Field.FOCUSED);
        c.bool(Field.ACCESSIBILITY_FOCUSED); c.bool(Field.CONTENT_INVALID); c.bool(Field.MULTI_LINE);
        c.bool(Field.DISMISSABLE); c.bool(Field.CAN_OPEN_POPUP); c.bool(Field.HEADING); c.bool(Field.SCREEN_READER_FOCUSABLE);
        c.bool(Field.SHOWING_HINT_TEXT); c.bool(Field.TEXT_ENTRY_KEY); c.bool(Field.TEXT_SELECTABLE);
        c.bool(Field.IMPORTANT_FOR_ACCESSIBILITY); c.bool(Field.ACCESSIBILITY_DATA_SENSITIVE);
        c.integer(Field.INPUT_TYPE); c.integer(Field.TEXT_SELECTION_START); c.integer(Field.TEXT_SELECTION_END);
        c.integer(Field.MAX_TEXT_LENGTH); c.integer(Field.LIVE_REGION); c.integer(Field.MOVEMENT_GRANULARITIES);
        c.integer(Field.DRAWING_ORDER);
        c.integer(Field.SCREEN_LEFT); c.integer(Field.SCREEN_TOP); c.integer(Field.SCREEN_RIGHT); c.integer(Field.SCREEN_BOTTOM);
        c.integer(Field.PARENT_LEFT); c.integer(Field.PARENT_TOP); c.integer(Field.PARENT_RIGHT); c.integer(Field.PARENT_BOTTOM);
        c.integer(Field.WINDOW_LEFT); c.integer(Field.WINDOW_TOP); c.integer(Field.WINDOW_RIGHT); c.integer(Field.WINDOW_BOTTOM);
        int actions = c.integer(Field.ACTION_COUNT);
        if (actions < 0 || actions > c.remaining() / 8) throw new IllegalArgumentException();
        for (int i = 0; i < actions; i++) { c.integer(Field.ACTION_ID); c.text(Field.ACTION_LABEL); }
        if (c.bool(Field.RANGE_PRESENT)) {
            c.integer(Field.RANGE_TYPE); c.integer(Field.RANGE_MIN); c.integer(Field.RANGE_MAX); c.integer(Field.RANGE_CURRENT);
        }
        if (c.bool(Field.COLLECTION_PRESENT)) {
            c.integer(Field.COLLECTION_ROWS); c.integer(Field.COLLECTION_COLUMNS);
            c.bool(Field.COLLECTION_HIERARCHICAL); c.integer(Field.COLLECTION_SELECTION_MODE);
        }
        if (c.bool(Field.ITEM_PRESENT)) {
            c.integer(Field.ITEM_ROW); c.integer(Field.ITEM_COLUMN); c.integer(Field.ITEM_ROW_SPAN);
            c.integer(Field.ITEM_COLUMN_SPAN); c.bool(Field.ITEM_HEADING); c.bool(Field.ITEM_SELECTED);
        }
        // SDK 34/35 end here; SDK 36 appends the existing four fields.
        if (c.remaining() != 0) {
            c.integer(Field.CHECKED_STATE); c.integer(Field.EXPANDED_STATE);
            c.bool(Field.FIELD_REQUIRED); c.text(Field.SUPPLEMENTAL_DESCRIPTION);
        }
        if (c.remaining() != 0) throw new IllegalArgumentException();
    }

    private static final class Cursor {
        final byte[] bytes;
        final MessageDigest[] hashes;
        int position;
        final int[] fields = new int[HASHED_FIELDS];
        final byte[] header = new byte[8];
        Cursor(byte[] bytes, MessageDigest[] hashes) { this.bytes = bytes; this.hashes = hashes; }
        int remaining() { return bytes.length - position; }
        int readInt() {
            if (remaining() < 4) throw new IllegalArgumentException();
            int value = (bytes[position] & 255) << 24 | (bytes[position + 1] & 255) << 16
                    | (bytes[position + 2] & 255) << 8 | bytes[position + 3] & 255;
            position += 4;
            return value;
        }
        void record(Field category, int start) {
            MessageDigest hash = hashes[category.ordinal()];
            writeInt(header, 0, fields[category.ordinal()]++); writeInt(header, 4, position - start);
            hash.update(header);
            hash.update(bytes, start, position - start);
        }
        int integer(Field category) {
            int start = position, value = readInt(); record(category, start); return value;
        }
        boolean bool(Field category) {
            int value = integer(category);
            if (value != 0 && value != 1) throw new IllegalArgumentException();
            return value == 1;
        }
        void text(Field category) {
            int start = position, length = readInt();
            if (length < -1 || length > 65536 || length > remaining() / 2) throw new IllegalArgumentException();
            if (length >= 0) position += length * 2;
            record(category, start);
        }
    }

    private static void writeInt(byte[] bytes, int offset, int value) {
        for (int i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> (24 - i * 8));
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(); }
    }
    private TreeFieldDiagnostics() { }
}
