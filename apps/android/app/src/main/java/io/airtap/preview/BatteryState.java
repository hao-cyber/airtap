package io.airtap.preview;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only Android battery observations; no wake, simulated state or charging controls. */
final class BatteryState {
    private static final int POWER_SOURCES = BatteryManager.BATTERY_PLUGGED_AC
            | BatteryManager.BATTERY_PLUGGED_USB | BatteryManager.BATTERY_PLUGGED_WIRELESS
            | BatteryManager.BATTERY_PLUGGED_DOCK;

    private BatteryState() {}

    private static Integer extra(Intent battery, String key) {
        if (battery == null) return null;
        try {
            int value = battery.getIntExtra(key, Integer.MIN_VALUE);
            return value == Integer.MIN_VALUE ? null : value;
        } catch (RuntimeException unavailable) { return null; }
    }

    private static Object value(Object value) { return value == null ? JSONObject.NULL : value; }

    private static Object current(BatteryManager manager, int property) {
        if (manager == null) return JSONObject.NULL;
        try {
            // Target SDK >= 28: unsupported/error is MIN_VALUE, not zero. Current is signed uA.
            int current = manager.getIntProperty(property);
            return current == Integer.MIN_VALUE ? JSONObject.NULL : current;
        } catch (RuntimeException unavailable) { return JSONObject.NULL; }
    }

    private static Object powerSources(Integer plugged) {
        if (plugged == null || plugged < 0 || (plugged & ~POWER_SOURCES) != 0) return JSONObject.NULL;
        JSONArray sources = new JSONArray();
        if ((plugged & BatteryManager.BATTERY_PLUGGED_AC) != 0) sources.put("ac");
        if ((plugged & BatteryManager.BATTERY_PLUGGED_USB) != 0) sources.put("usb");
        if ((plugged & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) sources.put("wireless");
        if ((plugged & BatteryManager.BATTERY_PLUGGED_DOCK) != 0) sources.put("dock");
        return sources;
    }

    static JSONObject describe(Context context) throws Exception {
        Intent battery = null;
        BatteryManager manager = null;
        try {
            // A null receiver reads the sticky intent without installing a subscription.
            battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (RuntimeException unavailable) { /* Other independent readings can still succeed. */ }
        try { manager = context.getSystemService(BatteryManager.class); }
        catch (RuntimeException unavailable) { /* Current readings remain explicitly unknown. */ }

        Integer level = extra(battery, BatteryManager.EXTRA_LEVEL);
        Integer scale = extra(battery, BatteryManager.EXTRA_SCALE);
        Double percent = level != null && scale != null && scale > 0 && level >= 0 && level <= scale
                ? level * 100.0 / scale : null;
        Integer plugged = extra(battery, BatteryManager.EXTRA_PLUGGED);
        Integer rawStatus = extra(battery, BatteryManager.EXTRA_STATUS);
        String status = rawStatus == null ? null : switch (rawStatus) {
            case BatteryManager.BATTERY_STATUS_CHARGING -> "charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging";
            case BatteryManager.BATTERY_STATUS_FULL -> "full";
            default -> null;
        };
        // Android considers FULL charged/charging. Net current remains a separate observation.
        Boolean charging = status == null ? null : status.equals("charging") || status.equals("full");
        Integer rawHealth = extra(battery, BatteryManager.EXTRA_HEALTH);
        // Android's health enum is a condition indicator, not remaining battery capacity percent.
        String health = rawHealth == null ? null : switch (rawHealth) {
            case BatteryManager.BATTERY_HEALTH_GOOD -> "good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat";
            case BatteryManager.BATTERY_HEALTH_DEAD -> "dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "unspecified_failure";
            case BatteryManager.BATTERY_HEALTH_COLD -> "cold";
            default -> null;
        };
        Integer temperature = extra(battery, BatteryManager.EXTRA_TEMPERATURE);
        Integer voltage = extra(battery, BatteryManager.EXTRA_VOLTAGE);
        return RelayApi.json(
                "level_percent", value(percent),
                "plugged", plugged == null || plugged < 0 ? JSONObject.NULL : plugged != 0,
                "power_sources", powerSources(plugged),
                "status", value(status),
                "is_charging", value(charging),
                "health", value(health),
                // ACTION_BATTERY_CHANGED exposes tenths of degrees Celsius and millivolts.
                "temperature_c", temperature == null ? JSONObject.NULL : temperature / 10.0,
                "voltage_mv", voltage == null || voltage <= 0 ? JSONObject.NULL : voltage,
                "current_now_ua", current(manager, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
                "current_average_ua", current(manager, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE));
    }
}
