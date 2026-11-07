package io.airtap.preview;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;

/** Short-lived platform screen recovery; never displays or opens Airtap's owner approval page. */
public final class ScreenRecoveryActivity extends Activity {
    static final String WINDOW_TITLE = "Airtap 正在准备屏幕";
    private ScreenRecovery recovery;
    private String token;
    private boolean requested, visible, resumed, drawn, windowReady, focused, posted, waitingLogged;
    private int readyGeneration, failedGeneration = -1, requests;
    private int callbackGeneration;
    private View decor;
    private final Runnable dismiss = () -> { posted = false; dismissIfReady(false); };
    private final ViewTreeObserver.OnPreDrawListener beforeDraw = () -> {
        drawn = true;
        windowSignal();
        return true;
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        token = getIntent().getStringExtra("request");
        PreviewService service = PreviewService.instance;
        if (token == null || service == null) { closeRecovery(); return; }
        recovery = service.screenRecovery();
        if (!recovery.attach(token, this) || !recovery.permit(token, this)) { closeRecovery(); return; }
        setTitle(WINDOW_TITLE);
        // Do not move these flags to AndroidManifest: a queued, cancelled start must not wake a device.
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        decor = getWindow().getDecorView();
        decor.getViewTreeObserver().addOnPreDrawListener(beforeDraw);
    }
    @Override protected void onStart() { super.onStart(); visible = true; }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (recovery == null || !recovery.permit(token, this)) { closeRecovery(); return; }
        // The platform accepts an Activity that would be visible without keyguard and can
        // turn its screen on during dismissal. Requiring a drawn frame first can deadlock.
        dismissIfReady(true);
        windowSignal();
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !focused) windowReady = false;
        focused = hasFocus;
        if (hasFocus) windowSignal();
    }
    private boolean visibleWindow() {
        return resumed && visible && drawn && decor != null && decor.isAttachedToWindow()
                && decor.isShown() && decor.getWindowVisibility() == View.VISIBLE;
    }
    void progress() {
        if (!isFinishing() && !isDestroyed() && recovery.permit(token, this)) windowSignal();
    }
    void relocked() {
        if (!isFinishing() && !isDestroyed() && recovery.permit(token, this)) {
            callbackGeneration++;
            requested = false;
            failedGeneration = -1;
        }
    }
    private void windowSignal() {
        if (isFinishing() || isDestroyed() || recovery == null) return;
        if (!visibleWindow()) {
            windowReady = false;
            if (!waitingLogged) {
                waitingLogged = true;
                recovery.diagnostic(ScreenPreparation.Diagnostic.window_not_ready);
            }
            return;
        }
        if (!windowReady) { windowReady = true; readyGeneration++; }
        // A completed traversal, not onResume alone, establishes a usable visible window.
        // Focus is a readiness signal, not a requirement for a translucent recovery Activity.
        if (!posted && !requested && requests < 2 && (requests == 0 || readyGeneration > failedGeneration)) {
            posted = true;
            decor.post(dismiss);
        }
    }
    private void dismissIfReady(boolean bootstrap) {
        if (isFinishing() || isDestroyed()) return;
        if (bootstrap) {
            if (!resumed || !visible || decor == null || !recovery.canBootstrap(token, this)) return;
        } else if (!visibleWindow()) { windowReady = false; return; }
        if (requested || requests >= 2 || (requests > 0 && readyGeneration <= failedGeneration)) return;
        if (!recovery.permit(token, this)) { closeRecovery(); return; }
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (keyguard == null) { recovery.failed(token, "device_state_unavailable"); return; }
        // A transient unlocked sample must not spend the one real dismissal opportunity.
        if (!keyguard.isKeyguardLocked()) return;
        // isDeviceLocked(), checked again by permit(), distinguishes authentication from a swipe screen.
        if (!recovery.permit(token, this)) { closeRecovery(); return; }
        int reservation = recovery.reserveDismiss(token, this);
        if (reservation == 0) return;
        requested = true;
        requests = reservation;
        final int request = ++callbackGeneration;
        if (bootstrap) recovery.diagnostic(ScreenPreparation.Diagnostic.bootstrap_requested);
        recovery.diagnostic(ScreenPreparation.Diagnostic.dismiss_requested);
        try {
            keyguard.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                private boolean complete;
                private boolean accept() {
                    if (complete || request != callbackGeneration || isFinishing() || isDestroyed()) return false;
                    complete = true;
                    return recovery.permit(token, ScreenRecoveryActivity.this);
                }
                @Override public void onDismissError() {
                    if (!accept()) return;
                    recovery.diagnostic(ScreenPreparation.Diagnostic.api_error);
                    if (requests >= 2) { recovery.failed(token, "device_keyguard_dismiss_failed"); return; }
                    recovery.allowDismissRetry(token, ScreenRecoveryActivity.this);
                    requested = false;
                    failedGeneration = readyGeneration;
                    // Only a new ready-window signal permits one retry within the original deadline.
                }
                @Override public void onDismissCancelled() {
                    if (!accept()) return;
                    recovery.diagnostic(ScreenPreparation.Diagnostic.api_cancelled);
                    recovery.failed(token, "device_keyguard_dismiss_failed");
                }
                @Override public void onDismissSucceeded() {
                    if (accept()) recovery.diagnostic(ScreenPreparation.Diagnostic.api_success);
                }
                // Success is verified by state polling, never inferred from the callback alone.
            });
        } catch (RuntimeException failure) {
            recovery.diagnostic(ScreenPreparation.Diagnostic.api_exception);
            recovery.failed(token, "device_keyguard_dismiss_failed");
        }
    }
    boolean isRecoveryVisible() { return visible && !isDestroyed(); }
    void closeRecovery() {
        if (decor != null) {
            decor.removeCallbacks(dismiss);
            if (decor.getViewTreeObserver().isAlive()) decor.getViewTreeObserver().removeOnPreDrawListener(beforeDraw);
        }
        posted = false;
        setTurnScreenOn(false);
        setShowWhenLocked(false);
        if (!isFinishing()) finishAndRemoveTask();
    }
    @Override protected void onPause() {
        resumed = false;
        drawn = false;
        windowReady = false;
        focused = false;
        if (decor != null) decor.removeCallbacks(dismiss);
        posted = false;
        super.onPause();
    }
    @Override protected void onStop() { visible = false; super.onStop(); }
    @Override protected void onDestroy() {
        visible = false;
        if (recovery != null && token != null) recovery.detached(token, this);
        super.onDestroy();
    }
}
