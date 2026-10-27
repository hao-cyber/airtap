package io.airtap.preview;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import io.airtap.preview.TreeFieldDiagnostics.Field;

/** Failure-only observations of one already captured pair; never a permission or freshness check. */
final class NativePairDiagnostics {
    enum Status { COMPLETE, AMBIGUOUS, UNKNOWN }
    enum Reason { NONE, IDENTITY, PARENT, TARGET, REPEAT_UNSTABLE, BUDGET, MALFORMED, EXCEPTION, LOG_LIMIT, UNEXPLAINED }
    enum Relation { TARGET, ANCESTOR, DESCENDANT, OTHER, UNAVAILABLE }
    enum Order { SAME, CHANGED, UNKNOWN }
    enum Repeat { STABLE, UNSTABLE, NOT_RUN }
    interface Identity<N> { int hash(N node); boolean same(N left, N right); }
    static final int MAX_LOG_CHARS = 3072;
    static final class Limit extends RuntimeException { }
    private static final class Ambiguous extends RuntimeException {
        final Reason reason;
        Ambiguous(Reason reason) { this.reason = reason; }
    }

    /** Cooperative budget, not a hard real-time deadline for a JVM or platform operation. */
    static final class Budget {
        static final long MAX_MS = 200;
        static final int MAX_EQUALS = 32768, MAX_STEPS = 2000000;
        private final LongSupplier clock;
        private final long started;
        private int steps, equals;
        Budget(LongSupplier clock) { this.clock = clock; started = clock.getAsLong(); }
        void check() { long now = clock.getAsLong(); if (now < started || now - started >= MAX_MS) throw new Limit(); }
        void step() { if (++steps > MAX_STEPS) throw new Limit(); if ((steps & 31) == 0) check(); }
        void equality() { if (++equals > MAX_EQUALS) throw new Limit(); step(); }
    }

    /** Only fixed enums/sets escape. No node, array contents, source IDs or hashes in results. */
    static final class Result {
        final Status status;
        final Reason reason;
        final boolean targetAvailable;
        final Order order;
        final Repeat repeat;
        final EnumMap<Relation, EnumSet<Field>> fields;
        private Result(Status status, Reason reason, boolean targetAvailable, Order order, Repeat repeat,
                       EnumMap<Relation, EnumSet<Field>> fields) {
            this.status = status; this.reason = reason; this.targetAvailable = targetAvailable;
            this.order = order; this.repeat = repeat; this.fields = fields;
        }
    }
    private static Result failed(Status status, Reason reason) {
        return new Result(status, reason, false, Order.UNKNOWN,
                reason == Reason.REPEAT_UNSTABLE ? Repeat.UNSTABLE : Repeat.NOT_RUN, new EnumMap<>(Relation.class));
    }

    private static final class Frozen<N> {
        final List<N> nodes;
        final byte[][] attributes;
        final int[] children, parents;
        Frozen(List<N> nodes, byte[][] attributes, int[] children, int[] parents) {
            this.nodes = nodes; this.attributes = attributes; this.children = children; this.parents = parents;
        }
    }
    private static <N, D extends CompleteTree.Description> int payload(CompleteTree.Capture<N, D> capture, Budget budget) {
        budget.check();
        if (capture == null || capture.entries == null || capture.entries.isEmpty()
                || capture.entries.size() > CompleteTree.MAX_NODES) throw new IllegalArgumentException();
        int total = 0;
        for (CompleteTree.Entry<N, D> entry : capture.entries) {
            budget.check();
            if (entry == null || entry.description == null || entry.description.attributes == null) throw new IllegalArgumentException();
            if (entry.description.attributes.length > CompleteTree.MAX_BYTES - total) throw new Limit();
            total += entry.description.attributes.length;
        }
        return total;
    }
    private static <N, D extends CompleteTree.Description> Frozen<N> freeze(CompleteTree.Capture<N, D> capture, Budget budget,
                                                                         int[] remainingPayload) {
        budget.check();
        if (capture == null || capture.entries == null || capture.entries.isEmpty()
                || capture.entries.size() > CompleteTree.MAX_NODES) throw new IllegalArgumentException();
        int size = capture.entries.size(), total = 0, assigned = 1;
        List<N> nodes = new ArrayList<>(size);
        byte[][] attributes = new byte[size][];
        int[] children = new int[size], parents = new int[size];
        Arrays.fill(parents, -2); parents[0] = -1;
        for (int i = 0; i < size; i++) {
            budget.check();
            CompleteTree.Entry<N, D> entry = capture.entries.get(i);
            if (entry == null || entry.node == null) throw new Ambiguous(Reason.IDENTITY);
            if (entry.description == null || entry.description.attributes == null || parents[i] == -2) throw new IllegalArgumentException();
            int count = entry.description.children;
            if (count < 0 || count > size - assigned) throw new IllegalArgumentException();
            byte[] bytes = entry.description.attributes;
            if (bytes.length > CompleteTree.MAX_BYTES - total) throw new IllegalArgumentException();
            total += bytes.length;
            if (bytes.length > remainingPayload[0]) throw new Limit();
            remainingPayload[0] -= bytes.length;
            attributes[i] = bytes.clone(); // Both clones total <=1 MiB; existing captures are additional memory.
            nodes.add(entry.node); children[i] = count;
            for (int child = 0; child < count; child++) parents[assigned++] = i;
        }
        if (assigned != size) throw new IllegalArgumentException();
        budget.check();
        return new Frozen<>(nodes, attributes, children, parents);
    }

    private static final class Index<N> {
        final Frozen<N> tree;
        final int[] hashes;
        final Map<Integer, List<Integer>> buckets = new HashMap<>();
        Index(Frozen<N> tree, Identity<N> identity, Budget budget) {
            this.tree = tree; hashes = new int[tree.nodes.size()];
            for (int i = 0; i < tree.nodes.size(); i++) {
                budget.check();
                N node = tree.nodes.get(i);
                if (!same(node, node, identity, budget)) throw new Ambiguous(Reason.IDENTITY);
                int hash = identity.hash(node); hashes[i] = hash;
                List<Integer> bucket = buckets.computeIfAbsent(hash, ignored -> new ArrayList<>());
                for (int old : bucket) if (same(node, tree.nodes.get(old), identity, budget)) throw new Ambiguous(Reason.IDENTITY);
                bucket.add(i);
            }
        }
        int locate(N node, Identity<N> identity, Budget budget) {
            budget.check();
            if (node == null) return -1;
            List<Integer> bucket = buckets.get(identity.hash(node));
            if (bucket == null) return -1;
            int match = -1;
            for (int index : bucket) if (same(node, tree.nodes.get(index), identity, budget)) {
                if (match != -1) throw new Ambiguous(Reason.IDENTITY);
                match = index;
            }
            return match;
        }
    }
    private static <N> boolean same(N left, N right, Identity<N> identity, Budget budget) {
        budget.equality(); boolean forward = identity.same(left, right);
        budget.equality(); boolean backward = identity.same(right, left);
        if (forward != backward) throw new Ambiguous(Reason.IDENTITY);
        return forward;
    }
    private static final class Observation {
        final Result result;
        final int[] mapping, leftHashes, rightHashes;
        Observation(Result result, int[] mapping, int[] leftHashes, int[] rightHashes) {
            this.result = result; this.mapping = mapping; this.leftHashes = leftHashes; this.rightHashes = rightHashes;
        }
        boolean sameAs(Observation other) {
            return result.order == other.result.order && result.targetAvailable == other.result.targetAvailable
                    && result.fields.equals(other.result.fields) && Arrays.equals(mapping, other.mapping)
                    && Arrays.equals(leftHashes, other.leftHashes) && Arrays.equals(rightHashes, other.rightHashes);
        }
    }

    private static Relation[] relations(int[] parents, int target, Budget budget) {
        Relation[] result = new Relation[parents.length];
        if (target < 0) { Arrays.fill(result, Relation.UNAVAILABLE); return result; }
        result[target] = Relation.TARGET;
        for (int i = parents[target]; i >= 0; i = parents[i]) { budget.step(); result[i] = Relation.ANCESTOR; }
        for (int i = 0; i < result.length; i++) {
            budget.step();
            if (result[i] != null) continue;
            int parent = parents[i];
            result[i] = parent >= 0 && (result[parent] == Relation.TARGET || result[parent] == Relation.DESCENDANT)
                    ? Relation.DESCENDANT : Relation.OTHER;
        }
        return result;
    }

    private static <N> Observation compare(Frozen<N> left, Frozen<N> right, N target, boolean targetRequested,
                                          Identity<N> identity, Budget budget) {
        budget.check();
        if (left.nodes.size() != right.nodes.size()) throw new Ambiguous(Reason.IDENTITY);
        Index<N> a = new Index<>(left, identity, budget), b = new Index<>(right, identity, budget);
        int size = left.nodes.size();
        int[] mapping = new int[size]; boolean[] used = new boolean[size];
        Order order = Order.SAME;
        for (int i = 0; i < size; i++) {
            budget.check();
            int match = b.locate(left.nodes.get(i), identity, budget);
            if (match < 0 || used[match]) throw new Ambiguous(Reason.IDENTITY);
            used[match] = true; mapping[i] = match;
            if (match != i) order = Order.CHANGED;
        }
        for (int i = 0; i < size; i++) {
            budget.step();
            int lp = left.parents[i], rp = right.parents[mapping[i]];
            if (lp == -1 ? rp != -1 : mapping[lp] != rp) throw new Ambiguous(Reason.PARENT);
        }
        int leftTarget = -1, rightTarget = -1;
        if (targetRequested) {
            leftTarget = a.locate(target, identity, budget); rightTarget = b.locate(target, identity, budget);
            if (leftTarget < 0 || rightTarget < 0 || mapping[leftTarget] != rightTarget) throw new Ambiguous(Reason.TARGET);
        }
        Relation[] leftRelations = relations(left.parents, leftTarget, budget), rightRelations = relations(right.parents, rightTarget, budget);
        EnumMap<Relation, EnumSet<Field>> fields = new EnumMap<>(Relation.class);
        for (int i = 0; i < size; i++) {
            budget.check();
            int match = mapping[i];
            Relation relation = leftRelations[i];
            if (relation != rightRelations[match]) throw new Ambiguous(Reason.TARGET);
            EnumSet<Field> changed = NodeFieldSlices.difference(left.attributes[i], left.children[i],
                    right.attributes[match], right.children[match], budget);
            if (!changed.isEmpty()) fields.computeIfAbsent(relation, ignored -> EnumSet.noneOf(Field.class)).addAll(changed);
        }
        budget.check();
        return new Observation(new Result(Status.COMPLETE, Reason.NONE, targetRequested, order, Repeat.STABLE, fields),
                mapping, a.hashes, b.hashes);
    }

    static <N, D extends CompleteTree.Description> Result comparePair(CompleteTree.Capture<N, D> first,
                    CompleteTree.Capture<N, D> repeated, N target, boolean targetRequested, Identity<N> identity, Budget budget) {
        try {
            int leftBytes = payload(first, budget), rightBytes = payload(repeated, budget);
            if (rightBytes > CompleteTree.MAX_BYTES - leftBytes) throw new Limit();
            int[] remainingPayload = {CompleteTree.MAX_BYTES};
            Frozen<N> left = freeze(first, budget, remainingPayload), right = freeze(repeated, budget, remainingPayload);
            Observation a = compare(left, right, target, targetRequested, identity, budget);
            Observation b = compare(left, right, target, targetRequested, identity, budget);
            budget.check();
            return a.sameAs(b) ? a.result : failed(Status.AMBIGUOUS, Reason.REPEAT_UNSTABLE);
        } catch (Limit limit) { return failed(Status.UNKNOWN, Reason.BUDGET); }
        catch (Ambiguous ambiguous) { return failed(Status.AMBIGUOUS, ambiguous.reason); }
        catch (IllegalArgumentException malformed) { return failed(Status.UNKNOWN, Reason.MALFORMED); }
        catch (RuntimeException exception) { return failed(Status.UNKNOWN, Reason.EXCEPTION); }
    }

    static String format(SnapshotDiagnostics.Stage stage, Result result, Budget budget) {
        try {
            budget.check();
            String value = line(stage, result);
            budget.check();
            return value.length() <= MAX_LOG_CHARS ? value : line(stage, failed(Status.UNKNOWN, Reason.LOG_LIMIT));
        } catch (Limit limit) { return line(stage, failed(Status.UNKNOWN, Reason.BUDGET)); }
        catch (RuntimeException exception) { return line(stage, failed(Status.UNKNOWN, Reason.EXCEPTION)); }
    }
    static Result forRejectedPair(Result result) {
        // The caller already observed a full-fingerprint mismatch. Never call it a false alarm.
        return result.status == Status.COMPLETE && result.order == Order.SAME && result.fields.isEmpty()
                ? failed(Status.UNKNOWN, Reason.UNEXPLAINED) : result;
    }
    private static String line(SnapshotDiagnostics.Stage stage, Result result) {
        StringBuilder fields = new StringBuilder();
        for (Map.Entry<Relation, EnumSet<Field>> entry : result.fields.entrySet()) for (Field field : entry.getValue()) {
            if (fields.length() != 0) fields.append(',');
            fields.append(field.name().toLowerCase(Locale.ROOT)).append(':').append(entry.getKey().name().toLowerCase(Locale.ROOT));
        }
        return "snapshot_pair stage=" + stage.name().toLowerCase(Locale.ROOT)
                + " comparison=first_vs_second input_dispatched=false alignment="
                + (result.status == Status.COMPLETE ? "source_identity_unique" : "unavailable")
                + " result=" + result.status.name().toLowerCase(Locale.ROOT) + " reason=" + result.reason.name().toLowerCase(Locale.ROOT)
                + " target=" + (result.targetAvailable ? "available" : "unavailable")
                + " order=" + result.order.name().toLowerCase(Locale.ROOT)
                + " structure=" + (result.status == Status.COMPLETE ? "matched" : result.status == Status.AMBIGUOUS ? "ambiguous" : "unknown")
                + " fields=" + (fields.length() == 0 ? "none" : fields)
                + " repeat=" + result.repeat.name().toLowerCase(Locale.ROOT);
    }
    private NativePairDiagnostics() { }
}
