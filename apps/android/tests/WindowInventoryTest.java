package io.airtap.preview;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Objects;

public final class WindowInventoryTest {
    static AccessibilityNodeInfo node(String pkg, int id) { return new AccessibilityNodeInfo(pkg, id); }
    static AccessibilityWindowInfo window(int id, int display, boolean active, boolean focused, String pkg) {
        return new AccessibilityWindowInfo(id, display, active, focused, pkg == null ? null : node(pkg, id));
    }
    static WindowInventory inventory(AccessibilityWindowInfo... windows) { return WindowInventory.capture(new AccessibilityService(null, windows)); }
    static void equal(Object expected, Object actual) { if (!Objects.equals(expected, actual)) throw new AssertionError("expected " + expected + ", got " + actual); }
    static void refused(String expected, Runnable run) { try { run.run(); throw new AssertionError("accepted " + expected); } catch (ScopedUi.Refused error) { equal(expected, error.code); } }
    static void check(String name, Runnable test) { test.run(); System.out.println("PASS " + name); }
    public static void main(String[] args) {
        check("active installer root remains usable and display-bound", () -> {
            var w = window(3, 0, true, true, "com.android.packageinstaller");
            var i = WindowInventory.capture(new AccessibilityService(w.root, w));
            equal(w.root, i.root(null)); equal("active_root", i.select().source()); equal(0, w.reads);
        });
        check("Android framework resolver is a valid package with exact window identity", () -> {
            var w = window(3,0,true,true,"android");
            var service = new AccessibilityService(w.root,w);
            var i = WindowInventory.capture(service);
            equal(w.root,i.root("android")); equal("active_root",i.select().source());
            var value = WindowInventory.capture(service,true).describe(service);
            var item = (JSONObject)((JSONArray)value.get("windows")).get(0);
            equal("android",item.get("package")); equal(true,item.get("root_available"));
            equal(JSONObject.NULL,item.get("availability_error"));
            refused("not_foreground", () -> i.root("com.example.app"));
        });
        check("Android framework root uses the same active-window fallback", () -> {
            var w = window(3,0,true,true,"android"); var i = inventory(w);
            equal(w.root,i.root("android")); equal("active_window",i.select().source());
        });
        check("framework exception does not accept other single labels or malformed packages", () -> {
            for (String pkg : new String[]{"Android","androidx","system","android "," android","android\n","android..app","android/ResolverActivity",""}) {
                equal(false,AppCatalog.allowed(pkg));
                refused("window_package_unavailable", () -> inventory(window(3,0,true,true,pkg)).root(null));
            }
            for (String pkg : new String[]{BuildConfig.APPLICATION_ID,"io.airtap.preview","io.airtap.preview.future"}) equal(false,AppCatalog.allowed(pkg));
        });
        check("null active root recovers the actual active installer root", () -> {
            var w = window(3, 0, true, false, "com.android.packageinstaller"); var i = inventory(w);
            equal(w.root, i.root("com.android.packageinstaller")); equal("active_window", i.select().source());
        });
        check("unreadable installer never falls through to the browser beneath", () -> {
            var install = window(3, 0, true, false, null); var browser = window(2, 0, false, true, "com.android.browser");
            var i = inventory(install, browser); refused("window_root_unavailable", () -> i.root(null)); equal(install, i.select().entry().window());
        });
        check("active root retrieval failure is unavailable, not protected", () -> {
            var w = window(3, 0, true, true, "com.android.packageinstaller"); w.fail = true;
            refused("window_root_unavailable", () -> inventory(w).root(null));
        });
        check("unique focused default-display app is fallback only with no active window", () -> {
            var w = window(3, 0, false, true, "com.android.packageinstaller"); var i = inventory(w);
            equal(w.root, i.root(null)); equal("focused_window", i.select().source());
        });
        check("focused system or keyboard windows are not guessed as foreground apps", () -> {
            for (int type : new int[]{AccessibilityWindowInfo.TYPE_SYSTEM, AccessibilityWindowInfo.TYPE_INPUT_METHOD}) {
                var w = window(3, 0, false, true, "com.android.systemui"); w.type = type;
                refused("window_root_unavailable", () -> inventory(w).root(null));
            }
        });
        check("multiple active or focused windows produce an ambiguity error", () -> {
            for (boolean active : new boolean[]{true, false}) {
                var a = window(1, 0, active, true, "com.example.a"); var b = window(2, 0, active, true, "com.example.b");
                refused("window_selection_ambiguous", () -> inventory(a,b).root(null));
            }
        });
        check("secondary active display cannot borrow default-display pixels", () -> {
            var other = window(9, 5, true, true, "com.example.presentation"); var local = window(2, 0, false, true, "com.android.browser");
            refused("unsupported_display", () -> inventory(other, local).root(null));
            refused("unsupported_display", () -> WindowInventory.capture(new AccessibilityService(other.root,other,local)).root(null));
        });
        check("missing active-root window metadata cannot infer a default display", () -> {
            var i = WindowInventory.capture(new AccessibilityService(node("com.example.a", 20), window(2, 0, false, true, "com.android.browser")));
            refused("window_metadata_unavailable", () -> i.root(null));
        });
        check("a root/window transition cannot target the old browser", () -> {
            var browser = window(1,0,false,false,"com.android.browser"); var installer = window(2,0,true,true,"com.android.packageinstaller");
            var i = WindowInventory.capture(new AccessibilityService(browser.root,browser,installer));
            refused("window_changed", () -> i.root(null));
        });
        check("Airtap foreground is protected, with no browser fallback", () -> {
            for (String pkg : new String[]{BuildConfig.APPLICATION_ID, "io.airtap.preview", "io.airtap.preview.future"}) {
                var owner = window(3, 0, true, true, pkg); var browser = window(2,0,false,true,"com.android.browser");
                refused("protected_airtap_window", () -> inventory(owner,browser).root(null));
            }
        });
        check("package and root-window identities are validated", () -> {
            var w = window(3,0,true,true,"com.android.packageinstaller");
            refused("not_foreground", () -> inventory(w).root("com.android.browser"));
            w.root = node(null,3); refused("window_package_unavailable", () -> inventory(w).root(null));
            w.root = node("not a package",3); refused("window_package_unavailable", () -> inventory(w).root(null));
            w.root = node("com.android.packageinstaller",4); refused("window_changed", () -> inventory(w).root(null));
        });
        check("normal selection avoids fetching unrelated roots", () -> {
            var active = window(1,0,true,true,"com.example.a"); var other = window(2,0,false,false,"com.example.b");
            var service = new AccessibilityService(active.root, active, other);
            WindowInventory.capture(service).root(null); equal(0,active.reads); equal(0,other.reads);
            WindowInventory.capture(service,true).describe(service); equal(1,other.reads);
        });
        check("metadata contains no titles, texts, tickets or authorization controls", () -> {
            var owner = window(1,0,true,true,BuildConfig.APPLICATION_ID); var app = window(2,5,false,false,"com.example.a");
            var service = new AccessibilityService(owner.root,owner,app); var value = WindowInventory.capture(service,true).describe(service);
            equal(false,value.get("content_included")); equal(false,value.get("window_selection_supported")); equal("not_determined",value.get("secure_capture_state"));
            JSONArray windows = (JSONArray)value.get("windows"); JSONObject first = (JSONObject)windows.get(0);
            equal("protected",first.get("owner_surface")); equal("protected_airtap_window",first.get("availability_error"));
            equal(false,first.has("title")); equal(false,first.has("text")); equal(false,first.has("nodes"));
            equal("unsupported_display",((JSONObject)windows.get(1)).get("availability_error"));
            JSONObject selection = (JSONObject)value.get("selection"); equal("protected_airtap_window",selection.get("error"));
            JSONArray displays = (JSONArray)value.get("displays"); equal(1080,((JSONObject)displays.get(0)).get("width"));
            equal(false,((JSONObject)displays.get(1)).get("coordinate_actions_supported"));
        });
        check("inventory records absent roots without claiming secure capture detection", () -> {
            var service = new AccessibilityService(null,window(1,0,true,true,null)); var value = WindowInventory.capture(service,true).describe(service);
            var first = (JSONObject)((JSONArray)value.get("windows")).get(0);
            equal("unknown",first.get("owner_surface")); equal(JSONObject.NULL,first.get("package")); equal(false,first.get("root_available"));
            equal("window_root_unavailable",first.get("availability_error")); equal("not_determined",value.get("secure_capture_state"));
        });
        check("missing display metadata is explicit while window diagnosis remains available", () -> {
            var service = new AccessibilityService(null); service.noManager=true;
            equal(false,WindowInventory.capture(service,true).describe(service).get("display_metadata_available"));
            service.fail=true; refused("window_inventory_unavailable", () -> WindowInventory.capture(service));
        });
        check("window result bounds its metadata and marks truncation", () -> {
            var windows = new AccessibilityWindowInfo[130]; for(int n=0;n<windows.length;n++) windows[n]=window(n,0,n==0,n==0,"com.example.a");
            var service = new AccessibilityService(null,windows); var value = WindowInventory.capture(service,true).describe(service);
            equal(true,value.get("truncated")); equal(128,((JSONArray)value.get("windows")).length());
        });
        check("recovery guidance requires fresh observation and never automatically navigates back", () -> {
            String guidance=WindowInventory.nextStep("window_root_unavailable");
            equal(true,guidance.contains("fresh observe/read_tree")); equal(true,guidance.contains("no automatic Back"));
            equal(null,WindowInventory.nextStep("permission_denied"));
        });
    }
}
