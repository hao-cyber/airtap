package io.airtap.preview;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserManager;
import android.view.accessibility.AccessibilityManager;

/** Platform prerequisites only. This class never enables accessibility or grants control. */
final class SessionRecoveryGate {
    private static boolean acknowledgementFailed;
    private SessionRecoveryGate() { }

    static boolean operationConfigured(Context context) {
        try {
            UserManager user = context.getSystemService(UserManager.class);
            if (user == null || !user.isUserUnlocked()) return false;
            AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
            if (manager == null) return false;
            ComponentName wanted = new ComponentName(context, PreviewService.class);
            for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                if (wanted.equals(ComponentName.unflattenFromString(info.getId()))) return true;
            }
        } catch (RuntimeException unavailable) { /* Unknown is not permission to restart. */ }
        return false;
    }

    private static long latestUserStop(Context context, long known) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) throw new IllegalStateException("exit_history_unavailable");
        for (ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 16)) {
            if (exit.getReason() == ApplicationExitInfo.REASON_USER_REQUESTED) known = Math.max(known, exit.getTimestamp());
        }
        return known;
    }

    static synchronized boolean userStopped(Context context) {
        try {
            if (acknowledgementFailed) return true;
            SharedPreferences state = context.getSharedPreferences("session-recovery", 0);
            long previous = state.getLong("user_stop_seen", 0);
            long latest = latestUserStop(context, previous);
            // Keep the stop even after Android evicts the corresponding exit-history entry.
            if (latest > previous && !state.edit().putLong("user_stop_seen", latest).commit()) return true;
            return latest > state.getLong("user_start_ack", 0);
        } catch (RuntimeException unavailable) { return true; }
    }

    /** Called only when the owner returns to the foreground Activity, never from a service. */
    static synchronized void acknowledgeForegroundStart(Context context) {
        try {
            SharedPreferences state = context.getSharedPreferences("session-recovery", 0);
            long latest = latestUserStop(context, state.getLong("user_stop_seen", 0));
            // A failed commit may still mutate the in-memory preferences. Keep this process closed.
            acknowledgementFailed = !state.edit().putLong("user_stop_seen", latest).putLong("user_start_ack", latest).commit();
        } catch (RuntimeException unavailable) { acknowledgementFailed = true; }
    }
}
