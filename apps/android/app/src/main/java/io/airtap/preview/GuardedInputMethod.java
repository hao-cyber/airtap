package io.airtap.preview;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.InputMethod;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;

/** Bind an IME session to an independently identified focused native field. */
final class GuardedInputMethod extends InputMethod {
    private final AccessibilityService service;
    private long generation;
    private boolean active;
    private EditorInfo startedInfo;
    private AccessibilityNodeInfo startedFocus;
    private int fieldId;
    private int selectionStart, selectionEnd;
    private int composingStart = -1, composingEnd = -1;
    private boolean compositionKnown;

    GuardedInputMethod(AccessibilityService service) { super(service); this.service = service; }
    @Override public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        generation++; active = true; startedInfo = attribute; startedFocus = null; fieldId = 0;
        selectionStart = attribute.initialSelStart; selectionEnd = attribute.initialSelEnd;
        composingStart = composingEnd = -1; compositionKnown = false;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        AccessibilityNodeInfo focus = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        AccessibilityNodeInfo candidate = validStartNode(root, focus, attribute) && focus.isFocused()
                ? focus : uniqueStartCandidate(root, attribute);
        if (candidate != null) {
            // Snapshot identity now, before issuing any editor ticket. The focus cache may still
            // name the previous field; later reads may confirm this anchor, never replace it.
            startedFocus = new AccessibilityNodeInfo(candidate); fieldId = attribute.fieldId;
        }
    }
    private boolean validStartNode(AccessibilityNodeInfo root, AccessibilityNodeInfo node, EditorInfo info) {
        return root != null && node != null && node.isEditable() && node.isVisibleToUser() && node.isEnabled()
                && node.getWindowId() == root.getWindowId() && nativeFieldId(node, info) == info.fieldId && info.fieldId > 0;
    }
    private AccessibilityNodeInfo uniqueStartCandidate(AccessibilityNodeInfo root, EditorInfo info) {
        if (root == null || info.fieldId <= 0 || info.packageName == null) return null;
        try {
            String resource = service.getPackageManager().getResourcesForApplication(info.packageName).getResourceName(info.fieldId);
            AccessibilityNodeInfo candidate = null;
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(resource)) {
                if (!validStartNode(root, node, info)) continue;
                if (candidate != null && !candidate.equals(node)) return null;
                candidate = node;
            }
            return candidate;
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException unavailable) { return null; }
    }
    @Override public void onFinishInput() {
        active = false; generation++; startedInfo = null; startedFocus = null; fieldId = 0;
        compositionKnown = false; composingStart = composingEnd = -1;
        super.onFinishInput();
    }
    @Override public void onUpdateSelection(int oldStart, int oldEnd, int newStart, int newEnd, int candidatesStart, int candidatesEnd) {
        // Either cursor or composing-region changes invalidate the captured editor state.
        if (selectionStart != newStart || selectionEnd != newEnd || !compositionKnown
                || composingStart != candidatesStart || composingEnd != candidatesEnd) generation++;
        selectionStart = newStart; selectionEnd = newEnd;
        composingStart = candidatesStart; composingEnd = candidatesEnd; compositionKnown = true;
        super.onUpdateSelection(oldStart, oldEnd, newStart, newEnd, candidatesStart, candidatesEnd);
    }
    private int nativeFieldId(AccessibilityNodeInfo node, EditorInfo info) {
        if (node == null || info == null || info.fieldId <= 0 || !String.valueOf(node.getPackageName()).equals(info.packageName)) return 0;
        String name = node.getViewIdResourceName();
        if (name == null || name.isEmpty()) return 0;
        try { return service.getPackageManager().getResourcesForApplication(info.packageName).getIdentifier(name, null, null); }
        catch (android.content.pm.PackageManager.NameNotFoundException | android.content.res.Resources.NotFoundException failure) { return 0; }
    }
    long generation() { return generation; }
    String bindingError(AccessibilityNodeInfo root, AccessibilityNodeInfo node) {
        if (!node.isEditable() || !node.isFocused() || !node.isVisibleToUser() || !node.isEnabled()) return "focused_editor_required";
        AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus == null || !focus.equals(node) || focus.getWindowId() != root.getWindowId()) return "focused_editor_required";
        EditorInfo info = getCurrentInputEditorInfo();
        if (!active || !getCurrentInputStarted() || info == null || info != startedInfo || getCurrentInputConnection() == null
                || !String.valueOf(node.getPackageName()).equals(info.packageName)) return "focused_editor_required";
        int resolved = nativeFieldId(node, info);
        if (resolved == 0) return "editor_identity_unavailable";
        if (resolved != info.fieldId) return "editor_target_changed";
        if (startedFocus == null || fieldId == 0) return "editor_identity_unavailable";
        if (fieldId != resolved || !startedFocus.equals(node)
                || startedFocus.getWindowId() != root.getWindowId()) return "editor_target_changed";
        if (node.getTextSelectionStart() < 0 || node.getTextSelectionEnd() < 0) return "editor_selection_unavailable";
        if (selectionStart != node.getTextSelectionStart() || selectionEnd != node.getTextSelectionEnd()) return "editor_selection_pending";
        return null;
    }
    String deletionError(int before, int after) {
        // BaseInputConnection expands deleteSurroundingText to composing boundaries. A callback
        // from this session is required; EditorInfo.initialSel does not describe composition.
        if (!compositionKnown) return "editor_composition_unknown";
        if (composingStart == -1 && composingEnd == -1) return null;
        if (composingStart < 0 || composingEnd < composingStart) return "editor_composition_unknown";
        int first = Math.min(selectionStart, selectionEnd), last = Math.max(selectionStart, selectionEnd);
        if ((before > 0 && composingStart < first) || (after > 0 && composingEnd > last)) return "editor_composition_active";
        return null;
    }
}
