package io.airtap.preview;

import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only actions the target widget advertises, after CompanionUi validates its snapshot. */
final class SemanticNodeActions {
    private static final Map<String, Integer> ACTIONS = new LinkedHashMap<>();
    static {
        add("accessibility_focus", AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
        add("clear_accessibility_focus", AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
        add("next_granularity", AccessibilityNodeInfo.AccessibilityAction.ACTION_NEXT_AT_MOVEMENT_GRANULARITY);
        add("previous_granularity", AccessibilityNodeInfo.AccessibilityAction.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY);
        add("select", AccessibilityNodeInfo.AccessibilityAction.ACTION_SELECT);
        add("clear_selection", AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_SELECTION);
        add("clear_focus", AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_FOCUS);
        add("context_click", AccessibilityNodeInfo.AccessibilityAction.ACTION_CONTEXT_CLICK);
        add("expand", AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
        add("collapse", AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE);
        add("dismiss", AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS);
        add("show_on_screen", AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN);
        add("scroll_up", AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP);
        add("scroll_down", AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN);
        add("scroll_left", AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT);
        add("scroll_right", AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT);
        add("page_up", AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP);
        add("page_down", AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN);
        add("page_left", AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT);
        add("page_right", AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT);
        add("scroll_to_position", AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION);
        add("set_progress", AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        add("drag_start", AccessibilityNodeInfo.AccessibilityAction.ACTION_DRAG_START);
        add("drag_drop", AccessibilityNodeInfo.AccessibilityAction.ACTION_DRAG_DROP);
        add("drag_cancel", AccessibilityNodeInfo.AccessibilityAction.ACTION_DRAG_CANCEL);
    }
    private static final Map<String, Integer> GRANULARITIES = new LinkedHashMap<>();
    static {
        GRANULARITIES.put("character", AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER);
        GRANULARITIES.put("word", AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD);
        GRANULARITIES.put("line", AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE);
        GRANULARITIES.put("paragraph", AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH);
        GRANULARITIES.put("page", AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PAGE);
    }
    // Values captured with the exact tree sent to the caller, not discovered at execution time.
    record Ticket(java.util.Set<Integer> declared, Map<Integer, String> custom, int granularities) {}
    static boolean customId(int id) { return id >= 0x02000000; }
    private static String label(AccessibilityNodeInfo.AccessibilityAction action) {
        return action.getLabel() == null ? "" : action.getLabel().toString();
    }
    static Ticket capture(AccessibilityNodeInfo node) {
        java.util.Set<Integer> declared = new java.util.HashSet<>();
        Map<Integer, String> custom = new LinkedHashMap<>();
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
            declared.add(action.getId());
            // Framework IDs are reserved for their typed operations, never custom passthrough.
            String label = label(action);
            if (customId(action.getId()) && custom.size() < 32 && label.length() <= 2000)
                custom.put(action.getId(), label);
        }
        return new Ticket(java.util.Set.copyOf(declared), java.util.Map.copyOf(custom), node.getMovementGranularities());
    }
    private static void add(String name, AccessibilityNodeInfo.AccessibilityAction action) { ACTIONS.put(name, action.getId()); }
    private static boolean supports(AccessibilityNodeInfo node, int id) {
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) if (action.getId() == id) return true;
        return false;
    }
    static JSONArray supported(AccessibilityNodeInfo node) {
        JSONArray result = new JSONArray();
        for (Map.Entry<String, Integer> entry : ACTIONS.entrySet()) if (supports(node, entry.getValue())) result.put(entry.getKey());
        return result;
    }
    static Ticket metadata(AccessibilityNodeInfo node, JSONObject out, boolean masked) throws Exception {
        Ticket ticket = capture(node);
        JSONArray actions = supported(node), custom = new JSONArray(), granularities = new JSONArray();
        if (!ticket.custom().isEmpty()) actions.put("custom");
        for (Map.Entry<Integer, String> entry : ticket.custom().entrySet()) {
            String value = entry.getValue();
            // App-provided labels are observations, not trusted agent instructions.
            int length = value.offsetByCodePoints(0, Math.min(200, value.codePointCount(0, value.length())));
            custom.put(new JSONObject().put("action_id", entry.getKey()).put("label", masked ? "" : value.substring(0, length)));
        }
        for (Map.Entry<String, Integer> entry : GRANULARITIES.entrySet())
            if ((ticket.granularities() & entry.getValue()) != 0) granularities.put(entry.getKey());
        out.put("node_actions", actions).put("custom_actions", custom).put("movement_granularities", granularities);
        out.put("accessibility_focused", node.isAccessibilityFocused());
        out.put("focused", node.isFocused()); out.put("selected", node.isSelected());
        out.put("checkable", node.isCheckable()); out.put("checked", node.isChecked());
        AccessibilityNodeInfo.CollectionInfo collection = node.getCollectionInfo();
        if (collection != null) out.put("collection", new JSONObject().put("rows", collection.getRowCount())
                .put("columns", collection.getColumnCount()).put("hierarchical", collection.isHierarchical())
                .put("selection_mode", collection.getSelectionMode()));
        AccessibilityNodeInfo.RangeInfo range = node.getRangeInfo();
        if (range != null && Float.isFinite(range.getMin()) && Float.isFinite(range.getMax()) && Float.isFinite(range.getCurrent()))
            out.put("range", new JSONObject().put("min", range.getMin()).put("max", range.getMax())
                    .put("current", range.getCurrent()).put("type", range.getType()));
        return ticket;
    }
    static void validate(JSONObject request) throws Exception {
        Object raw = request.get("operation");
        if (!(raw instanceof String) || !(ACTIONS.containsKey((String) raw) || raw.equals("custom"))) throw new ScopedUi.Refused("invalid_node_operation");
        String operation = (String) raw;
        java.util.Set<String> fields = new java.util.HashSet<>(java.util.Arrays.asList("id", "action", "package", "snapshot_id", "node_id", "operation"));
        if (operation.equals("custom")) {
            fields.add("action_id");
            if (!customId(integer(request, "action_id"))) throw new ScopedUi.Refused("invalid_custom_action");
        } else if (operation.equals("next_granularity") || operation.equals("previous_granularity")) {
            fields.add("granularity"); fields.add("extend_selection");
            if (!(request.get("granularity") instanceof String) || !GRANULARITIES.containsKey(request.getString("granularity"))
                    || !(request.get("extend_selection") instanceof Boolean)) throw new ScopedUi.Refused("invalid_request");
        } else if (operation.equals("scroll_to_position")) {
            fields.add("row"); fields.add("column"); integer(request, "row"); integer(request, "column");
        } else if (operation.equals("set_progress")) {
            fields.add("value"); Object value = request.get("value");
            if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue()) || !Float.isFinite(((Number) value).floatValue())) throw new ScopedUi.Refused("invalid_request");
        }
        if (request.length() != fields.size()) throw new ScopedUi.Refused("invalid_request");
        for (String field : fields) if (!request.has(field)) throw new ScopedUi.Refused("invalid_request");
    }
    private static int integer(JSONObject request, String name) throws Exception {
        Object value = request.get(name);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new ScopedUi.Refused("invalid_request");
        long n = ((Number) value).longValue(); if (n < 0 || n > Integer.MAX_VALUE) throw new ScopedUi.Refused("invalid_request");
        return (int) n;
    }
    static int operation(JSONObject request, AccessibilityNodeInfo node, Ticket ticket) throws Exception {
        validate(request); String operation = request.getString("operation");
        int action = operation.equals("custom") ? integer(request, "action_id") : ACTIONS.get(operation);
        if (!supports(node, action)) throw new ScopedUi.Refused("node_operation_unavailable");
        if (ticket == null || !ticket.declared().contains(action)) throw new ScopedUi.Refused("fresh_snapshot_required");
        if (operation.equals("custom")) {
            if (!ticket.custom().containsKey(action)) throw new ScopedUi.Refused("fresh_snapshot_required");
            for (AccessibilityNodeInfo.AccessibilityAction current : node.getActionList())
                if (current.getId() == action && !ticket.custom().get(action).equals(label(current)))
                    throw new ScopedUi.Refused("stale_node_action");
        } else if (operation.equals("next_granularity") || operation.equals("previous_granularity")) {
            int granularity = GRANULARITIES.get(request.getString("granularity"));
            if ((ticket.granularities() & granularity) == 0) throw new ScopedUi.Refused("fresh_snapshot_required");
            if ((node.getMovementGranularities() & granularity) == 0) throw new ScopedUi.Refused("node_granularity_unavailable");
        }
        return action;
    }
    static Bundle arguments(JSONObject request, AccessibilityNodeInfo node) throws Exception {
        String operation = request.getString("operation");
        if (operation.equals("custom")) return null; // No caller-supplied Bundle or hidden arguments.
        Bundle args = new Bundle();
        if (operation.equals("next_granularity") || operation.equals("previous_granularity")) {
            int granularity = GRANULARITIES.get(request.getString("granularity"));
            if ((node.getMovementGranularities() & granularity) == 0) throw new ScopedUi.Refused("node_granularity_unavailable");
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, granularity);
            args.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, (Boolean) request.get("extend_selection"));
        } else if (operation.equals("scroll_to_position")) {
            int row = integer(request, "row"), column = integer(request, "column");
            AccessibilityNodeInfo.CollectionInfo collection = node.getCollectionInfo();
            if (collection != null && ((collection.getRowCount() > 0 && row >= collection.getRowCount()) || (collection.getColumnCount() > 0 && column >= collection.getColumnCount()))) throw new ScopedUi.Refused("node_position_out_of_range");
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, row);
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, column);
        } else if (operation.equals("set_progress")) {
            double value = ((Number) request.get("value")).doubleValue();
            AccessibilityNodeInfo.RangeInfo range = node.getRangeInfo();
            if (range != null) {
                if (!Float.isFinite(range.getMin()) || !Float.isFinite(range.getMax()) || range.getMin() > range.getMax())
                    throw new ScopedUi.Refused("node_progress_unavailable");
                if (value < range.getMin() || value > range.getMax()) throw new ScopedUi.Refused("node_progress_out_of_range");
            }
            args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, (float) value);
        }
        return args;
    }
    private SemanticNodeActions() {}
}
