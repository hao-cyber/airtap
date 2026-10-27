package io.airtap.preview;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.InputType;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import static io.airtap.preview.SnapshotGuard.Reason.*;
import static io.airtap.preview.SnapshotDiagnostics.Stage.*;

/** A short-lived snapshot binds actions to a selected app's exact accessibility nodes. */
final class ScopedUi {
    static final class Refused extends RuntimeException {
        final String code;
        Refused(String code) { super(code); this.code = code; }
    }
    private static final class Target {
        final AccessibilityNodeInfo node;
        final byte[] identity;
        Target(AccessibilityNodeInfo node, byte[] identity) { this.node = node; this.identity = identity; }
    }
    private static final class Description extends CompleteTree.Description {
        final JSONObject publicItem;
        Description(byte[] attributes, int children, JSONObject publicItem) {
            super(attributes, children);
            this.publicItem = publicItem;
        }
    }
    private final AccessibilityService service;
    private final Map<String, Target> targets = new LinkedHashMap<>();
    private final SnapshotGuard guard = new SnapshotGuard();
    private String snapshot;
    // Observational only: these labels never control authorization or snapshot validity.
    private SnapshotDiagnostics.Stage diagnosticStage = READ_SCOPE;
    private SnapshotGuard.Reason diagnosticReason = NONE;
    private TreeFieldDiagnostics.Summary diagnosticBaseline;
    private static final long SCAN_BUDGET_MS = 5000;

    ScopedUi(AccessibilityService service) { this.service = service; }

    void clear() {
        targets.clear();
        snapshot = null;
        guard.clear();
        diagnosticBaseline = null;
    }

    private void trace(SnapshotDiagnostics.Stage stage, SnapshotGuard.Reason reason) {
        try {
            android.util.Log.i("AirtapPreview", SnapshotDiagnostics.format(stage, reason,
                    guard.lastInvalidation(), SystemClock.elapsedRealtime()));
        } catch (RuntimeException ignored) { /* Logging must not change the command result. */ }
    }

    private void traceTreeChanges(TreeFieldDiagnostics.Summary left, TreeFieldDiagnostics.Summary right) {
        try {
            android.util.Log.i("AirtapPreview", TreeFieldDiagnostics.format(diagnosticStage,
                    TreeFieldDiagnostics.difference(left, right), SystemClock.elapsedRealtime())
                    + " alignment=positional_unavailable");
        } catch (RuntimeException ignored) { /* Diagnostics never change the existing refusal. */ }
    }

    private void tracePair(CompleteTree.Capture<AccessibilityNodeInfo, Description> first,
                           CompleteTree.Capture<AccessibilityNodeInfo, Description> repeated,
                           AccessibilityNodeInfo target, boolean targetRequested) {
        try {
            NativePairDiagnostics.Budget budget = new NativePairDiagnostics.Budget(SystemClock::elapsedRealtime);
            NativePairDiagnostics.Result result = NativePairDiagnostics.comparePair(first, repeated, target, targetRequested,
                    new NativePairDiagnostics.Identity<AccessibilityNodeInfo>() {
                        @Override public int hash(AccessibilityNodeInfo node) { return node.hashCode(); }
                        @Override public boolean same(AccessibilityNodeInfo left, AccessibilityNodeInfo right) { return left.equals(right); }
                    }, budget);
            result = NativePairDiagnostics.forRejectedPair(result);
            android.util.Log.i("AirtapPreview", NativePairDiagnostics.format(diagnosticStage, result, budget));
        } catch (RuntimeException ignored) { /* Preserve the original failure and its cleanup. */ }
    }

    private Refused refused(String code, SnapshotGuard.Reason reason) {
        diagnosticReason = reason;
        return new Refused(code);
    }

    private static SnapshotGuard.Reason refusalReason(String code) {
        return switch (code) {
            case "device_locked" -> DEVICE_LOCKED;
            case "not_foreground" -> NOT_FOREGROUND;
            case "unexpected_window" -> UNEXPECTED_WINDOW;
            case "sensitive_input" -> SENSITIVE_INPUT;
            case "inspection_limit" -> INSPECTION_LIMIT;
            case "inspection_incomplete" -> INCOMPLETE;
            case "not_actionable" -> NOT_ACTIONABLE;
            case "not_editable" -> NOT_EDITABLE;
            case "not_scrollable" -> NOT_SCROLLABLE;
            case "unsupported_action" -> UNSUPPORTED_ACTION;
            case "permission_denied" -> PERMISSION_DENIED;
            case "action_rejected" -> ACTION_REJECTED;
            default -> UNEXPECTED_FAILURE;
        };
    }

    private void traceFailure(Throwable failure) {
        SnapshotGuard.Reason reason = diagnosticReason != NONE ? diagnosticReason
                : failure instanceof Refused ? refusalReason(((Refused) failure).code) : UNEXPECTED_FAILURE;
        trace(diagnosticStage, reason);
        guard.recordInvalidation(reason);
    }

    void changed(AccessibilityEvent event) {
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || (event.getWindowId() == guard.window() && (type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED))) {
            if (guard.hasContext()) {
                SnapshotGuard.Reason reason = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ? EVENT_WINDOW_STATE
                        : type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ? EVENT_WINDOWS
                        : type == AccessibilityEvent.TYPE_VIEW_SCROLLED ? EVENT_SCROLL : EVENT_TEXT;
                trace(EVENT, reason);
                guard.recordInvalidation(reason);
            }
            clear();
        } else if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            boolean wasDirty = guard.dirty();
            guard.contentChanged(event.getWindowId());
            if (!wasDirty && guard.dirty()) trace(EVENT, CONTENT_DIRTY);
        }
    }

    boolean unlocked() {
        KeyguardManager lock = service.getSystemService(KeyguardManager.class);
        PowerManager power = service.getSystemService(PowerManager.class);
        return lock != null && !lock.isKeyguardLocked() && power != null && power.isInteractive();
    }

    AccessibilityNodeInfo root(String packageName) {
        if (!unlocked()) throw new Refused("device_locked");
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null || !packageName.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
            throw new Refused("not_foreground");
        }
        for (AccessibilityWindowInfo other : service.getWindows()) {
            if (other.getId() == root.getWindowId()) continue;
            if (other.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) throw new Refused("unexpected_window");
            if (other.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                AccessibilityNodeInfo content = other.getRoot();
                if (content != null && !packageName.contentEquals(content.getPackageName() == null ? "" : content.getPackageName())) {
                    throw new Refused("unexpected_window");
                }
            }
        }
        rejectPasswordInputs(root);
        return root;
    }

    private static void rejectPasswordInputs(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int count = 0;
        while (!queue.isEmpty()) {
            if (++count > 2048) throw new Refused("inspection_limit");
            AccessibilityNodeInfo node = queue.removeFirst();
            if (sensitiveInput(node)) throw new Refused("sensitive_input");
            for (int i = 0; i < node.getChildCount() && queue.size() <= 2048; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) throw new Refused("inspection_incomplete");
                queue.addLast(child);
            }
            if (queue.size() > 2048) throw new Refused("inspection_limit");
        }
    }

    private static boolean sensitiveInput(AccessibilityNodeInfo node) {
        if (node.isPassword()) return true;
        int type = node.getInputType();
        int kind = type & InputType.TYPE_MASK_CLASS;
        int variation = type & InputType.TYPE_MASK_VARIATION;
        return kind == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                || kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    JSONObject read(String packageName) {
        diagnosticStage = READ_SCOPE;
        diagnosticReason = NONE;
        try { return readChecked(packageName); }
        catch (RuntimeException | Error failure) { traceFailure(failure); throw failure; }
    }

    private JSONObject readChecked(String packageName) {
        clear();
        long started = SystemClock.elapsedRealtime();
        AccessibilityNodeInfo root = root(packageName);
        int window = root.getWindowId();
        long revision = guard.beginRead(window);
        String next = java.util.UUID.randomUUID().toString().replace("-", "");
        Map<String, Target> collected = new LinkedHashMap<>();
        JSONArray nodes = new JSONArray();
        boolean truncated = false;
        int bytes = 0;
        // Export and the original fingerprint come from these exact same observations.
        diagnosticStage = READ_FIRST_SCAN;
        CompleteTree.Capture<AccessibilityNodeInfo, Description> first = capture(packageName, window, revision, started, true);
        diagnosticStage = READ_SECOND_SCAN;
        CompleteTree.Capture<AccessibilityNodeInfo, Description> repeated = capture(packageName, window, revision, started, false);
        for (CompleteTree.Entry<AccessibilityNodeInfo, Description> entry : first.entries) {
            JSONObject item = entry.description.publicItem;
            if (item == null) continue;
            String id = Integer.toString(collected.size() + 1);
            try { item.put("node_id", id); }
            catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
            bytes += item.toString().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 60000 || collected.size() >= 128) { truncated = true; break; }
            collected.put(id, new Target(entry.node, entry.description.attributes));
            nodes.put(item);
        }
        diagnosticStage = READ_FINAL_SCOPE;
        AccessibilityNodeInfo still = root(packageName);
        if (SystemClock.elapsedRealtime() - started >= SCAN_BUDGET_MS) { clear(); throw refused("inspection_incomplete", SCAN_BUDGET); }
        diagnosticStage = READ_PUBLISH;
        if (still.getWindowId() != window) { clear(); throw refused("stale_snapshot", WINDOW_CHANGED); }
        if (!guard.publish(next, packageName, window, first.fingerprint,
                repeated.fingerprint, started, SystemClock.elapsedRealtime(), revision)) {
            if (guard.failureReason() == READ_INCONSISTENT) {
                tracePair(first, repeated, null, false);
            }
            clear();
            throw refused("stale_snapshot", guard.failureReason());
        }
        snapshot = next;
        targets.putAll(collected);
        diagnosticBaseline = TreeFieldDiagnostics.summarize(first);
        return RelayApi.json("package", packageName, "snapshot_id", snapshot, "window_id", window,
                "expires_in_ms", guard.remaining(SystemClock.elapsedRealtime()), "nodes", nodes, "truncated", truncated);
    }

    JSONObject act(String action, String packageName, String snapshotId, String nodeId, String text, String direction,
                   BooleanSupplier sessionStillAuthorized) {
        diagnosticStage = ACT_SCOPE;
        diagnosticReason = NONE;
        return SnapshotGuard.action(() -> {
            try { return actChecked(action, packageName, snapshotId, nodeId, text, direction, sessionStillAuthorized); }
            catch (RuntimeException | Error failure) { traceFailure(failure); throw failure; }
        }, this::clear);
    }

    private JSONObject actChecked(String action, String packageName, String snapshotId, String nodeId, String text,
                                  String direction, BooleanSupplier sessionStillAuthorized) {
        long started = SystemClock.elapsedRealtime();
        AccessibilityNodeInfo root = root(packageName);
        int window = root.getWindowId();
        diagnosticStage = ACT_CURRENT;
        if (!guard.current(snapshotId, packageName, window, SystemClock.elapsedRealtime())) throw refused("stale_snapshot", guard.failureReason());
        long revision = guard.revision();
        boolean inspect = guard.dirty();
        if (inspect) {
            diagnosticStage = ACT_FIRST_SCAN;
            CompleteTree.Capture<AccessibilityNodeInfo, Description> first = capture(packageName, window, revision, started, false);
            diagnosticStage = ACT_SECOND_SCAN;
            CompleteTree.Capture<AccessibilityNodeInfo, Description> repeated = capture(packageName, window, revision, started, false);
            diagnosticStage = ACT_DIRTY_VERIFY;
            if (!guard.verify(first.fingerprint, repeated.fingerprint, revision, SystemClock.elapsedRealtime())) {
                if (guard.failureReason() == ORIGINAL_CHANGED) {
                    traceTreeChanges(diagnosticBaseline, TreeFieldDiagnostics.summarize(first));
                } else if (guard.failureReason() == SECOND_SCAN_CHANGED) {
                    Target diagnosticTarget = targets.get(nodeId);
                    tracePair(first, repeated, diagnosticTarget == null ? null : diagnosticTarget.node, true);
                }
                clear();
                throw refused("stale_snapshot", guard.failureReason());
            }
        }
        diagnosticStage = ACT_TARGET;
        Target target = targets.get(nodeId);
        if (target == null) throw refused("stale_node", TARGET_MISSING);
        if (!target.node.refresh()) throw refused("stale_node", TARGET_REFRESH);
        if (target.node.getWindowId() != window) throw refused("stale_node", TARGET_WINDOW);
        if (!packageName.contentEquals(target.node.getPackageName() == null ? "" : target.node.getPackageName())) throw refused("stale_node", TARGET_PACKAGE);
        if (!target.node.isVisibleToUser()) throw refused("stale_node", TARGET_HIDDEN);
        if (!target.node.isEnabled()) throw refused("stale_node", TARGET_DISABLED);
        if (sensitiveInput(target.node)) throw refused("stale_node", TARGET_SENSITIVE);
        if (!CompleteTree.same(target.identity, attributes(target.node))) throw refused("stale_node", TARGET_IDENTITY);
        diagnosticStage = ACT_ACTION_CHECK;
        int operation;
        Bundle arguments = null;
        switch (action) {
            case "click":
                if (!target.node.isClickable()) throw new Refused("not_actionable");
                operation = AccessibilityNodeInfo.ACTION_CLICK;
                break;
            case "set_text":
                if (!target.node.isEditable() || text == null || text.codePointCount(0, text.length()) > 2000) throw new Refused("not_editable");
                operation = AccessibilityNodeInfo.ACTION_SET_TEXT;
                arguments = new Bundle();
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                break;
            case "scroll":
                if (!target.node.isScrollable()) throw new Refused("not_scrollable");
                operation = "forward".equals(direction) ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                break;
            default: throw new Refused("unsupported_action");
        }
        // Traversal can take time. Recheck the actual grant, scope and original deadline at dispatch.
        diagnosticStage = ACT_FINAL_SCOPE;
        AccessibilityNodeInfo finalRoot = root(packageName);
        diagnosticStage = ACT_FINAL_CHECK;
        if (inspect && SystemClock.elapsedRealtime() - started >= SCAN_BUDGET_MS) { clear(); throw refused("inspection_incomplete", SCAN_BUDGET); }
        if (!sessionStillAuthorized.getAsBoolean()) { clear(); throw new Refused("permission_denied"); }
        if (!guard.unchanged(revision)) { clear(); throw refused("stale_snapshot", REVISION_CHANGED); }
        if (!guard.current(snapshotId, packageName, finalRoot.getWindowId(), SystemClock.elapsedRealtime())) {
            clear(); throw refused("stale_snapshot", guard.failureReason());
        }
        // Consume before dispatch so failure or a network timeout cannot replay this snapshot.
        guard.recordInvalidation(CONSUMED);
        clear();
        diagnosticStage = ACT_DISPATCH;
        boolean accepted = target.node.performAction(operation, arguments);
        if (!accepted) throw new Refused("action_rejected");
        return RelayApi.json("package", packageName, "action", action, "accepted", accepted,
                "method", "accessibility_node", "requires_fresh_snapshot", true);
    }

    private static String clipped(CharSequence value, int maximum) {
        if (value == null) return "";
        String text = value.toString();
        int length = text.codePointCount(0, text.length());
        return length <= maximum ? text : text.substring(0, text.offsetByCodePoints(0, maximum));
    }

    private CompleteTree.Capture<AccessibilityNodeInfo, Description> capture(String packageName, int window,
                                                                          long revision, long started, boolean export) {
        try {
            AccessibilityNodeInfo root = root(packageName);
            if (root.getWindowId() != window) throw refused("stale_snapshot", WINDOW_CHANGED);
            return CompleteTree.capture(root, new CompleteTree.Source<AccessibilityNodeInfo, Description>() {
                @Override public Description describe(AccessibilityNodeInfo node) {
                    if (!node.refresh()) { diagnosticReason = TARGET_REFRESH; throw new CompleteTree.Incomplete(); }
                    if (node.getWindowId() != window) { diagnosticReason = WINDOW_CHANGED; throw new CompleteTree.Incomplete(); }
                    if (sensitiveInput(node)) throw new Refused("sensitive_input");
                    byte[] identity = attributes(node);
                    JSONObject item = null;
                    if (export && node.isVisibleToUser() && packageName.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())) {
                        Rect bounds = new Rect();
                        node.getBoundsInScreen(bounds);
                        item = RelayApi.json("class", clipped(node.getClassName(), 100),
                                "view_id", clipped(node.getViewIdResourceName(), 160), "text", clipped(node.getText(), 200),
                                "description", clipped(node.getContentDescription(), 200),
                                "bounds", new JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom),
                                "clickable", node.isClickable(), "editable", node.isEditable(), "scrollable", node.isScrollable(),
                                "enabled", node.isEnabled());
                    }
                    return new Description(identity, node.getChildCount(), item);
                }
                @Override public AccessibilityNodeInfo child(AccessibilityNodeInfo node, int index) { return node.getChild(index); }
            }, () -> {
                if (!guard.unchanged(revision)) { diagnosticReason = REVISION_CHANGED; return false; }
                if (SystemClock.elapsedRealtime() - started >= SCAN_BUDGET_MS) { diagnosticReason = SCAN_BUDGET; return false; }
                return true;
            });
        } catch (Refused refused) { clear(); throw refused; }
        catch (RuntimeException incomplete) {
            if (diagnosticReason == NONE) diagnosticReason = INCOMPLETE;
            clear(); throw new Refused("inspection_incomplete");
        }
    }

    private static byte[] attributes(AccessibilityNodeInfo node) {
        CompleteTree.Values value = new CompleteTree.Values().integer(node.getWindowId()).integer(node.getChildCount())
                .text(node.getPackageName()).text(node.getClassName()).text(node.getViewIdResourceName()).text(node.getUniqueId())
                .text(node.getText()).text(node.getContentDescription()).text(node.getStateDescription())
                .text(node.getHintText()).text(node.getError()).text(node.getTooltipText()).text(node.getPaneTitle()).text(node.getContainerTitle())
                .bool(node.isVisibleToUser()).bool(node.isEnabled()).bool(node.isCheckable()).bool(node.isChecked()).bool(node.isSelected())
                .bool(node.isEditable()).bool(node.isClickable()).bool(node.isLongClickable()).bool(node.isContextClickable())
                .bool(node.isScrollable()).bool(node.isPassword()).bool(node.isFocusable()).bool(node.isFocused()).bool(node.isAccessibilityFocused())
                .bool(node.isContentInvalid()).bool(node.isMultiLine()).bool(node.isDismissable()).bool(node.canOpenPopup())
                .bool(node.isHeading()).bool(node.isScreenReaderFocusable()).bool(node.isShowingHintText()).bool(node.isTextEntryKey())
                .bool(node.isTextSelectable()).bool(node.isImportantForAccessibility()).bool(node.isAccessibilityDataSensitive())
                .integer(node.getInputType()).integer(node.getTextSelectionStart()).integer(node.getTextSelectionEnd())
                .integer(node.getMaxTextLength()).integer(node.getLiveRegion()).integer(node.getMovementGranularities()).integer(node.getDrawingOrder());
        Rect rect = new Rect();
        node.getBoundsInScreen(rect); rectangle(value, rect);
        node.getBoundsInParent(rect); rectangle(value, rect);
        node.getBoundsInWindow(rect); rectangle(value, rect);
        java.util.List<AccessibilityNodeInfo.AccessibilityAction> actions = node.getActionList();
        value.integer(actions.size());
        for (AccessibilityNodeInfo.AccessibilityAction action : actions) value.integer(action.getId()).text(action.getLabel());
        AccessibilityNodeInfo.RangeInfo range = node.getRangeInfo();
        value.bool(range != null);
        if (range != null) value.integer(range.getType()).number(range.getMin()).number(range.getMax()).number(range.getCurrent());
        AccessibilityNodeInfo.CollectionInfo collection = node.getCollectionInfo();
        value.bool(collection != null);
        if (collection != null) value.integer(collection.getRowCount()).integer(collection.getColumnCount())
                .bool(collection.isHierarchical()).integer(collection.getSelectionMode());
        AccessibilityNodeInfo.CollectionItemInfo item = node.getCollectionItemInfo();
        value.bool(item != null);
        if (item != null) value.integer(item.getRowIndex()).integer(item.getColumnIndex()).integer(item.getRowSpan())
                .integer(item.getColumnSpan()).bool(item.isHeading()).bool(item.isSelected());
        if (Build.VERSION.SDK_INT >= 36) value.integer(node.getChecked()).integer(node.getExpandedState())
                .bool(node.isFieldRequired()).text(node.getSupplementalDescription());
        return value.bytes();
    }

    private static void rectangle(CompleteTree.Values value, Rect rect) {
        value.integer(rect.left).integer(rect.top).integer(rect.right).integer(rect.bottom);
    }
}
