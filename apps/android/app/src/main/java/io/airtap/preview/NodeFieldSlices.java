package io.airtap.preview;

import java.util.Arrays;
import java.util.EnumSet;
import io.airtap.preview.TreeFieldDiagnostics.Field;

/** Temporary byte slices only. Does not observe Android nodes or retain raw bytes after its caller. */
final class NodeFieldSlices {
    private static final int MAX_SEGMENTS = 4096;
    static EnumSet<Field> difference(byte[] left, int leftChildren, byte[] right, int rightChildren,
                                     NativePairDiagnostics.Budget budget) {
        Cursor a = new Cursor(left, budget), b = new Cursor(right, budget);
        parse(a, leftChildren); parse(b, rightChildren);
        EnumSet<Field> changed = EnumSet.noneOf(Field.class);
        for (int field = 1; field < Field.UNKNOWN.ordinal(); field++) {
            budget.step();
            int x = a.head[field], y = b.head[field];
            while (x != -1 && y != -1) {
                budget.step();
                if (a.lengths[x] != b.lengths[y]) { changed.add(Field.values()[field]); break; }
                boolean equal = true;
                for (int offset = 0; offset < a.lengths[x]; offset += 256) {
                    budget.step();
                    int length = Math.min(256, a.lengths[x] - offset);
                    if (!Arrays.equals(left, a.offsets[x] + offset, a.offsets[x] + offset + length,
                            right, b.offsets[y] + offset, b.offsets[y] + offset + length)) { equal = false; break; }
                }
                if (!equal) { changed.add(Field.values()[field]); break; }
                x = a.next[x]; y = b.next[y];
            }
            if (x != -1 || y != -1) changed.add(Field.values()[field]);
        }
        budget.check();
        return changed;
    }

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
        final NativePairDiagnostics.Budget budget;
        final int[] head = new int[Field.UNKNOWN.ordinal()], tail = new int[Field.UNKNOWN.ordinal()];
        final int[] offsets, lengths, next;
        int position, count;
        Cursor(byte[] bytes, NativePairDiagnostics.Budget budget) {
            if (bytes == null || bytes.length > CompleteTree.MAX_BYTES) throw new IllegalArgumentException();
            this.bytes = bytes; this.budget = budget;
            int capacity = Math.min(MAX_SEGMENTS, bytes.length / 4);
            offsets = new int[capacity]; lengths = new int[capacity]; next = new int[capacity];
            Arrays.fill(head, -1); Arrays.fill(tail, -1); Arrays.fill(next, -1);
        }
        int remaining() { return bytes.length - position; }
        int readInt() {
            if (remaining() < 4) throw new IllegalArgumentException();
            int value = (bytes[position] & 255) << 24 | (bytes[position + 1] & 255) << 16
                    | (bytes[position + 2] & 255) << 8 | bytes[position + 3] & 255;
            position += 4;
            return value;
        }
        void record(Field field, int start) {
            budget.step();
            if (count == offsets.length) throw new NativePairDiagnostics.Limit();
            offsets[count] = start; lengths[count] = position - start;
            int ordinal = field.ordinal();
            if (tail[ordinal] == -1) head[ordinal] = count;
            else next[tail[ordinal]] = count;
            tail[ordinal] = count++;
        }
        int integer(Field field) { int start = position, value = readInt(); record(field, start); return value; }
        boolean bool(Field field) {
            int value = integer(field);
            if (value != 0 && value != 1) throw new IllegalArgumentException();
            return value == 1;
        }
        void text(Field field) {
            int start = position, length = readInt();
            if (length < -1 || length > 65536 || length > remaining() / 2) throw new IllegalArgumentException();
            if (length >= 0) position += length * 2;
            record(field, start);
        }
    }
    private NodeFieldSlices() { }
}
