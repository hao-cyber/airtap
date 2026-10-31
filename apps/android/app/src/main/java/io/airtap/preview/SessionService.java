package io.airtap.preview;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.IBinder;
import android.os.PowerManager;

/** Visible, stoppable recovery carrier. A foreground notification is not a control grant. */
public final class SessionService extends Service {
    private static final String CHANNEL = "airtap-active-session", STOP = "io.airtap.preview.STOP_SESSION";
    private static final String RESTORE = "io.airtap.preview.RESTORE_SESSION", READY = "io.airtap.preview.READY_SESSION";
    private static final String FENCE = "grant_fence";
    private static volatile SessionService instance;
    private volatile boolean foregroundReady, executionReady;
    private PowerManager.WakeLock screen;
    private int generation = -1;
    private String grantFence;
    private EnrollmentStore.Config config;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    static boolean notificationsAllowed(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return false;
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                && manager.areNotificationsEnabled() && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }
    static boolean ensureRestoring(Context context) throws Exception {
        if (!notificationsAllowed(context) || !SessionRecoveryGate.operationConfigured(context) || SessionRecoveryGate.userStopped(context)) return false;
        // Storage errors must reach the caller as storage errors, preserving the original grant.
        EnrollmentStore.Config identity = new EnrollmentStore(context).read();
        String fence = new TrustedGrant(context).sessionFence(identity);
        if (fence == null) return false;
        SessionService current = instance;
        if (current != null && current.foregroundReady && fence.equals(current.grantFence)) return true;
        try {
            context.startForegroundService(new Intent(context, SessionService.class).setAction(RESTORE).putExtra(FENCE, fence));
            return true;
        } catch (Exception failure) { logFailure("restore_start", failure); return false; }
    }
    static void begin(Context context) {
        PreviewService control = PreviewService.instance;
        if (control == null || !control.isAuthorized() || control.sessionFence() == null) return;
        int run = control.sessionGeneration();
        try {
            context.startForegroundService(new Intent(context, SessionService.class).setAction(READY)
                    .putExtra("generation", run).putExtra(FENCE, control.sessionFence()));
        } catch (Exception failure) { logFailure("ready_start", failure); control.notificationUnavailable(run, true); }
    }
    static boolean readyFor(PreviewService control, int run) {
        SessionService current = instance;
        return current != null && current.foregroundReady && current.executionReady && current.generation == run
                && current.grantFence != null && current.grantFence.equals(control.sessionFence())
                && PreviewService.instance == control && notificationsAllowed(current);
    }
    static void end(Context context) {
        if (instance != null) instance.release();
        context.stopService(new Intent(context, SessionService.class));
    }
    static void paused(Context context) {
        SessionService current = instance;
        if (current == null || !current.foregroundReady) return;
        current.executionReady = false; current.generation = -1; current.releaseScreen();
        try { current.showNotification(false); }
        catch (Exception failure) { logFailure("restore_notice", failure); current.halt(); }
    }
    private static void logFailure(String stage, Exception failure) {
        // Fixed classifications only: exception messages may contain device or connection data.
        String kind = failure instanceof android.app.ForegroundServiceStartNotAllowedException ? "background_start_refused"
                : failure instanceof SecurityException ? "permission_refused" : "unavailable";
        android.util.Log.w("AirtapRecovery", "stage=" + stage + " failure=" + kind);
    }
    private void releaseScreen() { if (screen != null && screen.isHeld()) screen.release(); }
    private void release() { foregroundReady = false; executionReady = false; generation = -1; main.removeCallbacksAndMessages(null); releaseScreen(); }
    private void halt() { release(); stopSelf(); }

    private final Runnable check = new Runnable() {
        @Override public void run() {
            PreviewService control = PreviewService.instance;
            try {
                if (!new TrustedGrant(SessionService.this).isCurrent(config, grantFence)
                        || !SessionRecoveryGate.operationConfigured(SessionService.this) || SessionRecoveryGate.userStopped(SessionService.this)) {
                    halt(); if (control != null) control.sessionCarrierUnavailable(); return;
                }
                if (!notificationsAllowed(SessionService.this)) {
                    halt(); if (control != null) control.notificationUnavailable(control.sessionGeneration(), false); return;
                }
                if (control != null && control.notificationBlocked()) {
                    control.notificationUnavailable(control.sessionGeneration(), false); return;
                }
                if (control != null && readyFor(control, control.sessionGeneration()) && control.isAuthorized() && !control.notificationBlocked()) {
                    // Restoring never acquires a screen lock; only the validated ready session may retain an already-ready screen.
                    if (control.unlocked()) { if (screen != null) screen.acquire(60000); }
                    else releaseScreen();
                } else {
                    if (executionReady) paused(SessionService.this);
                    releaseScreen();
                }
                if(control!=null)control.restore(); // Also continues owner-approved multi-grant completion while another grant is connected.
                if (foregroundReady) main.postDelayed(this, 2000);
            } catch (Exception failure) {
                logFailure("grant_read", failure); halt();
                if (control != null) control.storageUnavailable();
            }
        }
    };

    static Intent stopIntent(Context context, String fence) {
        return new Intent(context, SessionService.class).setAction(STOP)
                .setData(Uri.parse("airtap-session://stop/" + fence)).putExtra(FENCE, fence);
    }
    private int rejected(int startId) {
        if (foregroundReady) return START_STICKY; // An old delivery must not change the current session's restart policy.
        stopSelf(startId); return START_NOT_STICKY;
    }
    private int stopRequested(Intent intent, int startId) {
        String expected = intent.getStringExtra(FENCE);
        try {
            EnrollmentStore.Config identity = new EnrollmentStore(this).read();
            if (new TrustedGrant(this).stopIfCurrent(identity, expected)) {
                PreviewService control = PreviewService.instance;
                if (control != null) control.stoppedLocally(expected);
                if (expected != null && expected.equals(grantFence)) halt();
            }
        } catch (Exception failure) {
            // Do not report durable success when both persistence operations failed.
            logFailure("stop_persistence", failure); halt();
            PreviewService control = PreviewService.instance;
            if (control != null) control.storageUnavailable();
        }
        return rejected(startId);
    }
    private void showNotification(boolean ready) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "我的 AI 连接", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // Extras are not PendingIntent identity. The durable fence also belongs in the data URI.
        PendingIntent stop = PendingIntent.getService(this, 2, stopIntent(this, grantFence), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notice = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(ready ? "我的 AI 可以使用这台手机" : "正在恢复原 AI 连接")
                .setContentText(ready ? "收到任务时自动准备屏幕，可随时停止。" : "正在等待系统服务并核实原授权，尚不能操作手机。")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "停止控制", stop).build()).build();
        startForeground(701, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        foregroundReady = true;
    }
    @Override public void onCreate() { super.onCreate(); instance = this; }
    @SuppressWarnings("deprecation")
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) return stopRequested(intent, startId);
        if (intent != null && !RESTORE.equals(intent.getAction()) && !READY.equals(intent.getAction())) return rejected(startId);
        PreviewService control = PreviewService.instance;
        try {
            EnrollmentStore.Config identity = new EnrollmentStore(this).read();
            String currentFence = new TrustedGrant(this).sessionFence(identity);
            if (currentFence == null || (intent != null && !currentFence.equals(intent.getStringExtra(FENCE)))) return rejected(startId);
            if (!notificationsAllowed(this) || !SessionRecoveryGate.operationConfigured(this) || SessionRecoveryGate.userStopped(this)) {
                halt(); if (control != null) control.sessionCarrierUnavailable(); return START_NOT_STICKY;
            }
            boolean ready = intent != null && READY.equals(intent.getAction()) && control != null && control.isAuthorized()
                    && currentFence.equals(control.sessionFence()) && intent.getIntExtra("generation", -1) == control.sessionGeneration();
            // Do not let a stale READY delivery demote an already-current foreground session.
            if (intent != null && READY.equals(intent.getAction()) && !ready) return rejected(startId);
            config = identity; grantFence = currentFence;
            if (!ready && foregroundReady && executionReady && control != null && readyFor(control, control.sessionGeneration())) return START_STICKY;
            executionReady = false; generation = -1; releaseScreen();
            // Never wait for HTTP or accessibility binding before entering foreground.
            try { showNotification(ready); }
            catch (Exception failure) {
                logFailure("foreground_notice", failure); halt();
                if (control != null) control.notificationUnavailable(control.sessionGeneration(), true);
                return START_NOT_STICKY;
            }
            if (!new TrustedGrant(this).isCurrent(config, grantFence) || !notificationsAllowed(this)) {
                halt(); if (control != null) control.sessionCarrierUnavailable(); return START_NOT_STICKY;
            }
            if (ready) {
                generation = control.sessionGeneration(); executionReady = true;
                PowerManager power = getSystemService(PowerManager.class);
                if (screen == null && power != null && power.isWakeLockLevelSupported(PowerManager.SCREEN_DIM_WAKE_LOCK)) {
                    screen = power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "Airtap:TrustedPhone"); screen.setReferenceCounted(false);
                }
                control.notificationReady(generation);
            } else if (control != null && control.isAuthorized()) control.onInterrupt();
            android.util.Log.i("AirtapRecovery", "event=" + (ready ? "ready" : "restoring")
                    + " source=" + (intent == null ? "system_restart" : "explicit_start"));
            main.removeCallbacks(check); main.post(check);
            return START_STICKY;
        } catch (Exception failure) {
            logFailure("session_start", failure); halt();
            if (control != null) control.storageUnavailable();
            return START_NOT_STICKY;
        }
    }
    @Override public void onDestroy() {
        boolean current = instance == this;
        release(); if (current) instance = null;
        PreviewService control = PreviewService.instance;
        if (current && control != null && control.isAuthorized()) control.sessionCarrierUnavailable();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
