package io.airtap.preview;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.PowerManager;

/** Visible owner stop control. Pairing persists while screen recovery is handled per command. */
public final class SessionService extends Service {
    private static final String CHANNEL = "airtap-active-session", STOP = "io.airtap.preview.STOP_SESSION";
    private static volatile SessionService instance;
    private volatile boolean foregroundReady;
    private PowerManager.WakeLock screen;
    private int generation = -1;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    static boolean notificationsAllowed(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                && manager.areNotificationsEnabled() && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }
    static void begin(Context context) {
        PreviewService control = PreviewService.instance;
        if (control == null || !control.isAuthorized()) return;
        int run = control.sessionGeneration();
        try { context.startForegroundService(new Intent(context, SessionService.class).putExtra("generation", run)); }
        catch (Exception failure) { control.notificationUnavailable(run, true); }
    }
    static boolean readyFor(PreviewService control, int run) {
        SessionService current = instance;
        return current != null && current.foregroundReady && current.generation == run
                && PreviewService.instance == control && notificationsAllowed(current);
    }
    static void end(Context context) {
        if (instance != null) instance.release();
        context.stopService(new Intent(context, SessionService.class));
    }
    private final Runnable check = new Runnable() {
        @Override public void run() {
            PreviewService control = PreviewService.instance;
            if (control == null || !control.isAuthorized() || control.sessionGeneration() != generation) { release(); stopSelf(); return; }
            if (control.notificationBlocked() || !notificationsAllowed(SessionService.this)) { control.notificationUnavailable(generation, false); return; }
            // Only a current command may wake a device; the notification service keeps a ready screen usable.
            // Non-reference-counted acquire(timeout) replaces the pending timeout without releasing.
            // Renew before expiry: waiting for !isHeld() creates a release gap every 60 seconds.
            if (control.unlocked()) {
                if (screen != null) screen.acquire(60000);
            } else if (screen != null && screen.isHeld()) screen.release();
            main.postDelayed(this, 2000);
        }
    };
    private void release() { foregroundReady = false; main.removeCallbacksAndMessages(null); if (screen != null && screen.isHeld()) screen.release(); }
    @Override public void onCreate() { super.onCreate(); instance = this; }
    @SuppressWarnings("deprecation")
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        PreviewService control = PreviewService.instance;
        if (intent != null && STOP.equals(intent.getAction())) {
            if (control != null && intent.getIntExtra("generation", -1) == control.sessionGeneration()) control.stop("已停止手机控制");
            return START_NOT_STICKY;
        }
        if (control == null || !control.isAuthorized() || intent == null || intent.getIntExtra("generation", -1) != control.sessionGeneration()) {
            // A queued old start must not tear down a newer visible session in the same Service.
            if (control == null || !readyFor(control, control.sessionGeneration())) stopSelf(startId);
            return START_NOT_STICKY;
        }
        generation = control.sessionGeneration();
        foregroundReady = false;
        if (!notificationsAllowed(this)) { control.notificationUnavailable(generation, false); return START_NOT_STICKY; }
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "我的 AI 连接", NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, SessionService.class).setAction(STOP).putExtra("generation", generation), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notice = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentTitle("我的 AI 可以使用这台手机").setContentText("收到任务时自动准备屏幕，可随时停止。")
                    .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                    .addAction(new Notification.Action.Builder(null, "停止控制", stop).build()).build();
            startForeground(701, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            foregroundReady = true;
            if (!notificationsAllowed(this)) { control.notificationUnavailable(generation, false); return START_NOT_STICKY; }
            PowerManager power = getSystemService(PowerManager.class);
            if (screen == null && power != null && power.isWakeLockLevelSupported(PowerManager.SCREEN_DIM_WAKE_LOCK)) {
                screen = power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "Airtap:TrustedPhone"); screen.setReferenceCounted(false);
            }
            main.removeCallbacks(check); main.post(check);
            control.notificationReady(generation);
        } catch (Exception failure) { control.notificationUnavailable(generation, true); }
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        boolean current = instance == this;
        release(); if (current) instance = null;
        PreviewService control = PreviewService.instance;
        if (current && control != null && control.isAuthorized() && control.sessionGeneration() == generation) control.onInterrupt();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
