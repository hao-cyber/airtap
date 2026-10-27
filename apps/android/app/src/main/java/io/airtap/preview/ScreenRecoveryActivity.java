package io.airtap.preview;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Bundle;

/** Short-lived platform screen recovery; never displays or opens Airtap's owner approval page. */
public final class ScreenRecoveryActivity extends Activity {
    static final String WINDOW_TITLE = "Airtap 正在准备屏幕";
    private ScreenRecovery recovery;
    private String token;
    private boolean requested, visible;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        token = getIntent().getStringExtra("request");
        PreviewService service = PreviewService.instance;
        if (token == null || service == null) { closeRecovery(); return; }
        recovery = service.screenRecovery();
        if (!recovery.attach(token, this) || !recovery.permit(token)) { closeRecovery(); return; }
        setTitle(WINDOW_TITLE);
        // Do not move these flags to AndroidManifest: a queued, cancelled start must not wake a device.
        setShowWhenLocked(true);
        setTurnScreenOn(true);
    }
    @Override protected void onStart() { super.onStart(); visible = true; }
    @Override protected void onResume() {
        super.onResume();
        if (recovery == null || !recovery.permit(token)) { closeRecovery(); return; }
        if (requested) return;
        requested = true;
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (keyguard == null) { recovery.failed(token, "device_state_unavailable"); return; }
        if (!keyguard.isKeyguardLocked()) return;
        // isDeviceLocked(), checked again by permit(), distinguishes authentication from a swipe screen.
        if (!recovery.permit(token)) { closeRecovery(); return; }
        try {
            keyguard.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override public void onDismissError() { recovery.failed(token, "device_keyguard_dismiss_failed"); }
                @Override public void onDismissCancelled() { recovery.failed(token, "device_keyguard_dismiss_failed"); }
                // Success is verified by state polling, never inferred from the callback alone.
            });
        } catch (RuntimeException failure) { recovery.failed(token, "device_keyguard_dismiss_failed"); }
    }
    boolean isRecoveryVisible() { return visible && !isDestroyed(); }
    void closeRecovery() {
        setTurnScreenOn(false);
        setShowWhenLocked(false);
        if (!isFinishing()) finishAndRemoveTask();
    }
    @Override protected void onStop() { visible = false; super.onStop(); }
    @Override protected void onDestroy() {
        visible = false;
        if (recovery != null && token != null) recovery.detached(token, this);
        super.onDestroy();
    }
}
