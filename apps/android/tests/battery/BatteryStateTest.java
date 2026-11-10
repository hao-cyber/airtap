package io.airtap.preview;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class BatteryStateTest {
    interface Operation { void run() throws Exception; }
    static int count;
    static void require(boolean value) { if (!value) throw new AssertionError(); }
    static void test(String name, Operation op) throws Exception {
        op.run(); count++; System.out.println("PASS " + name);
    }
    static void unknown(JSONObject result, String... keys) {
        for (String key : keys) require(result.has(key) && result.get(key) == JSONObject.NULL);
    }
    static Context chargedContext() {
        var context = new Context();
        context.battery.putExtra(BatteryManager.EXTRA_LEVEL, 78).putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING)
                .putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_AC)
                .putExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD)
                .putExtra(BatteryManager.EXTRA_TEMPERATURE, 398).putExtra(BatteryManager.EXTRA_VOLTAGE, 4321);
        context.manager.values.put(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, 250000);
        context.manager.values.put(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE, 180000);
        return context;
    }
    static List<Object> sources(JSONObject result) { return ((JSONArray)result.get("power_sources")).values; }

    public static void main(String[] args) throws Exception {
        test("reports percentage, charger, charge state and documented measurement units", () -> {
            var context = chargedContext(); var result = BatteryState.describe(context);
            require(result.length() == 10 && result.get("level_percent").equals(78.0));
            require(result.get("health").equals("good"));
            require(result.get("plugged").equals(true) && sources(result).equals(List.of("ac")));
            require(result.get("status").equals("charging") && result.get("is_charging").equals(true));
            require(result.get("temperature_c").equals(39.8) && result.get("voltage_mv").equals(4321));
            require(result.get("current_now_ua").equals(250000) && result.get("current_average_ua").equals(180000));
            require(context.stickyReads == 1);
        });
        test("plugged is independent of charging and negative temperatures and current are retained", () -> {
            var context = chargedContext();
            context.battery.putExtra(BatteryManager.EXTRA_LEVEL, 0)
                    .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_NOT_CHARGING)
                    .putExtra(BatteryManager.EXTRA_TEMPERATURE, -45);
            context.manager.values.put(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, -120000);
            var result = BatteryState.describe(context);
            require(result.get("level_percent").equals(0.0) && result.get("plugged").equals(true));
            require(result.get("status").equals("not_charging") && result.get("is_charging").equals(false));
            require(result.get("temperature_c").equals(-4.5) && result.get("current_now_ua").equals(-120000));
        });
        test("full follows Android charged semantics without fabricating a positive current", () -> {
            var context = chargedContext();
            context.battery.putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_FULL);
            context.manager.values.put(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, 0);
            var result = BatteryState.describe(context);
            require(result.get("status").equals("full") && result.get("is_charging").equals(true));
            require(result.get("current_now_ua").equals(0));
        });
        test("known source flags include USB wireless dock and preserve combinations", () -> {
            var context = chargedContext();
            int[] values = {1, 2, 4, 8}; String[] names = {"ac", "usb", "wireless", "dock"};
            for (int i = 0; i < values.length; i++) {
                context.battery.putExtra(BatteryManager.EXTRA_PLUGGED, values[i]);
                require(sources(BatteryState.describe(context)).equals(List.of(names[i])));
            }
            context.battery.putExtra(BatteryManager.EXTRA_PLUGGED, 15);
            require(sources(BatteryState.describe(context)).equals(List.of("ac", "usb", "wireless", "dock")));
            context.battery.putExtra(BatteryManager.EXTRA_PLUGGED, 0)
                    .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING);
            var unplugged = BatteryState.describe(context);
            require(sources(unplugged).isEmpty() && unplugged.get("plugged").equals(false));
            require(unplugged.get("status").equals("discharging") && unplugged.get("is_charging").equals(false));
        });
        test("unrecognized source or status is unknown rather than falsely unplugged or charging", () -> {
            var context = chargedContext(); context.battery.putExtra(BatteryManager.EXTRA_PLUGGED, 17);
            var result = BatteryState.describe(context);
            unknown(result, "power_sources"); require(result.get("plugged").equals(true));
            context.battery.putExtra(BatteryManager.EXTRA_PLUGGED, -1);
            unknown(BatteryState.describe(context), "plugged", "power_sources");
            for (int status : new int[]{BatteryManager.BATTERY_STATUS_UNKNOWN, -1, 999}) {
                context.battery.putExtra(BatteryManager.EXTRA_STATUS, status);
                unknown(BatteryState.describe(context), "status", "is_charging");
            }
        });
        test("absent snapshot and unsupported measurements remain present JSON null fields", () -> {
            var context = new Context(); context.battery = null; context.manager = null;
            var result = BatteryState.describe(context);
            require(result.length() == 10);
            unknown(result, "level_percent", "plugged", "power_sources", "status", "is_charging",
                    "temperature_c", "voltage_mv", "current_now_ua", "current_average_ua", "health");
            unknown(BatteryState.describe(new Context()), "level_percent", "temperature_c", "voltage_mv",
                    "current_now_ua", "current_average_ua");
        });
        test("health is the reported condition enum and unknown conditions never mean good", () -> {
            var context = chargedContext();
            String[] names = {"good", "overheat", "dead", "over_voltage", "unspecified_failure", "cold"};
            for (int i = 0; i < names.length; i++) {
                context.battery.putExtra(BatteryManager.EXTRA_HEALTH, i + 2);
                require(BatteryState.describe(context).get("health").equals(names[i]));
            }
            for (int health : new int[]{BatteryManager.BATTERY_HEALTH_UNKNOWN, -1, 999}) {
                context.battery.putExtra(BatteryManager.EXTRA_HEALTH, health);
                unknown(BatteryState.describe(context), "health");
            }
        });
        test("invalid percent and voltage are unknown without overflow or clamping", () -> {
            var context = chargedContext();
            Object[][] cases = {{1,0}, {-1,100}, {101,100}, {"50",100}, {50,"100"}, {null,100}, {50,null}};
            for (Object[] row : cases) {
                context.battery.putExtra(BatteryManager.EXTRA_LEVEL, row[0]).putExtra(BatteryManager.EXTRA_SCALE, row[1]);
                unknown(BatteryState.describe(context), "level_percent");
            }
            context.battery.putExtra(BatteryManager.EXTRA_LEVEL, Integer.MAX_VALUE)
                    .putExtra(BatteryManager.EXTRA_SCALE, Integer.MAX_VALUE);
            require(BatteryState.describe(context).get("level_percent").equals(100.0));
            for (int voltage : new int[]{0,-1,Integer.MIN_VALUE}) {
                context.battery.putExtra(BatteryManager.EXTRA_VOLTAGE, voltage);
                unknown(BatteryState.describe(context), "voltage_mv");
            }
        });
        test("current support and per-property failures cannot erase independent known values", () -> {
            var context = chargedContext();
            context.manager.values.put(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, Integer.MIN_VALUE);
            unknown(BatteryState.describe(context), "current_now_ua");
            context.manager.failures.add(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
            context.manager.values.put(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, 0);
            var result = BatteryState.describe(context);
            unknown(result, "current_average_ua"); require(result.get("current_now_ua").equals(0));
            require(result.get("level_percent").equals(78.0));
        });
        test("sticky read and service failures independently preserve the other observation", () -> {
            var context = chargedContext(); context.intentFailure = true;
            var noIntent = BatteryState.describe(context);
            unknown(noIntent, "level_percent", "status", "temperature_c");
            require(noIntent.get("current_now_ua").equals(250000));
            context.intentFailure = false; context.managerFailure = true;
            var noManager = BatteryState.describe(context);
            unknown(noManager, "current_now_ua", "current_average_ua");
            require(noManager.get("level_percent").equals(78.0));
        });
        test("each request reads platform state again and never registers a live receiver", () -> {
            var context = chargedContext(); BatteryState.describe(context);
            context.battery = new Intent().putExtra(BatteryManager.EXTRA_LEVEL, 9)
                    .putExtra(BatteryManager.EXTRA_SCALE, 20).putExtra(BatteryManager.EXTRA_PLUGGED, 0);
            var result = BatteryState.describe(context);
            require(result.get("level_percent").equals(45.0) && result.get("plugged").equals(false));
            unknown(result, "status", "is_charging", "temperature_c", "voltage_mv");
            require(context.stickyReads == 2);
        });
        System.out.println("TOTAL " + count);
    }
}
