package io.airtap.preview;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.InputMethod;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Editor operations remain bound to a freshly observed node, never a global keyboard. */
final class AtomicInput {
    static final Set<String> ACTIONS = Set.of("focus", "set_selection", "select_all", "copy", "cut", "paste", "insert_text", "delete_text", "editor_action");
    static final Set<String> EDITOR_ACTIONS = Set.of("default", "done", "go", "next", "previous", "search", "send");
    static int integer(JSONObject q, String name, int max) throws Exception {
        Object value = q.get(name);
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0 || ((Number) value).longValue() > max)
            throw new ScopedUi.Refused("invalid_request");
        return ((Number) value).intValue();
    }
    static void validate(JSONObject q) throws Exception {
        String action = q.getString("action");
        if (action.equals("set_selection")) {
            integer(q, "start", Integer.MAX_VALUE); integer(q, "end", Integer.MAX_VALUE);
        } else if (action.equals("delete_text")) {
            int before = integer(q, "before", 2000), after = integer(q, "after", 2000);
            if (before + after == 0) throw new ScopedUi.Refused("invalid_request");
        } else if (action.equals("insert_text")) {
            Object value = q.get("text");
            if (!(value instanceof String)) throw new ScopedUi.Refused("invalid_request");
            String text = (String) value;
            if (text.codePointCount(0, text.length()) > 2000) throw new ScopedUi.Refused("text_too_large");
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new ScopedUi.Refused("invalid_request");
                } else if (Character.isLowSurrogate(c)) throw new ScopedUi.Refused("invalid_request");
            }
        } else if (action.equals("editor_action") && !EDITOR_ACTIONS.contains(q.getString("editor_action"))) throw new ScopedUi.Refused("invalid_request");
    }
    static boolean advertised(AccessibilityNodeInfo n, int id) {
        for (AccessibilityNodeInfo.AccessibilityAction action : n.getActionList()) if (action.getId() == id) return true;
        return false;
    }
    private static int nodeSelectAllEnd(AccessibilityNodeInfo n) {
        if (!advertised(n, AccessibilityNodeInfo.ACTION_SET_SELECTION)) return -1;
        // Android may expose an empty editor's hint as its text. Never select the hint length.
        if (n.isShowingHintText()) return 0;
        CharSequence value = n.getText();
        return value == null ? -1 : value.length(); // Selection offsets are UTF-16 units.
    }
    static JSONArray actions(AccessibilityNodeInfo n, boolean editor) {
        JSONArray out = new JSONArray();
        if (advertised(n, AccessibilityNodeInfo.ACTION_FOCUS)) out.put("focus");
        if (advertised(n, AccessibilityNodeInfo.ACTION_SET_SELECTION)) out.put("set_selection");
        if (nodeSelectAllEnd(n) >= 0) out.put("select_all");
        if (advertised(n, AccessibilityNodeInfo.ACTION_COPY)) out.put("copy");
        if (advertised(n, AccessibilityNodeInfo.ACTION_CUT)) out.put("cut");
        if (advertised(n, AccessibilityNodeInfo.ACTION_PASTE)) out.put("paste");
        if (advertised(n, AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())) out.put("editor_action");
        if (editor) for (String action : new String[]{"set_selection", "select_all", "copy", "cut", "paste", "insert_text", "delete_text", "editor_action"}) {
            boolean found = false; for (int i = 0; i < out.length(); i++) if (action.equals(out.optString(i))) found = true;
            if (!found) out.put(action);
        }
        return out;
    }
    record EditorTicket(GuardedInputMethod method, long generation) {}
    private record Editor(GuardedInputMethod method, InputMethod.AccessibilityInputConnection connection, EditorInfo info) {}
    static String editorStatus(AccessibilityService service, AccessibilityNodeInfo root, AccessibilityNodeInfo node) {
        InputMethod method = service.getInputMethod();
        if (!(method instanceof GuardedInputMethod guarded)) return "focused_editor_required";
        String error = guarded.bindingError(root, node); return error == null ? "ready" : error;
    }
    static EditorTicket capture(AccessibilityService service, AccessibilityNodeInfo root, AccessibilityNodeInfo node) {
        InputMethod method = service.getInputMethod();
        if (!(method instanceof GuardedInputMethod guarded) || guarded.bindingError(root, node) != null) return null;
        return new EditorTicket(guarded, guarded.generation());
    }
    private static Editor editor(AccessibilityService service, AccessibilityNodeInfo root, AccessibilityNodeInfo node, EditorTicket ticket) {
        InputMethod method = service.getInputMethod();
        if (!(method instanceof GuardedInputMethod guarded)) throw new ScopedUi.Refused("focused_editor_required");
        String error = guarded.bindingError(root, node);
        if (error != null) throw new ScopedUi.Refused(error);
        if (ticket == null) throw new ScopedUi.Refused("fresh_editor_snapshot_required");
        if (ticket.method() != guarded || ticket.generation() != guarded.generation()) throw new ScopedUi.Refused("editor_session_changed");
        return new Editor(guarded, guarded.getCurrentInputConnection(), guarded.getCurrentInputEditorInfo());
    }
    private static String standardEditorAction(int action) {
        return switch (action) {
            case EditorInfo.IME_ACTION_UNSPECIFIED -> "unspecified";
            case EditorInfo.IME_ACTION_NONE -> "none";
            case EditorInfo.IME_ACTION_DONE -> "done";
            case EditorInfo.IME_ACTION_GO -> "go";
            case EditorInfo.IME_ACTION_NEXT -> "next";
            case EditorInfo.IME_ACTION_PREVIOUS -> "previous";
            case EditorInfo.IME_ACTION_SEARCH -> "search";
            case EditorInfo.IME_ACTION_SEND -> "send";
            default -> "unknown";
        };
    }
    static JSONObject editorActionHint(AccessibilityService service, AccessibilityNodeInfo root,
                                       AccessibilityNodeInfo node, EditorTicket ticket) throws Exception {
        if (ticket == null) return null;
        Editor target;
        try { target = editor(service, root, node, ticket); }
        catch (ScopedUi.Refused unavailable) { return null; }
        if (target.info() == null) return null;
        boolean nativeEnter = advertised(node, AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        String option = standardEditorAction(target.info().imeOptions & EditorInfo.IME_MASK_ACTION);
        boolean custom = target.info().actionId != 0;
        // Native ACTION_IME_ENTER can deliver IME_NULL even when EditorInfo advertises DONE.
        return new JSONObject().put("default_route", nativeEnter ? "accessibility_node" : "input_connection")
                .put("default_action", nativeEnter ? "native_enter" : custom ? "custom" : option)
                .put("ime_option", option).put("custom_editor_action", custom)
                .put("requires_result_observation", true);
    }
    static String nextStep(String code) {
        return switch (code) {
            case "editor_selection_pending", "editor_selection_unavailable" -> "Read a fresh tree after the intended field and input method agree on its selection. Do not replay an edit whose outcome is unknown. An advertised native selection action can set an explicit range, followed by another fresh tree.";
            case "editor_composition_active", "editor_composition_unknown" -> "This delete was not dispatched. Read a fresh tree, select only the actual UTF-16 range to delete with set_selection, read a new tree and replace that selection using insert_text with empty text. Keep any originally selected content: before/after exclude it, so deleting both sides may need separate edits and fresh observations. Do not commit an empty string at an unverified selection or submit DONE just to clear composition.";
            default -> null;
        };
    }
    private static int nodeAction(String action) {
        return switch (action) {
            case "focus" -> AccessibilityNodeInfo.ACTION_FOCUS;
            case "set_selection" -> AccessibilityNodeInfo.ACTION_SET_SELECTION;
            case "copy" -> AccessibilityNodeInfo.ACTION_COPY;
            case "cut" -> AccessibilityNodeInfo.ACTION_CUT;
            case "paste" -> AccessibilityNodeInfo.ACTION_PASTE;
            default -> 0;
        };
    }
    static void perform(AccessibilityService service, AccessibilityNodeInfo root, AccessibilityNodeInfo node, JSONObject q, EditorTicket ticket, java.util.function.BooleanSupplier commandCurrent) throws Exception {
        validate(q); String action = q.getString("action");
        int selectAllEnd = action.equals("select_all") ? nodeSelectAllEnd(node) : -1;
        int id = action.equals("editor_action") && q.getString("editor_action").equals("default")
                ? AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId()
                : selectAllEnd >= 0 ? AccessibilityNodeInfo.ACTION_SET_SELECTION : nodeAction(action);
        if (id != 0 && advertised(node, id)) {
            Bundle args = null;
            if (action.equals("set_selection") || selectAllEnd >= 0) {
                args = new Bundle(); args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, selectAllEnd >= 0 ? 0 : integer(q, "start", Integer.MAX_VALUE));
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, selectAllEnd >= 0 ? selectAllEnd : integer(q, "end", Integer.MAX_VALUE));
            }
            if (!CommandFence.node(commandCurrent, node, id, args)) throw new ScopedUi.Refused("action_rejected");
            return;
        }
        if (action.equals("focus")) throw new ScopedUi.Refused("node_action_unavailable");
        Editor target = editor(service, root, node, ticket);
        InputMethod.AccessibilityInputConnection connection = target.connection();
        CommandFence.requireCurrent(commandCurrent);
        switch (action) {
            case "set_selection": connection.setSelection(integer(q, "start", Integer.MAX_VALUE), integer(q, "end", Integer.MAX_VALUE)); break;
            case "select_all": connection.performContextMenuAction(android.R.id.selectAll); break;
            case "copy": connection.performContextMenuAction(android.R.id.copy); break;
            case "cut": connection.performContextMenuAction(android.R.id.cut); break;
            case "paste": connection.performContextMenuAction(android.R.id.paste); break;
            case "insert_text": connection.commitText(q.getString("text"), 1, null); break;
            case "delete_text": {
                int before = integer(q, "before", 2000), after = integer(q, "after", 2000);
                String error = target.method().deletionError(before, after);
                if (error != null) throw new ScopedUi.Refused(error);
                CommandFence.requireCurrent(commandCurrent);
                connection.deleteSurroundingText(before, after); break;
            }
            case "editor_action": {
                int operation = switch (q.getString("editor_action")) {
                    case "done" -> EditorInfo.IME_ACTION_DONE;
                    case "go" -> EditorInfo.IME_ACTION_GO;
                    case "next" -> EditorInfo.IME_ACTION_NEXT;
                    case "previous" -> EditorInfo.IME_ACTION_PREVIOUS;
                    case "search" -> EditorInfo.IME_ACTION_SEARCH;
                    case "send" -> EditorInfo.IME_ACTION_SEND;
                    default -> target.info().actionId != 0 ? target.info().actionId : target.info().imeOptions & EditorInfo.IME_MASK_ACTION;
                };
                boolean custom = q.getString("editor_action").equals("default") && target.info().actionId != 0;
                if (!custom && (operation == EditorInfo.IME_ACTION_NONE || operation == EditorInfo.IME_ACTION_UNSPECIFIED)) throw new ScopedUi.Refused("editor_action_unavailable");
                connection.performEditorAction(operation); break;
            }
            default: throw new ScopedUi.Refused("unsupported_action");
        }
        // InputConnection methods are asynchronous and void; the caller must observe the actual result.
    }
}
