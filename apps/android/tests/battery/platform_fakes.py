"""Synthetic battery API boundaries. No physical charging or device claims."""

SOURCES = {
    "org/json/JSONObject.java": """package org.json;
import java.util.*;
public class JSONObject {
    public static final Object NULL = new Object();
    public final Map<String,Object> fields = new LinkedHashMap<>();
    public JSONObject put(String key, Object value) {
        if (value == null) fields.remove(key); else fields.put(key, value);
        return this;
    }
    public Object get(String key) {
        if (!fields.containsKey(key)) throw new IllegalArgumentException(key);
        return fields.get(key);
    }
    public boolean has(String key) { return fields.containsKey(key); }
    public int length() { return fields.size(); }
}
""",
    "org/json/JSONArray.java": """package org.json;
import java.util.*;
public class JSONArray {
    public final List<Object> values = new ArrayList<>();
    public JSONArray put(Object value) { values.add(value); return this; }
    public int length() { return values.size(); }
}
""",
    "android/content/Intent.java": """package android.content;
import java.util.*;
public class Intent {
    public static final String ACTION_BATTERY_CHANGED = "android.intent.action.BATTERY_CHANGED";
    public final Map<String,Object> extras = new HashMap<>();
    public Intent putExtra(String key, Object value) { extras.put(key, value); return this; }
    public int getIntExtra(String key, int fallback) {
        Object value = extras.get(key);
        return value instanceof Integer ? (Integer)value : fallback;
    }
}
""",
    "android/content/IntentFilter.java": """package android.content;
public class IntentFilter {
    public final String action;
    public IntentFilter(String action) { this.action = action; }
}
""",
    "android/content/Context.java": """package android.content;
public class Context {
    public Intent battery = new Intent();
    public android.os.BatteryManager manager = new android.os.BatteryManager();
    public boolean intentFailure, managerFailure;
    public int stickyReads;
    public Intent registerReceiver(Object receiver, IntentFilter filter) {
        if (receiver != null || !Intent.ACTION_BATTERY_CHANGED.equals(filter.action))
            throw new AssertionError("Read only the sticky battery intent, without a receiver.");
        stickyReads++;
        if (intentFailure) throw new SecurityException("synthetic sticky read failure");
        return battery;
    }
    public <T> T getSystemService(Class<T> type) {
        if (managerFailure) throw new IllegalStateException("synthetic service unavailable");
        return type.cast(manager);
    }
}
""",
    "android/os/BatteryManager.java": """package android.os;
import java.util.*;
public class BatteryManager {
    public static final String EXTRA_LEVEL="level", EXTRA_SCALE="scale", EXTRA_PLUGGED="plugged",
        EXTRA_STATUS="status", EXTRA_TEMPERATURE="temperature", EXTRA_VOLTAGE="voltage", EXTRA_HEALTH="health";
    public static final int BATTERY_PLUGGED_AC=1, BATTERY_PLUGGED_USB=2,
        BATTERY_PLUGGED_WIRELESS=4, BATTERY_PLUGGED_DOCK=8;
    public static final int BATTERY_STATUS_UNKNOWN=1, BATTERY_STATUS_CHARGING=2,
        BATTERY_STATUS_DISCHARGING=3, BATTERY_STATUS_NOT_CHARGING=4, BATTERY_STATUS_FULL=5;
    public static final int BATTERY_PROPERTY_CURRENT_NOW=2, BATTERY_PROPERTY_CURRENT_AVERAGE=3;
    public static final int BATTERY_HEALTH_UNKNOWN=1, BATTERY_HEALTH_GOOD=2, BATTERY_HEALTH_OVERHEAT=3,
        BATTERY_HEALTH_DEAD=4, BATTERY_HEALTH_OVER_VOLTAGE=5, BATTERY_HEALTH_UNSPECIFIED_FAILURE=6,
        BATTERY_HEALTH_COLD=7;
    public final Map<Integer,Integer> values = new HashMap<>();
    public final Set<Integer> failures = new HashSet<>();
    public int getIntProperty(int property) {
        if (failures.contains(property)) throw new IllegalStateException("synthetic unavailable property");
        return values.getOrDefault(property, Integer.MIN_VALUE);
    }
}
""",
    "io/airtap/preview/RelayApi.java": """package io.airtap.preview;
final class RelayApi {
    static org.json.JSONObject json(Object... args) {
        var out = new org.json.JSONObject();
        for (int i=0; i<args.length; i+=2) out.put((String)args[i], args[i+1]);
        return out;
    }
}
""",
}
