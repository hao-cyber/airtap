package io.airtap.preview;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Android adapter. No wake flags are set in the manifest or before the command permit check. */
final class ScreenRecovery implements ScreenPreparation.Platform {
    private final PreviewService service;
    private final CompanionUi ui;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScreenPreparation preparation = new ScreenPreparation(this);
    private final Map<String, ScreenRecoveryActivity> activities = new HashMap<>();

    ScreenRecovery(PreviewService service, CompanionUi ui) { this.service = service; this.ui = ui; }
    static ScreenPreparation.State state(Context context) {
        try {
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            PowerManager power = context.getSystemService(PowerManager.class);
            if (keyguard == null || power == null) return ScreenPreparation.State.UNAVAILABLE;
            return ScreenPreparation.State.of(true, power.isInteractive(), keyguard.isKeyguardLocked(), keyguard.isDeviceLocked());
        } catch (RuntimeException failure) { return ScreenPreparation.State.UNAVAILABLE; }
    }
    void prepare(BooleanSupplier current, ScreenPreparation.Completion completion) { preparation.prepare(current, completion); }
    void cancel() { preparation.cancel(); }
    boolean attach(String token, ScreenRecoveryActivity candidate) {
        if (!preparation.permit(token)) return false;
        ScreenRecoveryActivity previous = activities.get(token);
        if (previous != null && previous != candidate) return false;
        activities.put(token, candidate);
        return true;
    }
    boolean permit(String token) { return preparation.permit(token); }
    void failed(String token, String code) { preparation.failed(token, code); }
    void detached(String token, ScreenRecoveryActivity candidate) {
        activities.remove(token, candidate);
    }
    @Override public ScreenPreparation.State state() { return state(service); }
    @Override public long now() { return SystemClock.elapsedRealtime(); }
    @Override public void later(Runnable callback, long delayMillis) { main.postDelayed(callback, delayMillis); }
    @Override public void remove(Runnable callback) { main.removeCallbacks(callback); }
    @Override public void clearObservations() { ui.clear(); }
    @Override public void launch(String token) {
        service.startActivity(new Intent(service, ScreenRecoveryActivity.class).putExtra("request", token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        | Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }
    @Override public void close(String token) {
        ScreenRecoveryActivity activity = activities.get(token);
        if (activity != null) activity.closeRecovery();
    }
    @Override public boolean recoveryWindowGone(String token) {
        // Wait for the temporary Activity to stop/destroy and the accessibility window to disappear.
        for (ScreenRecoveryActivity activity : activities.values()) if (activity.isRecoveryVisible()) return false;
        return !ui.hasRecoveryWindow();
    }
}
