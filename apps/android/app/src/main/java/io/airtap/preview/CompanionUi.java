package io.airtap.preview;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Full-phone executor. Native nodes and recent window images are two complementary paths. */
final class CompanionUi {
    interface Reply { void success(JSONObject result); void error(String code); }
    private final AccessibilityService service;
    private final BooleanSupplier authorized;
    private final Map<String, Target> nodes = new LinkedHashMap<>();
    private String snapshot, imageId, snapshotPackage, imagePackage;
    private int snapshotWindow, imageWindow;
    private long snapshotDeadline, imageDeadline, revision;
    private Rect imageBounds, imageWindowBounds;
    private int imageRotation;
    private TouchGesture activeGesture;
    private PagedTree<AccessibilityNodeInfo> pagedTree;
    private static final long TREE_SCAN_MS = 5000;
    private static final long TTL = 30000;
    private record Target(AccessibilityNodeInfo node, String identity, AtomicInput.EditorTicket editor, SemanticNodeActions.Ticket semantics) {}
    CompanionUi(AccessibilityService service, BooleanSupplier authorized) { this.service = service; this.authorized = authorized; }
    void clear() { revision++; nodes.clear(); snapshot = null; imageId = null; pagedTree = null; }
    void cancelGestures() { if (activeGesture != null) activeGesture.cancel(); }
    private void check() {
        if (!authorized.getAsBoolean()) throw new ScopedUi.Refused("permission_denied");
        if (FileScopes.pickerActive(service)) throw new ScopedUi.Refused("file_authorization_in_progress");
        String error = screenState().error();
        if (error != null) throw new ScopedUi.Refused(error);
    }
    ScreenPreparation.State screenState() { return ScreenRecovery.state(service); }
    boolean unlocked() { return screenState() == ScreenPreparation.State.READY; }
    boolean hasRecoveryWindow() {
        if (service.getRootInActiveWindow() == null) {
            try { if (WindowInventory.capture(service).select().root() == null) return true; }
            catch (RuntimeException unavailable) { return true; }
        }
        for (AccessibilityWindowInfo window : service.getWindows()) {
            if (ScreenRecoveryActivity.WINDOW_TITLE.contentEquals(window.getTitle() == null ? "" : window.getTitle())) return true;
        }
        return false;
    }
    private AccessibilityNodeInfo root(String expected) {
        check();
        return WindowInventory.capture(service).root(expected);
    }
    private Rect bounds(AccessibilityNodeInfo root) {
        for (AccessibilityWindowInfo window : service.getWindows()) if (window.getId() == root.getWindowId()) {
            Rect rect = new Rect(); window.getBoundsInScreen(rect);
            if (!rect.isEmpty()) return rect;
        }
        throw new ScopedUi.Refused("window_unavailable");
    }
    private String pkg(AccessibilityNodeInfo node) { return String.valueOf(node.getPackageName()); }
    private Rect displayBounds() {
        android.graphics.Point size = new android.graphics.Point();
        service.getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRealSize(size);
        return new Rect(0, 0, size.x, size.y);
    }
    private void noOwnerWindow() {
        for (AccessibilityWindowInfo window : service.getWindows()) {
            AccessibilityNodeInfo node = window.getRoot();
            if (node != null && (pkg(node).equals(BuildConfig.APPLICATION_ID) || pkg(node).equals("io.airtap.preview") || pkg(node).startsWith("io.airtap.preview.")))
                throw new ScopedUi.Refused("protected_airtap_window");
        }
    }
    private static String text(CharSequence value) {
        return ObservedText.clip(value, 200);
    }
    private static boolean secret(AccessibilityNodeInfo node) {
        int type = node.getInputType(), kind = type & 15, variation = type & 4080;
        return node.isPassword() || (kind == 1 && (variation == 128 || variation == 144 || variation == 224)) || (kind == 2 && variation == 16);
    }
    private static String identity(AccessibilityNodeInfo node) {
        Rect rect = new Rect(); node.getBoundsInScreen(rect);
        // Unrelated counters, descendants and animation state do not invalidate a stable target.
        boolean masked = secret(node);
        return RelayApi.json("package", text(node.getPackageName()), "class", text(node.getClassName()),
                "view", text(node.getViewIdResourceName()), "unique", text(node.getUniqueId()),
                "text_fingerprint", ObservedText.localFingerprint(masked ? null : node.getText(), masked),
                "description_fingerprint", ObservedText.localFingerprint(masked ? null : node.getContentDescription(), masked),
                "hint_fingerprint", ObservedText.localFingerprint(masked ? null : node.getHintText(), masked),
                "password", masked, "enabled", node.isEnabled(), "visible", node.isVisibleToUser(),
                "showing_hint_text", node.isShowingHintText(),
                "bounds", rect.toShortString(), "clickable", node.isClickable(), "editable", node.isEditable(),
                "focused", node.isFocused(), "selected", node.isSelected(), "checked", node.isChecked(), "selection_start", node.getTextSelectionStart(), "selection_end", node.getTextSelectionEnd()).toString();
    }
    private static void exact(JSONObject request, String... fields) {
        java.util.Set<String> expected = new java.util.HashSet<>(java.util.Arrays.asList(fields));
        expected.add("id"); expected.add("action");
        if (request.length() != expected.size()) throw new ScopedUi.Refused("invalid_request");
        for (String field : expected) if (!request.has(field)) throw new ScopedUi.Refused("invalid_request");
    }
    private JSONArray rectangle(Rect r) { return new JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom); }
    void validateRequest(JSONObject request) throws Exception {
        String action = request.getString("action");
        if (FileScopes.ACTIONS.contains(action)) {
            try { FileScopes.validate(request); }
            catch (FileTransferStore.Failure failure) { throw new ScopedUi.Refused(failure.code); }
            return;
        }
        String expected = request.has("package") ? request.getString("package") : null;
        if (expected != null && !AppCatalog.allowed(expected)) throw new ScopedUi.Refused("protected_package");
        // Reject unsupported/malformed requests before they can wake the screen.
        switch (action) {
            case "apps": case "windows": case "foreground": case "observe": case "home": case "back": case "recents": case "system_actions": exact(request); break;
            case "system_action": exact(request, "name"); if (!SystemActions.known(request.getString("name"))) throw new ScopedUi.Refused("invalid_request"); break;
            case "audio_state": exact(request); break;
            case "set_volume": case "adjust_volume": AudioVolume.validate(request); break;
            case "read_tree": if (expected == null) exact(request); else exact(request, "package"); break;
            case "read_tree_page":
                exact(request, "package", "snapshot_id", "offset", "limit");
                integer(request, "offset", 0, PagedTree.MAX_NODES); integer(request, "limit", 1, PagedTree.MAX_PAGE_NODES); break;
            case "read_node_text":
                exact(request, "package", "snapshot_id", "node_id", "field", "offset", "length");
                integer(request, "offset", 0, Integer.MAX_VALUE); integer(request, "length", 2, ObservedText.MAX_CHUNK_UTF16);
                if (!java.util.Set.of("text", "description", "hint_text").contains(request.getString("field"))) throw new ScopedUi.Refused("invalid_request"); break;
            case "launch": exact(request, "package"); break;
            case "open_url": exact(request, "package", "url"); BrowserUrl.validate(request.getString("url")); break;
            case "click": case "long_click": exact(request, "package", "snapshot_id", "node_id"); break;
            case "node_action": SemanticNodeActions.validate(request); break;
            case "set_text": exact(request, "package", "snapshot_id", "node_id", "text"); break;
            case "focus": case "select_all": case "copy": case "cut": case "paste": exact(request, "package", "snapshot_id", "node_id"); break;
            case "set_selection": exact(request, "package", "snapshot_id", "node_id", "start", "end"); AtomicInput.validate(request); break;
            case "insert_text": exact(request, "package", "snapshot_id", "node_id", "text"); AtomicInput.validate(request); break;
            case "delete_text": exact(request, "package", "snapshot_id", "node_id", "before", "after"); AtomicInput.validate(request); break;
            case "editor_action": exact(request, "package", "snapshot_id", "node_id", "editor_action"); AtomicInput.validate(request); break;
            case "scroll": exact(request, "package", "snapshot_id", "node_id", "direction"); break;
            case "tap": case "long_press": exact(request, "package", "observation_id", "x", "y"); break;
            case "swipe": exact(request, "package", "observation_id", "x", "y", "end_x", "end_y", "duration_ms"); break;
            case "drag": exact(request, "package", "observation_id", "x", "y", "hold_ms", "path"); DragPlan.parse(request); break;
            case "gesture": exact(request, "package", "observation_id", "tracks"); GesturePlan.parse(request, GestureDescription.getMaxStrokeCount()); break;
            default: throw new ScopedUi.Refused("unsupported_action");
        }
    }
    JSONObject foreground() {
        AccessibilityNodeInfo r = root(null);
        return RelayApi.json("package", pkg(r), "window_id", r.getWindowId(), "display_id", android.view.Display.DEFAULT_DISPLAY,
                "screen_bounds", rectangle(bounds(r)), "bounds_coordinate_space", "display_pixels", "device_unlocked", true,
                "rotation", service.getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRotation());
    }
    private static int integer(JSONObject request, String name, int min, int max) throws Exception {
        Object value = request.get(name);
        if (!(value instanceof Integer || value instanceof Long) || ((Number)value).longValue() < min || ((Number)value).longValue() > max)
            throw new ScopedUi.Refused("invalid_request");
        return ((Number)value).intValue();
    }
    private PagedTree<AccessibilityNodeInfo> scan(AccessibilityNodeInfo r, String packageName, int window, long started,
                                                long expectedRevision, boolean export, BooleanSupplier commandCurrent) {
        final int[] nextId = {1};
        return PagedTree.capture(r, new PagedTree.Source<AccessibilityNodeInfo>() {
            public PagedTree.Description describe(AccessibilityNodeInfo n) {
                if (!n.refresh() || n.getWindowId() != window || !packageName.equals(pkg(n))) throw new ScopedUi.Refused("inspection_incomplete");
                boolean visible = n.isVisibleToUser(); JSONObject item = null;
                if (visible) {
                    try {
                        Rect rect = new Rect(); n.getBoundsInScreen(rect); String id = Integer.toString(nextId[0]++);
                        boolean masked = secret(n); AtomicInput.EditorTicket editor = AtomicInput.capture(service, r, n);
                        item = RelayApi.json("node_id", id, "class", text(n.getClassName()), "view_id", text(n.getViewIdResourceName()),
                                "showing_hint_text", n.isShowingHintText(), "bounds", rectangle(rect), "clickable", n.isClickable(),
                                "long_clickable", n.isLongClickable(), "editable", n.isEditable(), "scrollable", n.isScrollable(),
                                "enabled", n.isEnabled(), "password", masked, "focused", n.isFocused(), "input_actions", AtomicInput.actions(n, editor != null));
                        ObservedText.put(item, "text", masked || n.isShowingHintText() ? "" : n.getText(), masked);
                        ObservedText.put(item, "description", masked ? "" : n.getContentDescription(), masked);
                        ObservedText.put(item, "hint_text", masked ? "" : n.getHintText(), masked);
                        if (n.isEditable() && n.isFocused()) item.put("editor_status", AtomicInput.editorStatus(service, r, n));
                        if (!masked && editor != null) {
                            JSONObject hint = AtomicInput.editorActionHint(service, r, n, editor);
                            if (hint != null) item.put("editor_action_hint", hint);
                        }
                        if (!masked) item.put("selection_start", n.getTextSelectionStart()).put("selection_end", n.getTextSelectionEnd());
                        SemanticNodeActions.Ticket semantics = SemanticNodeActions.metadata(n, item, masked);
                        if (export) nodes.put(id, new Target(n, identity(n), editor, semantics));
                    } catch (Exception error) { throw new ScopedUi.Refused("inspection_incomplete"); }
                }
                return new PagedTree.Description(identity(n), n.getChildCount(), visible, item);
            }
            public AccessibilityNodeInfo child(AccessibilityNodeInfo n, int index) { return n.getChild(index); }
        }, () -> commandCurrent.getAsBoolean() && authorized.getAsBoolean() && revision == expectedRevision
                && SystemClock.elapsedRealtime() - started < TREE_SCAN_MS);
    }
    private void currentSnapshot(JSONObject q, AccessibilityNodeInfo r, BooleanSupplier commandCurrent) throws Exception {
        if (!commandCurrent.getAsBoolean()) throw new ScopedUi.Refused("permission_denied");
        if (!q.getString("snapshot_id").equals(snapshot) || !q.getString("package").equals(snapshotPackage)
                || r.getWindowId() != snapshotWindow || SystemClock.elapsedRealtime() >= snapshotDeadline || pagedTree == null)
            throw new ScopedUi.Refused("fresh_snapshot_required");
    }
    private JSONObject pageResult(int offset, int limit, AccessibilityNodeInfo r) throws Exception {
        JSONObject result = pagedTree.page(offset, limit);
        return result.put("package", snapshotPackage).put("snapshot_id", snapshot).put("window_id", snapshotWindow)
                .put("display_id", android.view.Display.DEFAULT_DISPLAY).put("expires_in_ms", Math.max(0, snapshotDeadline - SystemClock.elapsedRealtime()))
                .put("screen_bounds", rectangle(bounds(r))).put("node_coordinate_space", "display_pixels")
                .put("rotation", service.getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRotation());
    }
    private JSONObject read(String expected, BooleanSupplier commandCurrent) throws Exception {
        nodes.clear(); snapshot = null; pagedTree = null;
        try {
            long started = SystemClock.elapsedRealtime(), capturedRevision = revision;
            AccessibilityNodeInfo r = root(expected); String packageName = pkg(r); int window = r.getWindowId();
            PagedTree<AccessibilityNodeInfo> first = scan(r, packageName, window, started, capturedRevision, true, commandCurrent);
            PagedTree<AccessibilityNodeInfo> repeated = scan(root(packageName), packageName, window, started, capturedRevision, false, commandCurrent);
            if (!first.same(repeated)) throw new ScopedUi.Refused("fresh_snapshot_required");
            if (root(packageName).getWindowId() != window || revision != capturedRevision) throw new ScopedUi.Refused("window_changed");
            if (!commandCurrent.getAsBoolean()) throw new ScopedUi.Refused("permission_denied");
            snapshot = java.util.UUID.randomUUID().toString().replace("-", "");
            snapshotWindow = window; snapshotPackage = packageName; snapshotDeadline = started + TTL; pagedTree = first;
            return pageResult(0, PagedTree.MAX_PAGE_NODES, r);
        } catch (Exception error) { clear(); throw error; }
    }
    private JSONObject readPage(JSONObject q, BooleanSupplier commandCurrent) throws Exception {
        try {
            validateRequest(q); AccessibilityNodeInfo r = root(q.getString("package")); currentSnapshot(q, r, commandCurrent);
            long started = SystemClock.elapsedRealtime(), capturedRevision = revision;
            PagedTree<AccessibilityNodeInfo> live = scan(r, snapshotPackage, snapshotWindow, started, capturedRevision, false, commandCurrent);
            if (!pagedTree.same(live)) throw new ScopedUi.Refused("fresh_snapshot_required");
            r = root(snapshotPackage); currentSnapshot(q, r, commandCurrent);
            if (revision != capturedRevision) throw new ScopedUi.Refused("fresh_snapshot_required");
            return pageResult(q.getInt("offset"), q.getInt("limit"), r);
        } catch (Exception error) { clear(); throw error; }
    }
    private JSONObject readNodeText(JSONObject q, BooleanSupplier commandCurrent) throws Exception {
        try {
            validateRequest(q); String packageName = q.getString("package"); AccessibilityNodeInfo r = root(packageName);
            currentSnapshot(q, r, commandCurrent); long capturedRevision = revision;
            Target target = nodes.get(q.getString("node_id"));
            if (target == null || !target.node.refresh() || !target.node.isVisibleToUser()
                    || target.node.getWindowId() != snapshotWindow || !packageName.equals(pkg(target.node))) throw new ScopedUi.Refused("stale_node");
            if (secret(target.node)) throw new ScopedUi.Refused("sensitive_input");
            if (!target.identity.equals(identity(target.node))) throw new ScopedUi.Refused("stale_node");
            String field = q.getString("field");
            CharSequence raw = switch (field) {
                case "description" -> target.node.getContentDescription();
                case "hint_text" -> target.node.getHintText();
                default -> target.node.isShowingHintText() ? "" : target.node.getText();
            };
            // Own an immutable copy before the final live identity/authorization check.
            JSONObject result = ObservedText.chunk(raw == null ? "" : raw.toString(), q.getInt("offset"), q.getInt("length"));
            if (!target.node.refresh() || secret(target.node) || !target.identity.equals(identity(target.node))) throw new ScopedUi.Refused("stale_node");
            currentSnapshot(q, root(packageName), commandCurrent);
            if (revision != capturedRevision) throw new ScopedUi.Refused("fresh_snapshot_required");
            return result.put("package", packageName).put("snapshot_id", snapshot).put("node_id", q.getString("node_id"))
                    .put("field", field).put("window_id", snapshotWindow).put("expires_in_ms", Math.max(0, snapshotDeadline - SystemClock.elapsedRealtime()));
        } catch (Exception error) { clear(); throw error; }
    }
    void execute(JSONObject request, Reply reply, BooleanSupplier commandCurrent) {
        try {
            if (activeGesture != null) throw new ScopedUi.Refused("busy");
            String action = request.getString("action");
            if (FileScopes.ACTIONS.contains(action)) {
                FileScopes.execute(service, request, () -> {
                    if (!commandCurrent.getAsBoolean()) throw new ScopedUi.Refused("permission_denied");
                    check(); noOwnerWindow();
                }, reply);
                return;
            }
            String expected = request.has("package") ? request.getString("package") : null;
            if (expected != null && !AppCatalog.allowed(expected)) throw new ScopedUi.Refused("protected_package");
            switch (action) {
                case "apps": {
                    exact(request); check(); JSONArray apps = new JSONArray();
                    for (AppCatalog.Entry app : AppCatalog.installed(service)) apps.put(RelayApi.json("package", app.packageName, "name", app.label));
                    reply.success(RelayApi.json("apps", apps)); return;
                }
                case "foreground": exact(request); reply.success(foreground()); return;
                case "system_actions": exact(request); check(); reply.success(RelayApi.json("actions", SystemActions.available(service))); return;
                case "system_action": {
                    exact(request, "name"); root(null); noOwnerWindow(); clear(); check();
                    String name = request.getString("name");
                    SystemActions.perform(service, name, commandCurrent);
                    boolean locking = name.equals("lock_screen");
                    reply.success(RelayApi.json("accepted", true, "action", action, "name", name,
                            "requires_observation", !locking, "verification", "not_observed",
                            "screen_lock_requested", locking)); return;
                }
                case "audio_state": exact(request); check(); reply.success(AudioVolume.describe(service)); return;
                case "set_volume": case "adjust_volume": {
                    AudioVolume.validate(request); root(null); noOwnerWindow(); clear();
                    reply.success(AudioVolume.perform(service, request, () -> {
                        if (!commandCurrent.getAsBoolean()) throw new ScopedUi.Refused("permission_denied");
                        check(); noOwnerWindow();
                    })); return;
                }
                case "windows": exact(request); check(); reply.success(WindowInventory.capture(service, true).describe(service)); return;
                case "read_tree":
                    if (expected == null) exact(request); else exact(request, "package");
                    reply.success(read(expected, commandCurrent)); return;
                case "read_tree_page": reply.success(readPage(request, commandCurrent)); return;
                case "read_node_text": reply.success(readNodeText(request, commandCurrent)); return;
                case "observe": exact(request); observe(reply); return;
                case "launch": case "open_url": {
                    if (action.equals("launch")) exact(request, "package"); else exact(request, "package", "url");
                    check(); Intent intent;
                    if (action.equals("launch")) intent = service.getPackageManager().getLaunchIntentForPackage(expected);
                    else intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(BrowserUrl.validate(request.getString("url"))).normalizeScheme())
                            .setPackage(expected).addCategory(Intent.CATEGORY_BROWSABLE);
                    if (intent == null) throw new ScopedUi.Refused("app_unavailable");
                    android.content.pm.ResolveInfo resolved = service.getPackageManager().resolveActivity(intent, 0);
                    if (resolved == null || resolved.activityInfo == null || !resolved.activityInfo.exported || !expected.equals(resolved.activityInfo.packageName)) throw new ScopedUi.Refused("app_unavailable");
                    intent.setComponent(new android.content.ComponentName(expected, resolved.activityInfo.name));
                    clear(); check(); CommandFence.activity(commandCurrent, service, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    reply.success(RelayApi.json("accepted", true, "action", action, "requires_observation", true)); return;
                }
                case "home": case "back": case "recents": {
                    exact(request); check(); clear();
                    // Leaving Airtap is allowed; these actions never click or approve its controls.
                    boolean accepted = CommandFence.global(commandCurrent, service, action.equals("home") ? AccessibilityService.GLOBAL_ACTION_HOME
                            : action.equals("back") ? AccessibilityService.GLOBAL_ACTION_BACK : AccessibilityService.GLOBAL_ACTION_RECENTS);
                    if (!accepted) throw new ScopedUi.Refused("action_rejected");
                    reply.success(RelayApi.json("accepted", true, "action", action, "requires_observation", true)); return;
                }
                case "tap": case "long_press": case "swipe": case "drag": case "gesture": gesture(request, reply, commandCurrent); return;
                case "click": case "long_click": case "set_text": case "scroll": case "node_action":
                case "focus": case "set_selection": case "select_all": case "copy": case "cut": case "paste":
                case "insert_text": case "delete_text": case "editor_action": nodeAction(request, reply, commandCurrent); return;
                default: throw new ScopedUi.Refused("unsupported_action");
            }
        } catch (ScopedUi.Refused failure) { reply.error(failure.code); }
        catch (Exception failure) { reply.error("invalid_request"); }
    }
    private void nodeAction(JSONObject q, Reply reply, BooleanSupplier commandCurrent) throws Exception {
        String action = q.getString("action"), packageName = q.getString("package");
        if (AtomicInput.ACTIONS.contains(action)) validateRequest(q);
        else if (action.equals("node_action")) SemanticNodeActions.validate(q);
        else if (action.equals("set_text")) exact(q, "package", "snapshot_id", "node_id", "text");
        else if (action.equals("scroll")) exact(q, "package", "snapshot_id", "node_id", "direction");
        else exact(q, "package", "snapshot_id", "node_id");
        AccessibilityNodeInfo r = root(packageName);
        if (!q.getString("snapshot_id").equals(snapshot) || !packageName.equals(snapshotPackage) || r.getWindowId() != snapshotWindow || SystemClock.elapsedRealtime() >= snapshotDeadline) throw new ScopedUi.Refused("fresh_snapshot_required");
        Target target = nodes.get(q.getString("node_id"));
        if (target == null || !target.node.refresh() || !target.node.isVisibleToUser() || !target.node.isEnabled()
                || target.node.getWindowId() != r.getWindowId() || !packageName.equals(pkg(target.node))
                || !target.identity.equals(identity(target.node))) throw new ScopedUi.Refused("stale_node");
        AccessibilityNodeInfo n = target.node;
        if (AtomicInput.ACTIONS.contains(action)) {
            noOwnerWindow(); clear(); check();
            AtomicInput.perform(service, root(packageName), n, q, target.editor, commandCurrent);
            reply.success(RelayApi.json("accepted", true, "action", action, "requires_observation", true)); return;
        }
        int operation; Bundle args = null;
        if (action.equals("node_action")) {
            noOwnerWindow();
            operation = SemanticNodeActions.operation(q, n, target.semantics); args = SemanticNodeActions.arguments(q, n);
        } else if (action.equals("set_text")) {
            String value = q.getString("text");
            // Password text remains masked in reads; writing a user-authorized value is a normal app action.
            if (!n.isEditable()) throw new ScopedUi.Refused("not_editable");
            if (value.codePointCount(0, value.length()) > 2000) throw new ScopedUi.Refused("text_too_large");
            operation = AccessibilityNodeInfo.ACTION_SET_TEXT; args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        } else if (action.equals("scroll")) {
            String direction = q.getString("direction");
            if (!direction.equals("forward") && !direction.equals("backward")) throw new ScopedUi.Refused("invalid_request");
            for (int i = 0; i < 8 && n != null && !n.isScrollable(); i++) n = n.getParent();
            if (n == null || !n.isScrollable()) throw new ScopedUi.Refused("not_scrollable");
            operation = direction.equals("forward") ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        } else {
            boolean longClick = action.equals("long_click");
            for (int i = 0; i < 8 && n != null && !(longClick ? n.isLongClickable() : n.isClickable()); i++) n = n.getParent();
            if (n == null || !(longClick ? n.isLongClickable() : n.isClickable())) throw new ScopedUi.Refused("not_actionable");
            operation = longClick ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK;
        }
        if (!n.refresh() || !n.isEnabled() || !n.isVisibleToUser() || !packageName.equals(pkg(n)) || n.getWindowId() != root(packageName).getWindowId()) throw new ScopedUi.Refused("stale_node");
        if (action.equals("node_action")) {
            // Re-check target state, advertised action and arguments after the final refresh.
            if (!target.identity.equals(identity(n))) throw new ScopedUi.Refused("stale_node");
            noOwnerWindow(); operation = SemanticNodeActions.operation(q, n, target.semantics); args = SemanticNodeActions.arguments(q, n);
        }
        clear(); check(); boolean accepted = CommandFence.node(commandCurrent, n, operation, args);
        if (!accepted) throw new ScopedUi.Refused("action_rejected");
        reply.success(RelayApi.json("accepted", true, "action", action, "requires_observation", true));
    }
    private void observe(Reply reply) {
        AccessibilityNodeInfo r = root(null); String packageName = pkg(r); int window = r.getWindowId(); Rect windowRect = bounds(r), rect = displayBounds();
        noOwnerWindow();
        int rotation = service.getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRotation();
        long started = SystemClock.elapsedRealtime(), capturedRevision = ++revision; imageId = null;
        // Capture the actual display. OEM floating windows can report unscaled node/window bounds;
        // physical input must instead use the exact visible pixels, including the keyboard.
        service.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, service.getMainExecutor(), new AccessibilityService.TakeScreenshotCallback() {
            @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer(); Bitmap hw = null, copy = null, small = null;
                try {
                    if (capturedRevision != revision) throw new ScopedUi.Refused("window_changed");
                    noOwnerWindow();
                    if (root(packageName).getWindowId() != window || !bounds(root(packageName)).equals(windowRect) || !displayBounds().equals(rect)
                            || rotation != service.getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRotation()) throw new ScopedUi.Refused("window_changed");
                    hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace()); if (hw == null) throw new IllegalStateException();
                    copy = hw.copy(Bitmap.Config.ARGB_8888, false);
                    // Reject any unexplained display scaling/cropping.
                    if (copy.getWidth() != rect.width() || copy.getHeight() != rect.height()) throw new ScopedUi.Refused("capture_geometry_mismatch");
                    int width = Math.min(720, copy.getWidth()), height = Math.max(1, Math.round((float)copy.getHeight() * width / copy.getWidth()));
                    small = Bitmap.createScaledBitmap(copy, width, height, true);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    if (!small.compress(Bitmap.CompressFormat.JPEG, 65, bytes) || bytes.size() > 360000) throw new ScopedUi.Refused("image_too_large");
                    if (SystemClock.elapsedRealtime() - started >= TTL) throw new ScopedUi.Refused("observation_expired");
                    imageId = java.util.UUID.randomUUID().toString().replace("-", ""); imagePackage = packageName; imageWindow = window;
                    imageBounds = rect; imageWindowBounds = windowRect; imageRotation = rotation; imageDeadline = started + TTL;
                    reply.success(RelayApi.json("package", packageName, "window_id", window, "display_id", android.view.Display.DEFAULT_DISPLAY,
                            "rotation", rotation, "capture_scope", "display", "window_bounds", rectangle(windowRect), "observation_id", imageId,
                            "expires_in_ms", imageDeadline - SystemClock.elapsedRealtime(), "screen_bounds", rectangle(rect), "width", width, "height", height,
                            "coordinate_space", "normalized_image", "mime_type", "image/jpeg", "image_base64", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)));
                } catch (ScopedUi.Refused failure) { if (capturedRevision == revision) imageId = null; reply.error(failure.code); }
                catch (Exception failure) { if (capturedRevision == revision) imageId = null; reply.error("screenshot_failed"); }
                finally { if (small != null && small != copy) small.recycle(); if (copy != null) copy.recycle(); if (hw != null) hw.recycle(); buffer.close(); }
            }
            @Override public void onFailure(int errorCode) {
                if (capturedRevision == revision) imageId = null;
                reply.error(errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ? "screenshot_secure_window" : "screenshot_unavailable");
            }
        });
    }
    private static double coordinate(JSONObject q, String key) throws Exception {
        Object value = q.get(key);
        if (!(value instanceof Number)) throw new ScopedUi.Refused("invalid_coordinate");
        double n = ((Number)value).doubleValue(); if (!Double.isFinite(n) || n < 0 || n >= 1) throw new ScopedUi.Refused("invalid_coordinate"); return n;
    }
    private void gesture(JSONObject q, Reply reply, BooleanSupplier commandCurrent) throws Exception {
        String action = q.getString("action"), packageName = q.getString("package");
        if (action.equals("swipe")) exact(q, "package", "observation_id", "x", "y", "end_x", "end_y", "duration_ms");
        else if (action.equals("drag")) exact(q, "package", "observation_id", "x", "y", "hold_ms", "path");
        else if (action.equals("gesture")) exact(q, "package", "observation_id", "tracks");
        else exact(q, "package", "observation_id", "x", "y");
        AccessibilityNodeInfo r = root(packageName);
        if (!q.getString("observation_id").equals(imageId) || !packageName.equals(imagePackage) || r.getWindowId() != imageWindow
                || SystemClock.elapsedRealtime() >= imageDeadline || !bounds(r).equals(imageWindowBounds) || !displayBounds().equals(imageBounds)
                || imageRotation != service.getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRotation()) throw new ScopedUi.Refused("fresh_observation_required");
        noOwnerWindow();
        GesturePlan plan;
        if (action.equals("drag")) plan = GesturePlan.fromDrag(DragPlan.parse(q));
        else if (action.equals("gesture")) plan = GesturePlan.parse(q, GestureDescription.getMaxStrokeCount());
        else {
            double x = coordinate(q, "x"), y = coordinate(q, "y"), endX = x, endY = y;
            long duration = action.equals("long_press") ? 700 : 60;
            if (action.equals("swipe")) {
                Object raw = q.get("duration_ms"); if (!(raw instanceof Integer) && !(raw instanceof Long)) throw new ScopedUi.Refused("invalid_request");
                duration = ((Number)raw).longValue(); if (duration < 100 || duration > 5000) throw new ScopedUi.Refused("invalid_request");
                endX = coordinate(q, "end_x"); endY = coordinate(q, "end_y");
            }
            plan = GesturePlan.single(x, y, endX, endY, (int)duration);
        }
        Rect capturedBounds = new Rect(imageBounds); int capturedRotation = imageRotation;
        // A long press may open the launcher's own menu/window. Keep checking
        // package, authorization, display geometry and owner surfaces, without
        // requiring the initial window ID to survive the drag itself.
        TouchGesture operation = new TouchGesture(service, plan, capturedBounds, () -> {
            if (!commandCurrent.getAsBoolean()) throw new ScopedUi.Refused("permission_denied");
            root(packageName); noOwnerWindow();
            if (!displayBounds().equals(capturedBounds) || capturedRotation != service.getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRotation())
                throw new ScopedUi.Refused("window_changed");
        }, new TouchGesture.Reply() {
            public void completed() { activeGesture = null; reply.success(RelayApi.json("accepted", true, "action", action, "requires_observation", true)); }
            public void error(String code) { activeGesture = null; reply.error(code); }
        });
        clear(); check(); activeGesture = operation; operation.start();
    }
}
