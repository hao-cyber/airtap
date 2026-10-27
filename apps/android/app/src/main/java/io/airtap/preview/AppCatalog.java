package io.airtap.preview;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import java.util.LinkedHashMap;
import java.util.List;

/** Owner-selected launchable apps; application category never grants or denies a session. */
final class AppCatalog {
    static final class Entry {
        final String packageName;
        final String label;
        Entry(String packageName, String label) { this.packageName = packageName; this.label = label; }
    }

    /** Syntax and Airtap's authorization surface only; this does not establish installation or a grant. */
    static boolean allowed(String packageName) {
        return packageName != null && packageName.length() <= 200
                && (packageName.equals("android") || packageName.matches("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+"))
                && !packageName.equals(BuildConfig.APPLICATION_ID)
                && !packageName.equals("io.airtap.preview") && !packageName.startsWith("io.airtap.preview.");
    }

    static List<Entry> installed(Context context) {
        PackageManager manager = context.getPackageManager();
        LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> matches = manager.queryIntentActivities(query, 0);
        if (matches == null) return new ArrayList<>();
        for (ResolveInfo match : matches) {
            ActivityInfo activity = match == null ? null : match.activityInfo;
            if (!launchable(activity) || !allowed(activity.packageName)
                    || entries.containsKey(activity.packageName) || !installed(context, activity.packageName)) continue;
            try {
                CharSequence label = manager.getApplicationLabel(activity.applicationInfo);
                entries.put(activity.packageName, new Entry(activity.packageName,
                        label == null || label.length() == 0 ? activity.packageName : label.toString()));
            } catch (RuntimeException unavailable) {
                // An app may disappear or change while the local picker is being built.
            }
        }
        List<Entry> result = new ArrayList<>(entries.values());
        result.sort(java.util.Comparator.comparing(entry -> entry.packageName));
        return result;
    }

    static boolean installed(Context context, String packageName) {
        if (!allowed(packageName) || packageName.equals(context.getPackageName())) return false;
        try {
            PackageManager manager = context.getPackageManager();
            Intent launch = manager.getLaunchIntentForPackage(packageName);
            if (launch == null || launch.getComponent() == null
                    || !packageName.equals(launch.getComponent().getPackageName())) return false;
            ResolveInfo resolved = manager.resolveActivity(launch, 0);
            return resolved != null && launchable(resolved.activityInfo)
                    && packageName.equals(resolved.activityInfo.packageName);
        } catch (RuntimeException unavailable) { return false; }
    }

    private static boolean launchable(ActivityInfo activity) {
        return activity != null && activity.name != null && activity.exported && activity.enabled
                && activity.applicationInfo != null && activity.applicationInfo.enabled;
    }
}
