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
    private PowerManager.WakeLock wakeLock;
    private String wakeToken;
    private String detachedToken;

    ScreenRecovery(PreviewService service, CompanionUi ui) { this.service = service; this.ui = ui; }
    static ScreenPreparation.State state(Context context) {
        try {
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            PowerManager power = context.getSystemService(PowerManager.class);
            if (keyguard == null || power == null) return ScreenPreparation.State.UNAVAILABLE;
            return ScreenPreparation.State.of(true, power.isInteractive(), keyguard.isKeyguardLocked(), authenticationRequired(keyguard));
        } catch (RuntimeException failure) { return ScreenPreparation.State.UNAVAILABLE; }
    }
    static boolean authenticationRequired(KeyguardManager keyguard) {
        if (keyguard.isDeviceLocked()) return true;
        // isDeviceLocked/isDeviceSecure exclude SIM locks. A secure keyguard without
        // a device credential is a locked SIM, not a swipe screen that we may dismiss.
        return keyguard.isKeyguardLocked() && keyguard.isKeyguardSecure() && !keyguard.isDeviceSecure();
    }

    void prepare(BooleanSupplier current, ScreenPreparation.Completion completion) { preparation.prepare(current, completion); }
    void cancel() { preparation.cancel(); }
    boolean attach(String token, ScreenRecoveryActivity candidate) {
        if (!preparation.permit(token)) return false;
        ScreenRecoveryActivity previous = activities.get(token);
        if (previous != null && previous != candidate && !previous.isFinishing() && !previous.isDestroyed()) return false;
        activities.put(token, candidate);
        if (token.equals(detachedToken)) detachedToken = null;
        return true;
    }
    boolean permit(String token) { return preparation.permit(token); }
    boolean permit(String token, ScreenRecoveryActivity candidate) {
        return activities.get(token) == candidate && preparation.permit(token);
    }
    boolean canBootstrap(String token, ScreenRecoveryActivity candidate) {
        return activities.get(token) == candidate && preparation.canBootstrap(token);
    }
    void allowDismissRetry(String token, ScreenRecoveryActivity candidate) {
        if (activities.get(token) == candidate) preparation.allowDismissRetry(token);
    }
    int reserveDismiss(String token, ScreenRecoveryActivity candidate) {
        return activities.get(token) == candidate ? preparation.reserveDismiss(token) : 0;
    }
    void failed(String token, String code) { preparation.failed(token, code); }
    void detached(String token, ScreenRecoveryActivity candidate) {
        if (activities.remove(token, candidate) && preparation.permit(token)) detachedToken = token;
    }
    @Override public ScreenPreparation.State state() { return state(service); }
    @Override public long now() { return SystemClock.elapsedRealtime(); }
    @Override public void later(Runnable callback, long delayMillis) { main.postDelayed(callback, delayMillis); }
    @Override public void remove(Runnable callback) { main.removeCallbacks(callback); }
    @Override public void clearObservations() { ui.clear(); }
    @Override public void progress(String token) {
        ScreenRecoveryActivity activity = activities.get(token);
        if (activity != null) activity.progress();
    }
    @Override public void relocked(String token) {
        ScreenRecoveryActivity activity = activities.get(token);
        if (activity != null) activity.relocked();
    }
    @SuppressWarnings("deprecation")
    @Override public void wakeFallback(String token, long remainingMillis) {
        // Some devices leave a correctly launched turnScreenOn Activity stopped while dozing.
        // Prefer that Activity first; use this public wake API once, within the same permit/deadline.
        if (remainingMillis <= 0 || !preparation.permit(token) || wakeLock != null) return;
        PowerManager power = service.getSystemService(PowerManager.class);
        if (power == null || power.isInteractive() || !preparation.permit(token)) return;
        PowerManager.WakeLock candidate = null;
        try {
            candidate = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "Airtap:screen-preparation");
            candidate.setReferenceCounted(false);
            if (!preparation.permit(token)) return;
            candidate.acquire(Math.min(ScreenPreparation.TIMEOUT_MS, remainingMillis));
            wakeLock = candidate; wakeToken = token;
            android.util.Log.i("AirtapPreview", "event=screen_recovery branch=wake_fallback_requested");
        } catch (RuntimeException unavailable) {
            // A platform refusal is not a reason to bypass permission checks or replay the action.
            try { if (candidate != null && candidate.isHeld()) candidate.release(); }
            catch (RuntimeException ignored) { /* The native timeout remains bounded. */ }
            android.util.Log.i("AirtapPreview", "event=screen_recovery branch=wake_fallback_rejected");
        }
    }
    @Override public void releaseWake(String token) {
        if (token.equals(detachedToken)) detachedToken = null;
        if (!token.equals(wakeToken)) return;
        PowerManager.WakeLock held = wakeLock; wakeLock = null; wakeToken = null;
        try { if (held != null && held.isHeld()) held.release(); }
        catch (RuntimeException ignored) { /* Do not mask the command outcome; native timeout remains. */ }
    }
    @Override public void diagnostic(ScreenPreparation.Diagnostic branch) {
        preparation.recordDiagnostic(branch);
        // Fixed local metadata only; never include a token, system message or screen content.
        try { android.util.Log.i("AirtapPreview", "event=screen_recovery branch=" + branch); }
        catch (RuntimeException ignored) { /* Diagnostics cannot affect recovery. */ }
    }
    @Override public String windowDiagnostic(String token) {
        ScreenRecoveryActivity activity = activities.get(token);
        return activity == null ? "absent" : activity.windowDiagnostic();
    }
    @Override public boolean recoveryStopped(String token) {
        ScreenRecoveryActivity activity = activities.get(token);
        return activity == null ? token.equals(detachedToken) : activity.isRecoveryStopped();
    }
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
