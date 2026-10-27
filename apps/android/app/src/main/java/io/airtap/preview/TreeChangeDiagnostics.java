package io.airtap.preview;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;

/** Observes already-captured bytes only. Never participates in snapshot validity. */
final class TreeChangeDiagnostics {
    enum Category {
        TOPOLOGY, IDENTITY, TEXT, STATE, FOCUS, SELECTION, INPUT, BOUNDS,
        ACTIONS, RANGE, COLLECTION, PLATFORM, UNKNOWN, NONE
    }
    private static final int HASHED_CATEGORIES = Category.UNKNOWN.ordinal();

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
            MessageDigest[] hashes = new MessageDigest[HASHED_CATEGORIES];
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
                hashes[Category.TOPOLOGY.ordinal()].update(header);
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

    static EnumSet<Category> difference(Summary left, Summary right) {
        if (left == null || right == null || left.digests == null || right.digests == null) {
            return EnumSet.of(Category.UNKNOWN);
        }
        if (!Arrays.equals(left.digests[0], right.digests[0])) {
            // Node alignment is lost; other category differences would be ambiguous.
            return EnumSet.of(Category.TOPOLOGY);
        }
        EnumSet<Category> result = EnumSet.noneOf(Category.class);
        for (int i = 1; i < HASHED_CATEGORIES; i++) {
            if (!Arrays.equals(left.digests[i], right.digests[i])) result.add(Category.values()[i]);
        }
        return result.isEmpty() ? EnumSet.of(Category.NONE) : result;
    }

    static String format(SnapshotDiagnostics.Stage stage, Category category, long elapsed) {
        return "snapshot_change elapsed_ms=" + elapsed + " stage=" + stage.name().toLowerCase(Locale.ROOT)
                + " category=" + category.name().toLowerCase(Locale.ROOT);
    }

    /** Matches ScopedUi.attributes' existing encoding, without converting any text to String. */
    private static void parse(Cursor c, int children) {
        c.integer(Category.IDENTITY); // window
        if (c.integer(Category.TOPOLOGY) != children) throw new IllegalArgumentException();
        for (int i = 0; i < 4; i++) c.text(Category.IDENTITY); // package/class/view/unique ID
        for (int i = 0; i < 8; i++) c.text(Category.TEXT);
        for (int i = 0; i < 11; i++) c.bool(Category.STATE);
        for (int i = 0; i < 3; i++) c.bool(Category.FOCUS);
        for (int i = 0; i < 11; i++) c.bool(Category.STATE);
        c.integer(Category.INPUT);
        c.integer(Category.SELECTION); c.integer(Category.SELECTION);
        for (int i = 0; i < 4; i++) c.integer(Category.INPUT);
        for (int i = 0; i < 12; i++) c.integer(Category.BOUNDS);
        int actions = c.integer(Category.ACTIONS);
        if (actions < 0 || actions > c.remaining() / 8) throw new IllegalArgumentException();
        for (int i = 0; i < actions; i++) { c.integer(Category.ACTIONS); c.text(Category.ACTIONS); }
        if (c.bool(Category.RANGE)) for (int i = 0; i < 4; i++) c.integer(Category.RANGE);
        if (c.bool(Category.COLLECTION)) {
            c.integer(Category.COLLECTION); c.integer(Category.COLLECTION);
            c.bool(Category.COLLECTION); c.integer(Category.COLLECTION);
        }
        if (c.bool(Category.COLLECTION)) {
            for (int i = 0; i < 4; i++) c.integer(Category.COLLECTION);
            c.bool(Category.COLLECTION); c.bool(Category.COLLECTION);
        }
        // SDK 34/35 end here; SDK 36 appends the existing four fields.
        if (c.remaining() != 0) {
            c.integer(Category.PLATFORM); c.integer(Category.PLATFORM);
            c.bool(Category.PLATFORM); c.text(Category.PLATFORM);
        }
        if (c.remaining() != 0) throw new IllegalArgumentException();
    }

    private static final class Cursor {
        final byte[] bytes;
        final MessageDigest[] hashes;
        int position;
        final int[] fields = new int[HASHED_CATEGORIES];
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
        void record(Category category, int start) {
            MessageDigest hash = hashes[category.ordinal()];
            writeInt(header, 0, fields[category.ordinal()]++); writeInt(header, 4, position - start);
            hash.update(header);
            hash.update(bytes, start, position - start);
        }
        int integer(Category category) {
            int start = position, value = readInt(); record(category, start); return value;
        }
        boolean bool(Category category) {
            int value = integer(category);
            if (value != 0 && value != 1) throw new IllegalArgumentException();
            return value == 1;
        }
        void text(Category category) {
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
    private TreeChangeDiagnostics() { }
}
