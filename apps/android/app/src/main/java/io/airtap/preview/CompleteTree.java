package io.airtap.preview;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Complete bounded traversal, independent of the much smaller public node list. */
final class CompleteTree {
    static final int MAX_NODES = 2048;
    static final int MAX_BYTES = 1024 * 1024;
    static final class Incomplete extends RuntimeException { }

    static class Description {
        final byte[] attributes;
        final int children;
        Description(byte[] attributes, int children) {
            this.attributes = attributes;
            this.children = children;
        }
    }
    interface Source<N, D extends Description> {
        D describe(N node);
        N child(N node, int index);
    }
    static final class Entry<N, D extends Description> {
        final N node;
        final D description;
        Entry(N node, D description) { this.node = node; this.description = description; }
    }
    static final class Capture<N, D extends Description> {
        final byte[] fingerprint;
        final List<Entry<N, D>> entries;
        Capture(byte[] fingerprint, List<Entry<N, D>> entries) {
            this.fingerprint = fingerprint;
            this.entries = entries;
        }
    }
    private static final class Pending<N> {
        final N node;
        final int parent;
        final int childIndex;
        Pending(N node, int parent, int childIndex) { this.node = node; this.parent = parent; this.childIndex = childIndex; }
    }

    static <N, D extends Description> Capture<N, D> capture(N root, Source<N, D> source, BooleanSupplier unchanged) {
        if (root == null) throw new Incomplete();
        MessageDigest digest = sha256();
        ArrayDeque<Pending<N>> queue = new ArrayDeque<>();
        List<Entry<N, D>> entries = new ArrayList<>();
        queue.add(new Pending<>(root, -1, -1));
        int bytes = 0;
        while (!queue.isEmpty()) {
            if (!unchanged.getAsBoolean() || entries.size() >= MAX_NODES) throw new Incomplete();
            Pending<N> current = queue.removeFirst();
            D description = source.describe(current.node);
            if (description == null || description.attributes == null || description.children < 0
                    || description.children > MAX_NODES - entries.size() - queue.size() - 1) throw new Incomplete();
            bytes += description.attributes.length;
            if (bytes > MAX_BYTES) throw new Incomplete();
            // Include topology and child order. Length framing also prevents concatenation ambiguity.
            digest.update(new Values().integer(current.parent).integer(current.childIndex)
                    .integer(description.children).integer(description.attributes.length).bytes());
            digest.update(description.attributes);
            int index = entries.size();
            entries.add(new Entry<>(current.node, description));
            for (int i = 0; i < description.children; i++) {
                N child = source.child(current.node, i);
                if (child == null) throw new Incomplete();
                queue.addLast(new Pending<>(child, index, i));
            }
        }
        if (!unchanged.getAsBoolean()) throw new Incomplete();
        return new Capture<>(digest.digest(), entries);
    }

    static boolean same(byte[] left, byte[] right) { return left != null && right != null && Arrays.equals(left, right); }

    /** Exact, nullable, length-prefixed UTF-16 values; never clipped public text. */
    static final class Values {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final DataOutputStream out = new DataOutputStream(buffer);
        Values integer(int value) {
            try { out.writeInt(value); return this; }
            catch (IOException impossible) { throw new AssertionError(impossible); }
        }
        Values bool(boolean value) { return integer(value ? 1 : 0); }
        Values number(float value) { return integer(Float.floatToIntBits(value)); }
        Values text(CharSequence value) {
            if (value == null) return integer(-1);
            String text = value.toString();
            if (text.length() > 65536 || buffer.size() + text.length() * 2L + 4 > MAX_BYTES) throw new Incomplete();
            integer(text.length());
            try { for (int i = 0; i < text.length(); i++) out.writeChar(text.charAt(i)); }
            catch (IOException impossible) { throw new AssertionError(impossible); }
            return this;
        }
        byte[] bytes() {
            if (buffer.size() > MAX_BYTES) throw new Incomplete();
            return buffer.toByteArray();
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
