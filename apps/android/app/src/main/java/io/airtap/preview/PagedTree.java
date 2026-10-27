package io.airtap.preview;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Bounded in-memory snapshot. Native identity, traversal order and full field fingerprints bind every page. */
final class PagedTree<N> {
    static final int MAX_NODES = 2048, MAX_CAPTURE_BYTES = 2 * 1024 * 1024, MAX_PAGE_NODES = 128;
    record Description(String identity, int children, boolean visible, JSONObject item, String metadataFingerprint) {
        Description(String identity, int children, boolean visible, JSONObject item) {
            this(identity, children, visible, item, PagedTree.metadataFingerprint(item));
        }
    }
    // Page consistency covers every exported field, independently of the deliberately narrower
    // action-target identity. Input is already password-masked; this digest stays snapshot-local.
    private static String metadataFingerprint(JSONObject item) {
        if (item == null) return "";
        try {
            StringBuilder value = new StringBuilder(); canonical(item, value);
            return ObservedText.localFingerprint(value, false);
        } catch (Exception invalid) { throw new ScopedUi.Refused("inspection_incomplete"); }
    }
    private static void canonical(Object value, StringBuilder out) throws Exception {
        if (value == null || value == JSONObject.NULL) { out.append('n'); return; }
        if (value instanceof JSONObject object) {
            List<String> keys = new ArrayList<>(); object.keys().forEachRemaining(keys::add); java.util.Collections.sort(keys);
            out.append('{');
            for (String key : keys) { canonical(key, out); canonical(object.get(key), out); }
            out.append('}');
        } else if (value instanceof JSONArray array) {
            out.append('['); for (int i = 0; i < array.length(); i++) canonical(array.get(i), out); out.append(']');
        } else if (value instanceof String string) {
            out.append('s').append(string.length()).append(':').append(string);
        } else if (value instanceof Boolean || value instanceof Number) {
            out.append(value instanceof Boolean ? 'b' : 'd').append(value).append(';');
        } else throw new ScopedUi.Refused("inspection_incomplete");
    }
    interface Source<N> { Description describe(N node); N child(N node, int index); }
    record Entry<N>(N node, Description description, int parent, int childIndex) {}
    record Pending<N>(N node, int parent, int childIndex) {}
    final List<Entry<N>> entries;
    final List<Entry<N>> visible;
    private PagedTree(List<Entry<N>> entries, List<Entry<N>> visible) { this.entries = entries; this.visible = visible; }
    static <N> PagedTree<N> capture(N root, Source<N> source, BooleanSupplier current) {
        if (root == null) throw new ScopedUi.Refused("inspection_incomplete");
        ArrayDeque<Pending<N>> queue = new ArrayDeque<>(); queue.add(new Pending<>(root, -1, -1));
        List<Entry<N>> entries = new ArrayList<>(), visible = new ArrayList<>(); int bytes = 0;
        while (!queue.isEmpty()) {
            if (!current.getAsBoolean()) throw new ScopedUi.Refused("inspection_incomplete");
            if (entries.size() >= MAX_NODES) throw new ScopedUi.Refused("inspection_limit");
            Pending<N> pending = queue.removeFirst(); Description description = source.describe(pending.node());
            if (description == null || description.identity() == null || description.children() < 0)
                throw new ScopedUi.Refused("inspection_incomplete");
            if (description.children() > MAX_NODES - entries.size() - queue.size() - 1)
                throw new ScopedUi.Refused("inspection_limit");
            bytes += description.identity().getBytes(StandardCharsets.UTF_8).length;
            if (description.item() != null) bytes += description.item().toString().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_CAPTURE_BYTES) throw new ScopedUi.Refused("inspection_limit");
            int index = entries.size(); Entry<N> entry = new Entry<>(pending.node(), description, pending.parent(), pending.childIndex());
            entries.add(entry); if (description.visible()) visible.add(entry);
            for (int i = 0; i < description.children(); i++) {
                N child = source.child(pending.node(), i);
                if (child == null) throw new ScopedUi.Refused("inspection_incomplete");
                queue.addLast(new Pending<>(child, index, i));
            }
        }
        if (!current.getAsBoolean()) throw new ScopedUi.Refused("inspection_incomplete");
        return new PagedTree<>(entries, visible);
    }
    boolean same(PagedTree<N> other) {
        if (other == null || entries.size() != other.entries.size()) return false;
        for (int i = 0; i < entries.size(); i++) {
            Entry<N> left = entries.get(i), right = other.entries.get(i);
            if (!Objects.equals(left.node(), right.node()) || left.parent() != right.parent() || left.childIndex() != right.childIndex()
                    || left.description().children() != right.description().children() || left.description().visible() != right.description().visible()
                    || !left.description().identity().equals(right.description().identity())
                    || !left.description().metadataFingerprint().equals(right.description().metadataFingerprint())) return false;
        }
        return true;
    }
    JSONObject page(int offset, int limit) throws Exception {
        if (offset < 0 || offset > visible.size() || limit < 1 || limit > MAX_PAGE_NODES) throw new ScopedUi.Refused("invalid_page");
        JSONArray output = new JSONArray(); TreeReadBudget budget = new TreeReadBudget(); int next = offset;
        while (next < visible.size() && output.length() < limit) {
            JSONObject item = visible.get(next).description().item();
            if (item == null) throw new ScopedUi.Refused("inspection_incomplete");
            if (!budget.include(item.toString().getBytes(StandardCharsets.UTF_8).length)) break;
            output.put(item); next++;
        }
        if (next == offset && next < visible.size()) throw new ScopedUi.Refused("inspection_limit");
        return new JSONObject().put("nodes", output).put("offset", offset).put("returned_nodes", output.length())
                .put("total_nodes", visible.size()).put("total_inspected_nodes", entries.size()).put("traversal_complete", true)
                .put("next_offset", next < visible.size() ? next : JSONObject.NULL).put("truncated", next < visible.size());
    }
}
