package io.airtap.preview;

import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ExperienceRunner extends Instrumentation {
    private final JSONArray checks = new JSONArray();
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void check(boolean condition, String name) throws Exception {
        if (!condition) throw new AssertionError(name);
        checks.put(name);
    }
    private ExperienceActivity open(RecoveryStatus state) {
        return (ExperienceActivity) startActivitySync(new Intent(getTargetContext(), ExperienceActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("state", state.name()).putExtra("active", "测试 AI"));
    }
    private void tap(String text) throws Exception {
        AccessibilityNodeInfo root = null;
        long deadline = SystemClock.uptimeMillis() + 3000;
        while (root == null && SystemClock.uptimeMillis() < deadline) {
            root = getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).getRootInActiveWindow();
            if (root == null) SystemClock.sleep(100);
        }
        if (root == null) throw new AssertionError("Fixture window unavailable");
        java.util.List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(text);
        AccessibilityNodeInfo match = null;
        for (AccessibilityNodeInfo node : matches) if (text.contentEquals(node.getText()) && node.isClickable()) {
            if (match != null) throw new AssertionError("ambiguous fixture button"); match = node;
        }
        check(match != null && match.performAction(AccessibilityNodeInfo.ACTION_CLICK), "native click: " + text);
        waitForIdleSync(); SystemClock.sleep(200);
    }
    private String clipboard(ExperienceActivity activity) {
        ClipboardManager manager = activity.getSystemService(ClipboardManager.class);
        ClipData data = manager.getPrimaryClip();
        return data == null ? "" : data.getItemAt(0).coerceToText(activity).toString();
    }
    @Override public void onStart() {
        JSONObject result = new JSONObject();
        try {
            getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            for (RecoveryStatus status : RecoveryStatus.values()) {
                ExperienceActivity activity = open(status); waitForIdleSync();
                boolean ready = status == RecoveryStatus.CONNECTED || status == RecoveryStatus.SCREEN_PREPARABLE;
                check((activity.task != null) == ready, "task affordance: " + status);
                check(!OwnerStatus.title(status, null).isBlank() && !OwnerStatus.detail(status).isBlank(), "readable state: " + status);
                runOnMainSync(activity::finish); waitForIdleSync();
            }
            ExperienceActivity activity = open(RecoveryStatus.CONNECTED); waitForIdleSync();
            runOnMainSync(() -> activity.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("fixture", "unchanged")));
            runOnMainSync(() -> activity.task.performClick()); waitForIdleSync(); SystemClock.sleep(200);
            check("unchanged".equals(clipboard(activity)), "opening example does not copy or execute");
            tap("返回"); check("unchanged".equals(clipboard(activity)), "cancel preserves clipboard");
            runOnMainSync(() -> activity.task.performClick()); waitForIdleSync(); SystemClock.sleep(200);
            tap("复制任务"); check(FirstTask.PROMPT.equals(clipboard(activity)), "explicit copy carries complete verified-outcome task");
            runOnMainSync(activity::finish);
            result.put("status", "passed").put("checks", checks);
        } catch (Throwable failure) {
            try { result.put("status", "failed").put("error", failure.toString()).put("checks", checks); } catch (Exception ignored) {}
        }
        try (java.io.FileOutputStream out = getTargetContext().openFileOutput("result.json", 0)) { out.write(result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (Exception failure) { throw new RuntimeException(failure); }
        finish(result.optString("status").equals("passed") ? -1 : 0, new Bundle());
    }
}
