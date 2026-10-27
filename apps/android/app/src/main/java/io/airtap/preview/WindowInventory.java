package io.airtap.preview;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.util.SparseArray;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Metadata only: no window titles, node text, owner controls, tickets or credentials. */
final class WindowInventory {
    record Entry(AccessibilityWindowInfo window, AccessibilityNodeInfo root) {}
    record Selection(AccessibilityNodeInfo root, Entry entry, String source, String error) {}
    final AccessibilityNodeInfo activeRoot;
    final List<Entry> entries;
    private WindowInventory(AccessibilityNodeInfo activeRoot, List<Entry> entries) {
        this.activeRoot = activeRoot; this.entries = List.copyOf(entries);
    }
    static WindowInventory capture(AccessibilityService service) {
        return capture(service, false);
    }
    static WindowInventory capture(AccessibilityService service, boolean includeOtherRoots) {
        try {
            AccessibilityNodeInfo active = service.getRootInActiveWindow();
            SparseArray<List<AccessibilityWindowInfo>> all = service.getWindowsOnAllDisplays();
            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                List<AccessibilityWindowInfo> windows = all.valueAt(i);
                if (windows == null) continue;
                for (AccessibilityWindowInfo window : windows) {
                    AccessibilityNodeInfo root = null;
                    try {
                        if (active != null && active.getWindowId() == window.getId()) root = active;
                        else if (includeOtherRoots || active == null && (window.isActive() || window.isFocused())) root = window.getRoot();
                    }
                    catch (RuntimeException unavailable) { /* Report unavailable, not a protected app category. */ }
                    entries.add(new Entry(window, root));
                }
            }
            return new WindowInventory(active, entries);
        } catch (RuntimeException failure) { throw new ScopedUi.Refused("window_inventory_unavailable"); }
    }
    static boolean ownerPackage(String name) {
        return name != null && (name.equals(BuildConfig.APPLICATION_ID) || name.equals("io.airtap.preview") || name.startsWith("io.airtap.preview."));
    }
    private static String packageName(AccessibilityNodeInfo root) {
        return root == null || root.getPackageName() == null ? null : root.getPackageName().toString();
    }
    private Selection choice(Entry entry, String source) {
        if (entry.window.getDisplayId() != Display.DEFAULT_DISPLAY) return new Selection(null, entry, source, "unsupported_display");
        if (entry.root == null) return new Selection(null, entry, source, "window_root_unavailable");
        String name = packageName(entry.root);
        if (ownerPackage(name)) return new Selection(null, entry, source, "protected_airtap_window");
        if (!AppCatalog.allowed(name)) return new Selection(null, entry, source, "window_package_unavailable");
        if (entry.root.getWindowId() != entry.window.getId()) return new Selection(null, entry, source, "window_changed");
        return new Selection(entry.root, entry, source, null);
    }
    Selection select() {
        if (activeRoot != null) {
            // The two platform reads can straddle a transition. Do not keep the old app's root
            // when fresh window metadata already identifies a different active window.
            for (Entry entry : entries) if (entry.window.isActive() && entry.window.getId() != activeRoot.getWindowId())
                return new Selection(null, entry, "active_root", "window_changed");
            for (Entry entry : entries) if (entry.window.getId() == activeRoot.getWindowId()) return choice(entry, "active_root");
            return new Selection(null, null, "active_root", "window_metadata_unavailable");
        }
        List<Entry> active = new ArrayList<>();
        for (Entry entry : entries) if (entry.window.isActive()) active.add(entry);
        // An unreadable installer/dialog must never fall through to the browser underneath.
        if (active.size() > 1) return new Selection(null, null, "active_window", "window_selection_ambiguous");
        if (active.size() == 1) return choice(active.get(0), "active_window");
        List<Entry> focused = new ArrayList<>();
        for (Entry entry : entries) if (entry.window.getDisplayId() == Display.DEFAULT_DISPLAY && entry.window.isFocused()) focused.add(entry);
        if (focused.size() > 1) return new Selection(null, null, "focused_window", "window_selection_ambiguous");
        if (focused.size() == 1 && focused.get(0).window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION)
            return choice(focused.get(0), "focused_window");
        return new Selection(null, null, "none", "window_root_unavailable");
    }
    AccessibilityNodeInfo root(String expected) {
        Selection selection = select();
        if (selection.error != null) throw new ScopedUi.Refused(selection.error);
        if (expected != null && !expected.equals(packageName(selection.root))) throw new ScopedUi.Refused("not_foreground");
        return selection.root;
    }
    private static JSONArray rect(Rect rect) { return new JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom); }
    private static String type(int type) {
        return switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION -> "application";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method";
            case AccessibilityWindowInfo.TYPE_SYSTEM -> "system";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility_overlay";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_screen_divider";
            default -> "other";
        };
    }
    JSONObject describe(AccessibilityService service) {
        Selection selected = select(); JSONArray windows = new JSONArray(); int count = 0;
        for (Entry entry : entries) {
            if (count++ >= 128) break;
            AccessibilityWindowInfo window = entry.window; String name = packageName(entry.root); Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
            boolean owner = ownerPackage(name);
            String issue = owner ? "protected_airtap_window" : entry.root == null ? "window_root_unavailable"
                    : window.getDisplayId() != Display.DEFAULT_DISPLAY ? "unsupported_display" : !AppCatalog.allowed(name) ? "window_package_unavailable"
                    : entry.root.getWindowId() != window.getId() ? "window_changed" : null;
            windows.put(RelayApi.json("window_id", window.getId(), "display_id", window.getDisplayId(), "type", type(window.getType()),
                    "active", window.isActive(), "focused", window.isFocused(), "layer", window.getLayer(), "bounds", rect(bounds),
                    "root_available", entry.root != null, "package", owner || AppCatalog.allowed(name) ? name : JSONObject.NULL,
                    "owner_surface", entry.root == null ? "unknown" : owner ? "protected" : "not_airtap",
                    "selected", selected.entry == entry && selected.error == null, "availability_error", issue == null ? JSONObject.NULL : issue));
        }
        JSONArray displays = new JSONArray(); boolean displayMetadataAvailable = true;
        try {
            DisplayManager manager = service.getSystemService(DisplayManager.class);
            if (manager == null) throw new IllegalStateException();
            for (Display display : manager.getDisplays()) {
                Point size = new Point(); display.getRealSize(size);
                displays.put(RelayApi.json("display_id", display.getDisplayId(), "width", size.x, "height", size.y,
                        "rotation", display.getRotation(), "state", display.getState(), "coordinate_actions_supported", display.getDisplayId() == Display.DEFAULT_DISPLAY));
            }
        } catch (RuntimeException unavailable) { displayMetadataAvailable = false; }
        JSONObject selection = RelayApi.json("source", selected.source, "error", selected.error == null ? JSONObject.NULL : selected.error,
                "window_id", selected.entry == null ? JSONObject.NULL : selected.entry.window.getId(),
                "display_id", selected.entry == null ? JSONObject.NULL : selected.entry.window.getDisplayId());
        String step = nextStep(selected.error); if (step != null) selection = put(selection, "next_step", step);
        return RelayApi.json("windows", windows, "displays", displays, "truncated", entries.size() > 128,
                "display_metadata_available", displayMetadataAvailable, "active_root_available", activeRoot != null,
                "selection", selection, "coordinate_action_display_id", Display.DEFAULT_DISPLAY, "window_selection_supported", false,
                "content_included", false, "secure_capture_state", "not_determined");
    }
    private static JSONObject put(JSONObject object, String key, String value) { try { return object.put(key, value); } catch (Exception impossible) { throw new IllegalStateException(impossible); } }
    static String nextStep(String code) {
        if (code == null) return null;
        return switch (code) {
            case "window_root_unavailable", "window_package_unavailable" -> "Run windows to inspect the active window, then obtain a fresh observe/read_tree after it settles. Do not reuse the underlying app's targets. Decide from the current task whether to wait, go back or reopen the authorized item; no automatic Back is performed.";
            case "window_inventory_unavailable", "window_metadata_unavailable", "window_selection_ambiguous", "window_changed" -> "Run windows again after the transition settles. Use a fresh observe/read_tree when the active default-display window is identifiable; do not guess a window or reuse old coordinates.";
            case "unsupported_display" -> "Run windows to identify the active display. This client currently operates the default display only; do not apply a secondary display's coordinates to it.";
            case "protected_airtap_window" -> "Airtap's own authorization surface is protected. Do not read or operate its controls. For an already-authorized task, normal HOME or BACK may leave it; obtain a fresh observation afterwards.";
            case "screenshot_secure_window" -> "Android refused capture of protected content. Use normal authorized app navigation or an available UI tree; do not bypass secure capture or infer hidden content.";
            default -> null;
        };
    }
}
