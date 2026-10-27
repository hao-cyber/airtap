package io.airtap.preview;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/** Local, best-effort metadata only. No endpoint, credentials, payloads or raw messages. */
final class PreviewDiagnostics {
    enum Event {
        accessibility_connected, accessibility_interrupt, accessibility_destroy,
        authorize, stop, connect_wait_network, connect_attempt, socket_open,
        socket_closing, socket_closed, socket_failure, socket_cancel, socket_close_requested,
        disconnected, reconnect_scheduled, network_observe, network_available,
        network_lost, network_capabilities, network_reset,
        test_create, test_resume, test_pause, test_destroy,
        session_begin, session_end_requested, session_start, session_foreground,
        session_notification_stop, session_start_without_grant, session_destroy
    }

    enum Reason {
        none, other, empty, network_change, stale_callback,
        accessibility_interrupted, accessibility_destroyed, new_authorization,
        notification_required, grant_expired, invalid_grant, relay_denied,
        new_pairing, local_stop, revoke_requested, relay_change,
        notification_disabled, notification_channel_disabled, notification_start_failed,
        notification_stop, foreground_service_ended, test_finished, missing_grant,
        screen_locked, screen_unsupported, screen_hold_failed, screen_hold_ended,
        expired, revoked, connection_replaced, expired_outcome_unknown, timeout,
        device_disconnected_outcome_unknown, expired_or_revoked, duplicate_hello,
        unsolicited_response, response_too_large, invalid_json_response,
        invalid_response, invalid_image, device_error,
        request_too_large, text_requests_only, invalid_id, invalid_request
    }

    enum FailureKind { none, timeout, dns, connect, tls, protocol, eof, socket, io, other }

    static void event(Context context, Event event) {
        event(context, event, Reason.none, 0, -1, -1);
    }

    static void event(Context context, Event event, Reason reason, int code, int run, int socketMatch) {
        record(context, event, reason, code, run, socketMatch, FailureKind.none, FailureKind.none, 0);
    }

    static void failure(Context context, Throwable failure, int status, int run, int socketMatch) {
        Throwable cause = failure == null ? null : failure.getCause();
        int errno = cause instanceof android.system.ErrnoException ? ((android.system.ErrnoException) cause).errno : 0;
        record(context, Event.socket_failure, Reason.none, status, run, socketMatch,
                kind(failure), kind(cause), errno);
    }

    private static void record(Context context, Event event, Reason reason, int code, int run, int socketMatch,
                               FailureKind failure, FailureKind cause, int errno) {
        try {
            StringBuilder line = new StringBuilder("event=").append(event)
                    .append(" elapsed_ms=").append(SystemClock.elapsedRealtime())
                    .append(" reason=").append(reason).append(" code=").append(code)
                    .append(" callback_generation=").append(run).append(" socket_match=").append(socketMatch)
                    .append(" failure=").append(failure).append(" cause=").append(cause).append(" errno=").append(errno);
            PreviewService control = PreviewService.instance;
            line.append(control == null ? " control_service=false" : " control_service=true " + control.diagnosticFields());
            line.append(" test_resumed=").append(TestActivity.resumed);
            if (context instanceof Activity) {
                Activity activity = (Activity) context;
                line.append(" finishing=").append(activity.isFinishing())
                        .append(" changing_configuration=").append(activity.isChangingConfigurations());
            }
            try {
                ActivityManager.RunningAppProcessInfo process = new ActivityManager.RunningAppProcessInfo();
                ActivityManager.getMyMemoryState(process);
                ActivityManager manager = context.getSystemService(ActivityManager.class);
                line.append(" process_importance=").append(process.importance)
                        .append(" app_foreground=").append(process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
                        .append(" background_restricted=").append(manager == null ? "unavailable" : Boolean.toString(manager.isBackgroundRestricted()));
            } catch (RuntimeException ignored) { line.append(" process_state=unavailable"); }
            try {
                PowerManager power = context.getSystemService(PowerManager.class);
                if (power == null) line.append(" power_state=unavailable");
                else line.append(" interactive=").append(power.isInteractive())
                        .append(" power_save=").append(power.isPowerSaveMode())
                        .append(" device_idle=").append(power.isDeviceIdleMode())
                        .append(" battery_exempt=").append(power.isIgnoringBatteryOptimizations(context.getPackageName()));
            } catch (RuntimeException ignored) { line.append(" power_state=unavailable"); }
            try {
                ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
                Network active = manager == null ? null : manager.getActiveNetwork();
                NetworkCapabilities cap = active == null ? null : manager.getNetworkCapabilities(active);
                line.append(" network_available=").append(active != null)
                        .append(" network_validated=").append(cap != null && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                        .append(" network_internet=").append(cap != null && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                        .append(" transport=").append(transport(cap));
            } catch (RuntimeException ignored) { line.append(" network_state=unavailable"); }
            Log.i("AirtapPreview", line.toString());
        } catch (RuntimeException ignored) { /* Diagnostics cannot change control behavior. */ }
    }

    private static String transport(NetworkCapabilities cap) {
        if (cap == null) return "offline";
        if (cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "vpn";
        if (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
        if (cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
        if (cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
        return "other";
    }

    private static FailureKind kind(Throwable value) {
        if (value == null) return FailureKind.none;
        if (value instanceof java.net.SocketTimeoutException) return FailureKind.timeout;
        if (value instanceof java.net.UnknownHostException) return FailureKind.dns;
        if (value instanceof java.net.ConnectException) return FailureKind.connect;
        if (value instanceof javax.net.ssl.SSLException) return FailureKind.tls;
        if (value instanceof java.net.ProtocolException) return FailureKind.protocol;
        if (value instanceof java.io.EOFException) return FailureKind.eof;
        if (value instanceof java.net.SocketException) return FailureKind.socket;
        if (value instanceof java.io.IOException) return FailureKind.io;
        return FailureKind.other;
    }

    static Reason closeReason(String reason) {
        if (reason == null || reason.isEmpty()) return Reason.empty;
        switch (reason) {
            case "expired": return Reason.expired;
            case "new_pairing": return Reason.new_pairing;
            case "revoked": return Reason.revoked;
            case "connection_replaced": return Reason.connection_replaced;
            case "expired_outcome_unknown": return Reason.expired_outcome_unknown;
            case "timeout": return Reason.timeout;
            case "device_disconnected_outcome_unknown": return Reason.device_disconnected_outcome_unknown;
            case "expired_or_revoked": return Reason.expired_or_revoked;
            case "duplicate_hello": return Reason.duplicate_hello;
            case "unsolicited_response": return Reason.unsolicited_response;
            case "response_too_large": return Reason.response_too_large;
            case "invalid_json_response": return Reason.invalid_json_response;
            case "invalid_response": return Reason.invalid_response;
            case "invalid_image": return Reason.invalid_image;
            case "device_error": return Reason.device_error;
            default: return Reason.other;
        }
    }

    static Reason stopReason(String message) {
        if (message == null) return Reason.other;
        switch (message) {
            case "无障碍服务中断": return Reason.accessibility_interrupted;
            case "无障碍服务已停止": return Reason.accessibility_destroyed;
            case "正在启动已批准的会话": return Reason.new_authorization;
            case "需要允许会话通知后才能启动授权": return Reason.notification_required;
            case "会话授权到期，请重新配对": return Reason.grant_expired;
            case "会话无效或已过期，请重新配对": return Reason.invalid_grant;
            case "中继拒绝授权，请重新配对": return Reason.relay_denied;
            case "正在创建新的配对邀请…": return Reason.new_pairing;
            case "已停止本机授权；重新连接需创建新邀请并批准。":
            case "已由手机停止授权；重新连接需新邀请和批准。": return Reason.local_stop;
            case "已本地停止，正在向中继撤销…": return Reason.revoke_requested;
            case "已停止本机控制，正在尝试撤销旧中继…": return Reason.relay_change;
            case "会话通知被关闭，手机授权已停止": return Reason.notification_disabled;
            case "会话通知渠道被关闭，请允许通知后重新授权": return Reason.notification_channel_disabled;
            case "无法保持可见会话通知，手机授权已停止": return Reason.notification_start_failed;
            case "无法保持会话通知或亮屏，手机授权已停止": return Reason.notification_start_failed;
            case "手机已息屏或锁屏，会话授权已结束；请解锁后重新核码批准": return Reason.screen_locked;
            case "这台手机不支持会话期间保持亮屏，手机授权已停止": return Reason.screen_unsupported;
            case "无法保持本次会话亮屏，手机授权已停止": return Reason.screen_hold_failed;
            case "会话亮屏保持已结束，手机授权已停止": return Reason.screen_hold_ended;
            case "已通过常驻通知停止手机授权": return Reason.notification_stop;
            case "会话前台服务已结束，授权已停止": return Reason.foreground_service_ended;
            case "测试页已关闭，手机授权已停止": return Reason.test_finished;
            case "没有本机授权，请创建新邀请并批准。": return Reason.missing_grant;
            default: return Reason.other;
        }
    }
}
