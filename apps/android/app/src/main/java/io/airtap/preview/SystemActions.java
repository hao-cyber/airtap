package io.airtap.preview;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;

/** Named, discoverable Android system actions, not arbitrary key/event injection. */
final class SystemActions {
    private static final Map<String, Integer> IDS = new LinkedHashMap<>();
    static {
        IDS.put("power_dialog", AccessibilityService.GLOBAL_ACTION_POWER_DIALOG);
        IDS.put("lock_screen", AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
        IDS.put("split_screen", AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
        IDS.put("notifications", AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
        IDS.put("quick_settings", AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
        IDS.put("dismiss_notification_shade", AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        IDS.put("all_apps", AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS);
        IDS.put("dpad_up", AccessibilityService.GLOBAL_ACTION_DPAD_UP);
        IDS.put("dpad_down", AccessibilityService.GLOBAL_ACTION_DPAD_DOWN);
        IDS.put("dpad_left", AccessibilityService.GLOBAL_ACTION_DPAD_LEFT);
        IDS.put("dpad_right", AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT);
        IDS.put("dpad_center", AccessibilityService.GLOBAL_ACTION_DPAD_CENTER);
    }
    static boolean known(String name) { return IDS.containsKey(name); }
    private static boolean available(AccessibilityService service, int id) {
        for (AccessibilityNodeInfo.AccessibilityAction action : service.getSystemActions()) if (action.getId() == id) return true;
        return false;
    }
    static JSONArray available(AccessibilityService service) {
        JSONArray result = new JSONArray();
        for (Map.Entry<String, Integer> entry : IDS.entrySet()) if (available(service, entry.getValue())) result.put(entry.getKey());
        return result;
    }
    static void perform(AccessibilityService service, String name, java.util.function.BooleanSupplier current) {
        Integer id = IDS.get(name);
        if (id == null) throw new ScopedUi.Refused("invalid_request");
        if (!available(service, id)) throw new ScopedUi.Refused("system_action_unavailable");
        if (!CommandFence.global(current, service, id)) throw new ScopedUi.Refused("action_rejected");
    }
}
