package io.airtap.preview;

import android.accessibilityservice.AccessibilityService;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** One explicitly trusted AI, durable across process and network recovery. */
public final class PreviewService extends AccessibilityService {
    static PreviewService instance;
    private static final Pattern ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final long HANDSHAKE_TIMEOUT_MS = 30000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random jitter = new Random();
    private final OkHttpClient client = TlsPolicy.newClientBuilder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
    private final CompanionUi ui = new CompanionUi(this, this::executionAllowed);
    private final ScreenRecovery recovery = new ScreenRecovery(this, ui);
    private WebSocket socket;
    private java.util.concurrent.atomic.AtomicBoolean transportOpen = new java.util.concurrent.atomic.AtomicBoolean(false);
    private boolean authorized, connected, restoring;
    private volatile boolean notificationBlocked;
    private long commandSerial, activeCommand;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Network currentNetwork;
    private Runnable reconnectTask, handshakeTask;
    private int generation, retry, connections, attempts;
    private String url, token;
    private String state = "等待连接自己的 AI";
    private String lastDiagnostic = "";
    private RecoveryStatus recoveryStatus = RecoveryStatus.WAITING_FOR_AI;
    private int notificationFailures;
    private EnrollmentStore.Config config;

    @Override public android.accessibilityservice.InputMethod onCreateInputMethod() { return new GuardedInputMethod(this); }
    @Override protected void onServiceConnected() { instance = this; restore(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) ui.clear();
    }
    @Override public void onInterrupt() {
        suspend("系统暂时中断服务，等待恢复");
        recoveryStatus = RecoveryStatus.VERIFYING_CONNECTION;
        scheduleRestore(generation, 2000);
    }
    @Override public void onDestroy() {
        suspend("服务已暂停，系统恢复后重新连接");
        if (instance == this) instance = null;
        client.dispatcher().executorService().shutdown(); client.connectionPool().evictAll(); super.onDestroy();
    }
    String statusText() {
        String screen = !authorized ? "" : switch (ui.screenState()) {
            case READY -> "";
            case SCREEN_OFF -> " · 收到任务时自动亮屏";
            case DISMISSIBLE_KEYGUARD -> " · 收到任务时自动退出锁屏";
            case AUTHENTICATION_REQUIRED -> " · 系统需要身份验证，配对已保留";
            case UNAVAILABLE -> " · 暂时无法读取屏幕状态";
        };
        return state + screen + "\n" + lastDiagnostic;
    }
    String diagnosticFields() { return "connections=" + connections + " attempts=" + attempts + " authorized=" + authorized + " connected=" + connected; }
    RecoveryStatus recoveryStatus() {
        if (authorized && !SessionService.notificationsAllowed(this)) return RecoveryStatus.NOTIFICATIONS_REQUIRED;
        if (authorized && notificationBlocked) return RecoveryStatus.VERIFYING_CONNECTION;
        if (!connected || !executionAllowed()) return recoveryStatus;
        return switch (ui.screenState()) {
            case READY -> RecoveryStatus.CONNECTED;
            case SCREEN_OFF, DISMISSIBLE_KEYGUARD -> RecoveryStatus.SCREEN_PREPARABLE;
            case AUTHENTICATION_REQUIRED -> RecoveryStatus.SYSTEM_AUTH_REQUIRED;
            case UNAVAILABLE -> RecoveryStatus.SCREEN_UNAVAILABLE;
        };
    }
    boolean isAuthorized() { return authorized; }
    boolean isConnected() { return connected; }
    int sessionGeneration() { return generation; }
    long remainingMillis() { return authorized ? TimeUnit.DAYS.toMillis(1) : 0; }
    boolean unlocked() { return ui.unlocked(); }
    ScreenRecovery screenRecovery() { return recovery; }
    private boolean valid() { return authorized; }
    private boolean executionAllowed() {
        if (!authorized || notificationBlocked) return false;
        if (!SessionService.notificationsAllowed(this)) {
            // Latch a loss observed between watchdog ticks: re-enabling cannot resume an old
            // command or socket before the service has rechecked the same approved claim.
            notificationBlocked = true;
            return false;
        }
        return SessionService.readyFor(this, generation);
    }
    boolean notificationBlocked() { return notificationBlocked; }

    void restore() {
        if (authorized || restoring || instance != this) return;
        try {
            config = new EnrollmentStore(this).read();
            JSONObject trust = new TrustedGrant(this).read(config);
            if (trust == null) {
                if (new TrustedGrant(this).pending(config) == null) return;
                if (!SessionService.notificationsAllowed(this)) { waitForNotifications(); return; }
                restoring = true; int run = generation;
                state = "正在完成你已确认的 AI 连接";
                recoveryStatus = RecoveryStatus.VERIFYING_CONNECTION;
                boolean started = ApprovalRecovery.resume(this, config, outcome -> {
                    if (generation != run || instance != this) return;
                    restoring = false;
                    if (outcome == ApprovalRecovery.Outcome.APPROVED) restore();
                    else if (outcome == ApprovalRecovery.Outcome.RETRY) {
                        state = "等待网络恢复，刚才的确认已保留"; recoveryStatus = RecoveryStatus.NETWORK_RECONNECTING; retryApproval(run);
                    } else { state = "连接已解除，请重新邀请自己的 AI"; recoveryStatus = RecoveryStatus.WAITING_FOR_AI; }
                });
                if (!started) retryApproval(run);
                return;
            }
            if (!SessionService.notificationsAllowed(this)) { waitForNotifications(); return; }
            restoring = true; int run = generation;
            state = "正在恢复与我的 AI 的连接";
            recoveryStatus = RecoveryStatus.VERIFYING_CONNECTION;
            RelayApi.requestStatus(config.url, "/v1/device/pairing", "GET", config.token, null, (body, status, error) -> {
                if (generation != run || instance != this) return;
                restoring = false;
                if (error != null) {
                    if (status == 401 || status == 403 || status == 410) stop("连接已解除，请重新邀请自己的 AI");
                    else {
                        boolean secureFailure = TlsPolicy.USER_MESSAGE.equals(error);
                        state = secureFailure ? TlsPolicy.USER_MESSAGE : "等待网络恢复，配对已保留";
                        recoveryStatus = secureFailure ? RecoveryStatus.SECURE_CONNECTION_REQUIRED : RecoveryStatus.NETWORK_RECONNECTING;
                        scheduleRestore(run, 10000);
                    }
                    return;
                }
                try {
                    ServiceProtocol.version(body);
                    if (!"approved".equals(body.getString("state")) || !body.getBoolean("persistent") || !body.getBoolean("all_apps")
                            || !body.getBoolean("allow_control") || !body.isNull("session_expires_at")
                            || !trust.getString("claim").equals(body.getJSONObject("candidate").getString("claim_id"))) {
                        stop("连接授权已变化，请重新邀请自己的 AI"); return;
                    }
                    // Re-read after network: an owner stop must fence a delayed successful response.
                    JSONObject current = new TrustedGrant(this).read(config);
                    if (current == null || !current.getString("claim").equals(trust.getString("claim"))) return;
                    if (!SessionService.notificationsAllowed(this)) { waitForNotifications(); return; }
                    PreviewState.config = config; TrustedGrant.show(current);
                    notificationBlocked = false;
                    authorized = true; PreviewState.grantAvailable = false;
                    url = RelayAddress.webSocket(config.url); token = config.token;
                    state = "正在连接我的 AI";
                    // No socket or device command until the visible foreground notice exists.
                    // Its asynchronous callback is generation-fenced just like relay approval.
                    SessionService.begin(this);
                } catch (Exception failure) { stop("服务版本或授权不匹配，请检查更新"); }
            });
        } catch (Exception failure) { stop("无法读取已保存的连接，请重新接入"); }
    }
    private void retryApproval(int run) {
        scheduleRestore(run, 10000);
    }
    private void scheduleRestore(int run, long delay) {
        // Keep the restore gate held during backoff; Activity refresh cannot enqueue retries.
        restoring = true;
        main.postDelayed(() -> {
            if (generation != run || instance != this) return;
            restoring = false; restore();
        }, delay);
    }
    private void waitForNotifications() {
        state = "请允许 Airtap 通知，原连接会自动恢复";
        recoveryStatus = RecoveryStatus.NOTIFICATIONS_REQUIRED;
        scheduleRestore(generation, 2000);
    }
    void notificationUnavailable(int run, boolean startFailed) {
        if (run != generation || instance != this) return;
        // A missing stop affordance closes execution immediately, but does not erase owner consent.
        suspend("连接通知暂不可用，原配对已保留");
        recoveryStatus = startFailed ? RecoveryStatus.NOTIFICATION_START_RETRY : RecoveryStatus.NOTIFICATIONS_REQUIRED;
        long delay = startFailed ? Math.min(30000, 5000L << Math.min(notificationFailures++, 3)) : 2000;
        scheduleRestore(generation, delay);
    }
    void notificationReady(int run) {
        if (run != generation || instance != this || !executionAllowed()) return;
        notificationFailures = 0;
        recoveryStatus = RecoveryStatus.NETWORK_RECONNECTING;
        if (networkCallback == null) observeNetwork(run);
        connect(run);
    }
    void stop(String message) {
        try { new TrustedGrant(this).stop(); }
        finally { suspend(message); recoveryStatus = RecoveryStatus.WAITING_FOR_AI; notificationFailures = 0; PreviewState.grantAvailable = false; }
    }
    private void suspend(String message) {
        authorized = false; connected = false; restoring = false; activeCommand = 0; generation++;
        transportOpen.set(false);
        WorkspaceShares.revokeAll(this);
        recovery.cancel();
        ui.cancelGestures();
        ui.clear(); main.removeCallbacksAndMessages(null); reconnectTask = null; handshakeTask = null;
        if (networkCallback != null) {
            try { getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback); } catch (Exception ignored) { }
            networkCallback = null;
        }
        currentNetwork = null;
        WebSocket previous = socket; socket = null; if (previous != null) previous.cancel();
        token = null; url = null; retry = 0; state = message; SessionService.end(this);
    }
    private void connect(int run) {
        if (generation != run || !executionAllowed() || socket != null) return;
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        Network active = manager.getActiveNetwork();
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(active);
        if (active == null || capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            PreviewDiagnostics.event(this, PreviewDiagnostics.Event.connect_wait_network);
            state = "等待系统默认网络恢复（不会重放操作）";
            recoveryStatus = RecoveryStatus.NETWORK_RECONNECTING;
            scheduleConnect(run, 3000);
            return;
        }
        state = "正在连接中继";
        recoveryStatus = RecoveryStatus.NETWORK_RECONNECTING;
        attempts++;
        PreviewDiagnostics.event(this, PreviewDiagnostics.Event.connect_attempt);
        Request request = new Request.Builder().url(url).header("Authorization", "Bearer " + token).build();
        final java.util.concurrent.atomic.AtomicBoolean connectionOpen = new java.util.concurrent.atomic.AtomicBoolean(true);
        transportOpen = connectionOpen;
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                main.post(() -> {
                    if (!current(ws, run)) {
                        PreviewDiagnostics.event(PreviewService.this, PreviewDiagnostics.Event.socket_cancel,
                                PreviewDiagnostics.Reason.stale_callback, 0, run, socket == ws ? 1 : 0);
                        ws.cancel(); return;
                    }
                    cancelHandshake();
                    connected = true;
                    connections++;
                    retry = 0;
                    PreviewDiagnostics.event(PreviewService.this, PreviewDiagnostics.Event.socket_open,
                            PreviewDiagnostics.Reason.none, response.code(), run, 1);
                    state = "已连接（手机主动 WSS）";
                    recoveryStatus = RecoveryStatus.CONNECTED;
                    lastDiagnostic = "HTTP=101 exception=none cause=none";
                    ws.send("hello");
                });
            }
            @Override public void onMessage(WebSocket ws, String text) {
                if (text.length() > 8192 || text.getBytes(StandardCharsets.UTF_8).length > 8192) {
                    PreviewDiagnostics.event(PreviewService.this, PreviewDiagnostics.Event.socket_close_requested,
                            PreviewDiagnostics.Reason.request_too_large, 1009, run, socket == ws ? 1 : 0);
                    ws.close(1009, "request too large"); return;
                }
                main.post(() -> { if (current(ws, run)) command(ws, run, text); });
            }
            @Override public void onMessage(WebSocket ws, ByteString bytes) {
                PreviewDiagnostics.event(PreviewService.this, PreviewDiagnostics.Event.socket_close_requested,
                        PreviewDiagnostics.Reason.text_requests_only, 1003, run, socket == ws ? 1 : 0);
                ws.close(1003, "text requests only");
            }
            @Override public void onClosing(WebSocket ws, int code, String reason) {
                // Fence pending recovery immediately on the transport thread, before the close handshake.
                // Each listener owns its marker; a late old callback cannot reopen or close a newer link.
                connectionOpen.set(false);
                main.post(() -> disconnected(ws, run, code));
                PreviewDiagnostics.event(PreviewService.this, PreviewDiagnostics.Event.socket_closing,
                        PreviewDiagnostics.closeReason(reason), code, run, socket == ws ? 1 : 0);
                ws.close(code, null);
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                connectionOpen.set(false);
                PreviewDiagnostics.event(PreviewService.this, PreviewDiagnostics.Event.socket_closed,
                        PreviewDiagnostics.closeReason(reason), code, run, socket == ws ? 1 : 0);
                main.post(() -> disconnected(ws, run, code));
            }
            @Override public void onFailure(WebSocket ws, Throwable failure, Response response) {
                connectionOpen.set(false);
                int status = response == null ? 0 : response.code();
                PreviewDiagnostics.failure(PreviewService.this, failure, status, run, socket == ws ? 1 : 0);
                Throwable cause = failure.getCause();
                // Class names and numeric status only: never pass Throwable or its message to logs.
                String diagnostic = "HTTP=" + status + " exception=" + failure.getClass().getName()
                        + " cause=" + (cause == null ? "none" : cause.getClass().getName());
                if (cause instanceof android.system.ErrnoException) {
                    diagnostic += " errno=" + ((android.system.ErrnoException) cause).errno;
                }
                String safeDiagnostic = diagnostic;
                main.post(() -> {
                    if (socket != ws || generation != run) return;
                    lastDiagnostic = safeDiagnostic;
                    disconnected(ws, run, status);
                    if (valid() && TlsPolicy.isSecurityFailure(failure)) {
                        state = TlsPolicy.USER_MESSAGE;
                        recoveryStatus = RecoveryStatus.SECURE_CONNECTION_REQUIRED;
                    }
                });
            }
        });
        awaitHandshake(socket, run, connectionOpen);
    }

    private void awaitHandshake(WebSocket expected, int run,
                                java.util.concurrent.atomic.AtomicBoolean connectionOpen) {
        cancelHandshake();
        // TCP's connect timeout and WebSocket pings do not bound DNS/TLS/HTTP upgrade
        // as a whole. Keep an independent deadline until onOpen reaches the main thread.
        handshakeTask = () -> {
            if (socket != expected || generation != run || connected) return;
            connectionOpen.set(false);
            PreviewDiagnostics.event(this, PreviewDiagnostics.Event.handshake_timeout,
                    PreviewDiagnostics.Reason.timeout, 0, run, 1);
            lastDiagnostic = "HTTP=0 exception=handshake_timeout cause=none";
            // Detach first: cancel() may deliver a late onFailure/onOpen. Neither may
            // affect the next attempt or resume work on the expired transport.
            disconnected(expected, run, 0);
            expected.cancel();
        };
        main.postDelayed(handshakeTask, HANDSHAKE_TIMEOUT_MS);
    }

    private void cancelHandshake() {
        if (handshakeTask != null) main.removeCallbacks(handshakeTask);
        handshakeTask = null;
    }

    private boolean current(WebSocket ws, int run) {
        return socket == ws && generation == run && executionAllowed() && transportOpen.get();
    }

    private void disconnected(WebSocket ws, int run, int status) {
        PreviewDiagnostics.event(this, PreviewDiagnostics.Event.disconnected,
                PreviewDiagnostics.Reason.none, status, run, socket == ws ? 1 : 0);
        if (socket != ws || generation != run) return;
        cancelHandshake();
        socket = null;
        connected = false;
        activeCommand = 0;
        recovery.cancel();
        if (!valid()) { stop("会话授权到期，请重新配对"); return; }
        if (status == 401 || status == 403 || status == 410 || status == 1008) { stop("中继拒绝授权，请重新配对"); return; }
        long delay = Math.min(30000, 1000L << Math.min(retry++, 5));
        delay = (long) (delay * (0.75 + jitter.nextDouble() * 0.5));
        state = "连接中断，等待重连（不重放操作）";
        recoveryStatus = RecoveryStatus.NETWORK_RECONNECTING;
        ui.cancelGestures();
        ui.clear();
        scheduleConnect(run, delay);
    }

    private void scheduleConnect(int run, long delay) {
        if (generation != run || !valid()) return;
        PreviewDiagnostics.event(this, PreviewDiagnostics.Event.reconnect_scheduled);
        if (reconnectTask != null) main.removeCallbacks(reconnectTask);
        reconnectTask = () -> { reconnectTask = null; connect(run); };
        main.postDelayed(reconnectTask, delay);
    }

    private void observeNetwork(int run) {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        currentNetwork = manager.getActiveNetwork();
        PreviewDiagnostics.event(this, PreviewDiagnostics.Event.network_observe);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                PreviewDiagnostics.event(PreviewService.this, PreviewDiagnostics.Event.network_available,
                        PreviewDiagnostics.Reason.none, 0, run, -1);
                main.post(() -> {
                    if (generation != run || !valid() || network.equals(currentNetwork)) return;
                    currentNetwork = network;
                    resetForNetwork(run);
                });
            }
            @Override public void onLost(Network network) {
                PreviewDiagnostics.event(PreviewService.this, PreviewDiagnostics.Event.network_lost,
                        PreviewDiagnostics.Reason.none, 0, run, -1);
                main.post(() -> {
                    if (generation != run || !valid() || !network.equals(currentNetwork)) return;
                    currentNetwork = null;
                    resetForNetwork(run);
                });
            }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                PreviewDiagnostics.event(PreviewService.this, PreviewDiagnostics.Event.network_capabilities,
                        PreviewDiagnostics.Reason.none, 0, run, -1);
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return;
                main.post(() -> {
                    if (generation == run && valid() && socket == null && network.equals(currentNetwork)) scheduleConnect(run, 250);
                });
            }
        };
        manager.registerDefaultNetworkCallback(networkCallback);
    }

    private void resetForNetwork(int run) {
        PreviewDiagnostics.event(this, PreviewDiagnostics.Event.network_reset,
                PreviewDiagnostics.Reason.network_change, 0, run, -1);
        WebSocket previous = socket;
        cancelHandshake();
        socket = null;
        connected = false;
        activeCommand = 0;
        recovery.cancel();
        ui.cancelGestures();
        ui.clear();
        if (previous != null) {
            PreviewDiagnostics.event(this, PreviewDiagnostics.Event.socket_cancel,
                    PreviewDiagnostics.Reason.network_change, 0, run, -1);
            previous.cancel();
        }
        state = "系统网络变化，重新建立连接（不重放操作）";
        scheduleConnect(run, 500);
    }

    private void command(WebSocket ws, int run, String text) {
        String id = null;
        try {
            JSONObject request = new JSONObject(text); id = request.getString("id");
            if (!ID.matcher(id).matches()) { ws.close(1008, "invalid_id"); return; }
            long sequence = ServiceProtocol.integer(request, "sequence");
            if (!current(ws, run)) return;
            new TrustedGrant(this).consume(config, sequence);
            request.remove("sequence");
            String action = request.getString("action");
            if ("ping".equals(action)) {
                if (request.length() != 2) throw new ScopedUi.Refused("invalid_request");
                success(ws, id, RelayApi.json("authorized", true, "persistent", true, "all_apps", true, "allow_control", true,
                        "client", RelayApi.json("platform", "android", "version_name", BuildConfig.VERSION_NAME,
                                "version_code", BuildConfig.VERSION_CODE, "build_type", BuildConfig.BUILD_TYPE),
                        "device_unlocked", ui.unlocked(), "screen_state", ui.screenState().wireName, "auto_wake", true,
                        "capabilities", new org.json.JSONArray().put("companion_v1").put("paged_tree_v1").put("node_text_v1").put("screen_recovery_v1").put("atomic_input_v1").put("system_actions_v1").put("drag_v1").put("gesture_v1").put("semantic_node_v1").put("windows_v1").put("volume_v1").put("files_v1").put("file_share_v1"),
                        "observation_limits", RelayApi.json("max_inspected_nodes", PagedTree.MAX_NODES, "max_capture_bytes", PagedTree.MAX_CAPTURE_BYTES,
                                "max_page_nodes", PagedTree.MAX_PAGE_NODES, "max_page_bytes", TreeReadBudget.MAX_BYTES, "max_text_chunk_utf16", ObservedText.MAX_CHUNK_UTF16, "snapshot_ttl_ms", 30000),
                        "gesture_limits", RelayApi.json("max_tracks", GesturePlan.trackLimit(android.accessibilityservice.GestureDescription.getMaxStrokeCount()),
                                "max_points_per_track", GesturePlan.MAX_POINTS, "max_duration_ms", GesturePlan.MAX_TOTAL_MS,
                                "max_strokes_per_dispatch", android.accessibilityservice.GestureDescription.getMaxStrokeCount())));
                return;
            }
            if (activeCommand != 0) { error(ws, id, "busy"); return; }
            ui.validateRequest(request);
            long command = ++commandSerial; activeCommand = command;
            String requestId = id;
            java.util.function.BooleanSupplier commandCurrent = () -> current(ws, run) && activeCommand == command;
            CompanionUi.Reply reply = new CompanionUi.Reply() {
                private boolean finish() {
                    if (!commandCurrent.getAsBoolean()) return false;
                    activeCommand = 0; return true;
                }
                public void success(JSONObject value) { if (finish()) PreviewService.this.success(ws, requestId, value); }
                public void error(String code) { if (finish()) PreviewService.this.error(ws, requestId, code); }
            };
            recovery.prepare(commandCurrent, new ScreenPreparation.Completion() {
                @Override public void ready(boolean recovered) {
                    if (!commandCurrent.getAsBoolean()) return;
                    if (recovered && request.has("snapshot_id")) { reply.error("fresh_snapshot_required"); return; }
                    if (recovered && request.has("observation_id")) { reply.error("fresh_observation_required"); return; }
                    ui.execute(request, reply, commandCurrent);
                }
                @Override public void error(String code) { reply.error(code); }
            });
        } catch (ScopedUi.Refused failure) { if (id != null) error(ws, id, failure.code); }
        catch (Exception failure) {
            // Storage failure must stop execution; never proceed without persisted replay protection.
            if (id != null) error(ws, id, "request_or_storage_failure");
        }
    }
    private void success(WebSocket ws, String id, JSONObject value) { ws.send(RelayApi.json("id", id, "ok", true, "result", value).toString()); }
    private void error(WebSocket ws, String id, String code) {
        String next = AtomicInput.nextStep(code);
        if (next == null) next = FileScopes.recoveryHint(code);
        if (next == null) next = WindowInventory.nextStep(code);
        JSONObject details = next == null ? RelayApi.json("code", code) : RelayApi.json("code", code, "next_step", next);
        ws.send(RelayApi.json("id", id, "ok", false, "error", details).toString());
    }
    private String network() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        NetworkCapabilities cap = manager == null ? null : manager.getNetworkCapabilities(manager.getActiveNetwork());
        return transport(cap);
    }

    private static String transport(NetworkCapabilities cap) {
        if (cap == null) return "offline";
        if (cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "vpn";
        if (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
        if (cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
        if (cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
        return "other";
    }

}
