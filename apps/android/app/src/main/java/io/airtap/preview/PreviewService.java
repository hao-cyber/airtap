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

/** Persisted owner grants and a single task execution context, fenced across reconnects. */
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
    private NetworkPath currentNetworkPath;
    private Runnable reconnectTask, handshakeTask;
    private int generation, retry, connections, attempts;
    private String url, token;
    private String sessionFence, restoringFence;
    private boolean multiMode, multiHello, approvalInFlight;
    private java.util.Set<String> remoteGrants = java.util.Set.of();
    private Runnable helloTask;
    private long nextGrantRefresh, nextOwnerRetry;
    private boolean grantRefreshInFlight;
    private final TaskLease tasks = new TaskLease(SystemClock::elapsedRealtime, new TaskLease.Effects() {
        public void invalidate() { invalidateTask(); }
        public boolean quiescent() { return ui.quiescent() && FileScopes.quiescent() && MediaCapture.quiescent(); }
        public boolean approved(String claim) throws Exception { return !OwnerRevocations.allPending(PreviewService.this,config) && !OwnerRevocations.claimsPending(PreviewService.this,config).contains(claim) && remoteGrants.contains(claim) && new TrustedGrant(PreviewService.this).grant(config,claim) != null; }
        public void requireLease(String claim) throws Exception { new TrustedGrant(PreviewService.this).requireLease(config,claim); }
    });
    private final SimPinInput simPinInput=new SimPinInput(this);
    private final CommandTimeline fileCommandTimeline = new CommandTimeline();
    private record CommandAuthority(String claim, String grantFence, TaskLease.Ticket ticket, WebSocket socket, int generation, boolean leased, boolean events) {}
    private void invalidateTask() { activeCommand=0; fileCommandTimeline.interrupt(); recovery.cancel();ui.clear();ui.cancelGestures();MediaCapture.invalidate();WorkspaceShares.revokeAll(this); }
    private void disconnectTask() { multiHello=false;if(helloTask!=null)main.removeCallbacks(helloTask);helloTask=null;tasks.disconnect(); }
    private boolean authorityCurrent(CommandAuthority authority) {
        if(authority==null||!current(authority.socket,authority.generation))return false;
        try {
            TrustedGrant trust=new TrustedGrant(this);
            if(!authority.grantFence.equals(trust.grantFence(config,authority.claim)))return false;
            if(OwnerRevocations.allPending(this,config)||OwnerRevocations.claimsPending(this,config).contains(authority.claim))return false;
            if(!multiMode)return true;
            if(!multiHello||!remoteGrants.contains(authority.claim))return false;
            if(authority.events)return true;
            if(authority.leased)return tasks.current(authority.ticket);
            JSONObject grant=trust.grant(config,authority.claim);
            return tasks.idle()&&trust.grants(config).size()==1&&grant!=null&&!ServiceProtocol.bool(grant,"lease_required");
        }catch(Exception unavailable){return false;}
    }
    void ownerModeChanged() { suspend("正在更新 AI 连接");restore(); }
    void ownerGrantsChanged() {
        if(multiMode) {
            try {
                TrustedGrant trust=new TrustedGrant(this);String holder=tasks.claim();
                java.util.Set<String> revoked=OwnerRevocations.claimsPending(this,config);
                if(OwnerRevocations.allPending(this,config)||trust.grants(config).isEmpty()||holder==null&&!revoked.isEmpty()||holder!=null&&(trust.grant(config,holder)==null||revoked.contains(holder)))tasks.disconnect();
            }
            catch(Exception unreadable){storageUnavailable();return;}
            refreshMultiGrants();
        } else restore();
    }
    private boolean resumeMigration(EnrollmentStore.Config saved) throws Exception {
        TrustedGrant trust=new TrustedGrant(this);JSONObject pending=trust.migration(saved);
        if(pending==null)return false;config=saved;
        final String expected=pending.getString("fence");
        if(!authorized){
            if(!SessionService.notificationsAllowed(this)){waitForNotifications();return true;}
            restoringFence=expected;
            if(!SessionService.ensureRestoring(this)){notificationUnavailable(generation,true);return true;}
            state="正在核实多 AI 连接，原授权已保留";recoveryStatus=RecoveryStatus.VERIFYING_CONNECTION;
        }
        MigrationRecovery.resume(this,saved,outcome->{
            if(instance!=this)return;
            try {
                EnrollmentStore.Config current=new EnrollmentStore(this).read();
                if(current==null||!current.url.equals(saved.url)||!current.token.equals(saved.token))return;
                TrustedGrant latest=new TrustedGrant(this);
                if(outcome==MigrationRecovery.Outcome.COMMITTED&&latest.multi(current))ownerModeChanged();
                else if(outcome==MigrationRecovery.Outcome.STOPPED){
                    if(expected.equals(sessionFence)||expected.equals(restoringFence)){suspend("原 AI 授权已解除");SessionService.end(this);}
                }else if(outcome==MigrationRecovery.Outcome.REJECTED&&!authorized)restore();
            }catch(Exception unreadable){storageUnavailable();}
        });
        return true;
    }
    private void resumeMultiApproval() throws Exception {
        if(approvalInFlight||new TrustedGrant(this).pending(config)==null)return;
        approvalInFlight=true;
        try {
            boolean sent=ApprovalRecovery.resume(this,config,outcome->{
                approvalInFlight=false;if(instance!=this)return;
                if(outcome==ApprovalRecovery.Outcome.APPROVED)ownerGrantsChanged();
                else if(outcome==ApprovalRecovery.Outcome.RETRY&&!authorized)scheduleRestore(generation,5000);
            });
            if(!sent){approvalInFlight=false;if(!authorized)scheduleRestore(generation,5000);}
        } catch(Exception failure) { approvalInFlight=false;throw failure; }
    }
    private java.util.Map<String,String> grantSnapshot() throws Exception {
        java.util.Map<String,String> result=new java.util.HashMap<>();TrustedGrant trust=new TrustedGrant(this);
        for(JSONObject g:trust.grants(config)){String claim=g.getString("claim");result.put(claim,trust.grantFence(config,claim));}return result;
    }
    private void retainRemote(java.util.Set<String> remote,java.util.Map<String,String> snapshot) throws Exception {
        TrustedGrant trust=new TrustedGrant(this);java.util.Set<String> keep=new java.util.HashSet<>(remote);
        for(JSONObject g:trust.grants(config)){String claim=g.getString("claim");if(!java.util.Objects.equals(snapshot.get(claim),trust.grantFence(config,claim)))keep.add(claim);}
        trust.retain(config,keep);FileScopes.retain(this,trust.validClaims(config));
    }
    private void refreshMultiGrants() {
        if(config==null||!multiMode||grantRefreshInFlight)return;int run=generation;String expected=sessionFence;
        WebSocket transport=socket;int connection=connections;
        final java.util.Map<String,String> snapshot;
        try{snapshot=grantSnapshot();}catch(Exception unreadable){storageUnavailable();return;}
        grantRefreshInFlight=true;nextGrantRefresh=SystemClock.elapsedRealtime()+10000;
        RelayApi.requestStatus(config.url,"/v1/device/grants","GET",config.token,null,(body,status,error)->{
            if(instance!=this||generation!=run)return;grantRefreshInFlight=false;
            if(socket!=transport||connections!=connection)return;
            try{
                TrustedGrant trust=new TrustedGrant(this);if(expected==null||!trust.isCurrent(config,expected))return;
                if(error!=null){
                    if(RelayFailure.deviceTerminal(status,body))stopRestoring(expected,"连接已解除，请重新邀请自己的 AI");
                    else{suspend("正在重新核实原 AI 连接，尚不能操作手机");recoveryStatus=RecoveryStatus.NETWORK_RECONNECTING;scheduleRestore(generation,10000);}
                    return;
                }
                remoteGrants=MultiAiProtocol.claims(body);retainRemote(remoteGrants,snapshot);
                String holder=tasks.claim();if(holder!=null&&trust.grant(config,holder)==null)tasks.disconnect();
                if(trust.grants(config).isEmpty()){suspend("等待连接自己的 AI");SessionService.end(this);}
                else if(!authorized)restore();
            }catch(Exception unreadable){storageUnavailable();}
        });
    }
    private void restoreMulti() throws Exception {
        config=new EnrollmentStore(this).read();multiMode=true;
        if(SystemClock.elapsedRealtime()>=nextOwnerRetry){nextOwnerRetry=SystemClock.elapsedRealtime()+5000;OwnerRevocations.flush(this,config,null);}
        if(OwnerRevocations.allPending(this,config))return;
        resumeMultiApproval();
        if(authorized){if(SystemClock.elapsedRealtime()>=nextGrantRefresh)refreshMultiGrants();return;}
        if(restoring)return;
        TrustedGrant trust=new TrustedGrant(this);String expected=trust.sessionFence(config);
        if(expected==null)return;
        if(!SessionService.notificationsAllowed(this)){waitForNotifications();return;}
        restoringFence=expected;if(!SessionService.ensureRestoring(this)){notificationUnavailable(generation,true);return;}
        final java.util.Map<String,String> snapshot=grantSnapshot();
        restoring=true;int run=generation;state="正在核实已保存的 AI 连接";recoveryStatus=RecoveryStatus.VERIFYING_CONNECTION;
        // Committed mode is reconciled read-only; recovery never starts another migration.
        RelayApi.requestStatus(config.url,"/v1/device/grants","GET",config.token,null,(body,status,error)->{
            if(instance!=this||generation!=run)return;restoring=false;
            try{
                if(!trust.isCurrent(config,expected))return;
                if(error!=null){if(RelayFailure.deviceTerminal(status,body))stopRestoring(expected,"连接已解除，请重新邀请自己的 AI");else{recoveryStatus=RecoveryStatus.NETWORK_RECONNECTING;scheduleRestore(run,10000);}return;}
                remoteGrants=MultiAiProtocol.claims(body);retainRemote(remoteGrants,snapshot);
                if(trust.grants(config).isEmpty()){SessionService.end(this);return;}
                if(!trust.isCurrent(config,expected))return;
                notificationBlocked=false;sessionFence=expected;restoringFence=null;authorized=true;url=RelayAddress.webSocket(config.url);token=config.token;PreviewState.config=config;
                SessionService.begin(this);
            }catch(Exception unreadable){storageUnavailable();}
        });
    }
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
        if (multiMode && !multiHello) return RecoveryStatus.VERIFYING_CONNECTION;
        return switch (ui.screenState()) {
            case READY -> RecoveryStatus.CONNECTED;
            case SCREEN_OFF, DISMISSIBLE_KEYGUARD -> RecoveryStatus.SCREEN_PREPARABLE;
            case AUTHENTICATION_REQUIRED -> RecoveryStatus.SYSTEM_AUTH_REQUIRED;
            case UNAVAILABLE -> RecoveryStatus.SCREEN_UNAVAILABLE;
        };
    }
    boolean isAuthorized() { return authorized; }
    boolean isConnected() { return connected && (!multiMode || multiHello); }
    /** Called by the Activity dump on the main thread; reads fields only, never restores work. */
    JSONObject diagnostics(EnrollmentStore.Config expected) {
        boolean same = expected != null && config != null && expected.url.equals(config.url) && expected.token.equals(config.token);
        return RelayApi.json("service_present", true, "service_identity_matches", same,
                "authorized", authorized, "connected", connected, "service_multi_mode", multiMode,
                "multi_hello_acknowledged", multiHello, "restoring", restoring,
                "notification_blocked", notificationBlocked);
    }
    int sessionGeneration() { return generation; }
    String sessionFence() { return sessionFence; }
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
        if(instance!=this)return;
        try {
            EnrollmentStore.Config saved=new EnrollmentStore(this).read();
            TrustedGrant local=new TrustedGrant(this);
            if(local.multi(saved)) { restoreMulti(); return; }
            JSONObject original=local.read(saved);
            if(original!=null&&(OwnerRevocations.allPending(this,saved)||OwnerRevocations.claimsPending(this,saved).contains(original.getString("claim")))){
                if(SystemClock.elapsedRealtime()>=nextOwnerRetry){nextOwnerRetry=SystemClock.elapsedRealtime()+5000;OwnerRevocations.flush(this,saved,null);}
                if(authorized||restoring)suspend("本机已停止，正在确认原 AI 的解除");
                SessionService.end(this);return;
            }
            // A pending migration never turns a failed enable into an irreversible local mode.
            // An existing single socket remains live; a disconnected one waits for reconciliation.
            if(resumeMigration(saved))return;
        }catch(Exception unreadable){storageUnavailable();return;}
        multiMode=false;
        if (authorized || restoring) return;
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
            String expectedFence = TrustedGrant.fenceOf(trust);
            restoringFence = expectedFence;
            if (!SessionService.ensureRestoring(this)) { notificationUnavailable(generation, true); return; }
            restoring = true; int run = generation;
            state = "正在恢复与我的 AI 的连接";
            recoveryStatus = RecoveryStatus.VERIFYING_CONNECTION;
            RelayApi.requestStatus(config.url, "/v1/device/pairing", "GET", config.token, null, (body, status, error) -> {
                if (generation != run || instance != this) return;
                restoring = false;
                try {
                    TrustedGrant latest=new TrustedGrant(this);
                    if(latest.multi(config)||latest.migration(config)!=null){restore();return;}
                    if (!latest.isCurrent(config, expectedFence)) return;
                } catch (Exception unreadable) { storageUnavailable(); return; }
                if (error != null) {
                    if (RelayFailure.deviceTerminal(status, body)) stopRestoring(expectedFence, "连接已解除，请重新邀请自己的 AI");
                    else {
                        boolean secureFailure = TlsPolicy.USER_MESSAGE.equals(error);
                        state = secureFailure ? TlsPolicy.USER_MESSAGE : "等待网络恢复，配对已保留";
                        recoveryStatus = secureFailure ? RecoveryStatus.SECURE_CONNECTION_REQUIRED : RecoveryStatus.NETWORK_RECONNECTING;
                        scheduleRestore(run, 10000);
                    }
                    return;
                }
                try {
                    if (!RelayFailure.singleApproved(body, trust.getString("claim"))) {
                        stopRestoring(expectedFence, "连接授权已变化，请重新邀请自己的 AI"); return;
                    }
                    // Re-read after network: an owner stop must fence a delayed successful response.
                    JSONObject current;
                    try {
                        current = new TrustedGrant(this).read(config);
                        if (current == null || !expectedFence.equals(TrustedGrant.fenceOf(current))) return;
                    } catch (Exception unreadable) { storageUnavailable(); return; }
                    if (!SessionService.notificationsAllowed(this)) { waitForNotifications(); return; }
                    PreviewState.config = config; TrustedGrant.show(current);
                    notificationBlocked = false;
                    sessionFence = expectedFence; restoringFence = null;
                    authorized = true; PreviewState.grantAvailable = false;
                    url = RelayAddress.webSocket(config.url); token = config.token;
                    state = "正在连接我的 AI";
                    // No socket or device command until the visible foreground notice exists.
                    // Its asynchronous callback is generation-fenced just like relay approval.
                    SessionService.begin(this);
                } catch (Exception failure) {
                    state = "中继响应尚未核实，原连接记录已保留";
                    recoveryStatus = RecoveryStatus.VERIFYING_CONNECTION;
                    scheduleRestore(run, 10000);
                }
            });
        } catch (Exception failure) { storageUnavailable(); }
    }
    private void stopRestoring(String expectedFence, String message) {
        try {
            if (!new TrustedGrant(this).stopIfCurrent(config, expectedFence)) return;
            suspend(message); recoveryStatus = RecoveryStatus.WAITING_FOR_AI; SessionService.end(this);
        } catch (Exception unreadable) { storageUnavailable(); }
    }
    void stoppedLocally(String expectedFence) {
        if (expectedFence == null || (!expectedFence.equals(sessionFence) && !expectedFence.equals(restoringFence))) return;
        suspend("已停止手机控制"); recoveryStatus = RecoveryStatus.WAITING_FOR_AI;
    }
    void sessionCarrierUnavailable() {
        suspend("正在恢复原 AI 连接，尚不能操作手机");
        recoveryStatus = RecoveryStatus.VERIFYING_CONNECTION;
        scheduleRestore(generation, 2000);
    }
    void storageUnavailable() {
        suspend("暂时无法读取或保存原 AI 连接，记录已保留，请检查手机存储");
        recoveryStatus = RecoveryStatus.STORAGE_UNAVAILABLE;
        SessionService.end(this);
        scheduleRestore(generation, 10000);
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
        SessionService.end(this);
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
        finally { suspend(message); SessionService.end(this); recoveryStatus = RecoveryStatus.WAITING_FOR_AI; notificationFailures = 0; PreviewState.grantAvailable = false; }
    }
    private void suspend(String message) {
        disconnectTask();grantRefreshInFlight=false;
        authorized = false; connected = false; restoring = false; activeCommand = 0; generation++;
        sessionFence = null; restoringFence = null;
        transportOpen.set(false);
        WorkspaceShares.revokeAll(this);
        recovery.cancel();
        ui.cancelGestures();
        ui.clear(); main.removeCallbacksAndMessages(null); reconnectTask = null; handshakeTask = null;
        if (networkCallback != null) {
            try { getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback); } catch (Exception ignored) { }
            networkCallback = null;
        }
        currentNetwork = null; currentNetworkPath = null;
        WebSocket previous = socket; socket = null; if (previous != null) previous.cancel();
        token = null; url = null; retry = 0; state = message; SessionService.paused(this);
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
                    state = multiMode ? "正在核实已保存的 AI 连接" : "已连接（手机主动 WSS）";
                    recoveryStatus = multiMode ? RecoveryStatus.VERIFYING_CONNECTION : RecoveryStatus.CONNECTED;
                    lastDiagnostic = "HTTP=101 exception=none cause=none";
                    if(multiMode){
                        multiHello=false;ws.send(MultiAiProtocol.hello().toString());
                        helloTask=()->{if(current(ws,run)&&!multiHello){disconnected(ws,run,0);ws.cancel();}};main.postDelayed(helloTask,HANDSHAKE_TIMEOUT_MS);
                    }else ws.send("hello");
                });
            }
            @Override public void onMessage(WebSocket ws, String text) {
                long receivedNanos = System.nanoTime();
                if (text.length() > 66560 || text.getBytes(StandardCharsets.UTF_8).length > 66560) {
                    PreviewDiagnostics.event(PreviewService.this, PreviewDiagnostics.Event.socket_close_requested,
                            PreviewDiagnostics.Reason.request_too_large, 1009, run, socket == ws ? 1 : 0);
                    ws.close(1009, "request too large"); return;
                }
                main.post(() -> { if (current(ws, run)) {
                    long mainStartedNanos = System.nanoTime();
                    if(multiMode)multiMessage(ws,run,text,receivedNanos,mainStartedNanos);
                    else command(ws,run,text,receivedNanos,mainStartedNanos);
                } });
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
        disconnectTask();
        socket = null;
        connected = false;
        activeCommand = 0;
        recovery.cancel();
        try {
            TrustedGrant trust=new TrustedGrant(this);
            if(!multiMode&&(trust.multi(config)||trust.migration(config)!=null)){
                suspend("正在核实多 AI 连接，原授权已保留");restore();return;
            }
        }catch(Exception unreadable){storageUnavailable();return;}
        if (!valid()) { stop("会话授权到期，请重新配对"); return; }
        // HTTP upgrade refusals have no verified protocol body here. Reconcile the original
        // identity read-only before deciding it was revoked; a proxy refusal is not owner STOP.
        if(status==401||status==403||status==410||status==1008){suspend("连接需要重新核实，原授权已保留");scheduleRestore(generation,2000);return;}
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

    private record NetworkPath(int transports, boolean internet, boolean validated) {
        boolean available() { return internet && validated; }
        boolean newlyAvailableAfter(NetworkPath previous) {
            // A VPN can remain VALIDATED while its Wi-Fi/cellular underlay disappears.
            // A changed combination with a remaining physical transport is a usable route
            // switch; losing the last physical transport is not.
            if (!available()) return false;
            if (previous == null || !previous.available()) return true;
            int vpn = 1 << NetworkCapabilities.TRANSPORT_VPN;
            return transports != previous.transports && (transports & ~vpn) != 0;
        }
    }

    private NetworkPath networkPath(NetworkCapabilities capabilities) {
        if (capabilities == null) return null;
        int transports = 0;
        for (int transport : new int[]{NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_WIFI,
                NetworkCapabilities.TRANSPORT_BLUETOOTH, NetworkCapabilities.TRANSPORT_ETHERNET,
                NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI_AWARE,
                NetworkCapabilities.TRANSPORT_LOWPAN, NetworkCapabilities.TRANSPORT_USB}) {
            if (capabilities.hasTransport(transport)) transports |= 1 << transport;
        }
        // Public, stable route hints only; signal strength and other noisy metadata are ignored.
        return new NetworkPath(transports, capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
    }

    private void observeNetwork(int run) {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        currentNetwork = manager.getActiveNetwork();
        currentNetworkPath = networkPath(manager.getNetworkCapabilities(currentNetwork));
        PreviewDiagnostics.event(this, PreviewDiagnostics.Event.network_observe);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                PreviewDiagnostics.event(PreviewService.this, PreviewDiagnostics.Event.network_available,
                        PreviewDiagnostics.Reason.none, 0, run, -1);
                main.post(() -> {
                    if (generation != run || !valid() || network.equals(currentNetwork)) return;
                    currentNetwork = network;
                    currentNetworkPath = null;
                    resetForNetwork(run);
                });
            }
            @Override public void onLost(Network network) {
                PreviewDiagnostics.event(PreviewService.this, PreviewDiagnostics.Event.network_lost,
                        PreviewDiagnostics.Reason.none, 0, run, -1);
                main.post(() -> {
                    if (generation != run || !valid() || !network.equals(currentNetwork)) return;
                    currentNetwork = null;
                    currentNetworkPath = null;
                    resetForNetwork(run);
                });
            }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                PreviewDiagnostics.event(PreviewService.this, PreviewDiagnostics.Event.network_capabilities,
                        PreviewDiagnostics.Reason.none, 0, run, -1);
                NetworkPath path = networkPath(capabilities);
                main.post(() -> {
                    if (generation != run || !valid() || !network.equals(currentNetwork)) return;
                    NetworkPath previous = currentNetworkPath;
                    currentNetworkPath = path;
                    if (!path.newlyAvailableAfter(previous)) return;
                    // Forget failures on the old route once, not on every capabilities callback.
                    // An in-flight/healthy socket keeps its lifetime and command fences.
                    retry = 0;
                    if (socket == null) scheduleConnect(run, 250);
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
        disconnectTask();
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
        recoveryStatus = RecoveryStatus.NETWORK_RECONNECTING;
        scheduleConnect(run, 500);
    }

    private void multiMessage(WebSocket ws,int run,String text,long receivedNanos,long mainStartedNanos) {
        try{
            JSONObject message=new JSONObject(text);String type=message.getString("type");
            if("hello_ack".equals(type)){
                if(multiHello)throw new IllegalArgumentException();MultiAiProtocol.helloAck(message);multiHello=true;
                if(helloTask!=null)main.removeCallbacks(helloTask);helloTask=null;tasks.connected();
                state="已连接（手机主动 WSS）";recoveryStatus=RecoveryStatus.CONNECTED;return;
            }
            if(!multiHello)throw new IllegalArgumentException();
            if("task_fence".equals(type)){
                TaskLease.Fence fence=MultiAiProtocol.fence(message);String error=tasks.apply(fence);
                JSONObject ack=RelayApi.json("type","task_fence_ack","operation_id",fence.operation(),"lease_epoch",fence.epoch(),"ok",error==null);
                if(error!=null)ack.put("error",error);if(current(ws,run))ws.send(ack.toString());
                if("storage_failure".equals(error)||"grant_unavailable".equals(error)){disconnected(ws,run,0);ws.close(1011,"revalidate_grants");}
                return;
            }
            if(!"command".equals(type))throw new IllegalArgumentException();
            command(ws,run,text,receivedNanos,mainStartedNanos);
        }catch(Exception malformed){disconnectTask();ws.close(1008,"invalid_multi_message");}
    }
    private void command(WebSocket ws, int run, String text, long receivedNanos, long mainStartedNanos) {
        String id = null;
        CommandTimeline.Span timing = null;
        try {
            JSONObject request = new JSONObject(text);TrustedGrant trust=new TrustedGrant(this);String owner;TaskLease.Ticket ticket=null;boolean leased=false;
            if(multiMode){
                MultiAiProtocol.Envelope envelope=MultiAiProtocol.envelope(request);request=envelope.command();owner=envelope.claim();
                if(envelope.lease()!=null){ticket=tasks.ticket(owner,envelope.lease(),envelope.epoch());leased=true;}
            }else {JSONObject grant=trust.read(config);if(grant==null)throw new ScopedUi.Refused("permission_denied");owner=grant.getString("claim");}
            id = request.getString("id");
            if (!ID.matcher(id).matches()) { ws.close(1008, "invalid_id"); return; }
            long sequence = ServiceProtocol.integer(request, "sequence");
            if (!current(ws, run)) return;
            String grantFence=trust.grantFence(config,owner);
            CommandAuthority authority=new CommandAuthority(owner,grantFence,ticket,ws,run,leased, java.util.Set.of("event_status","event_read").contains(request.optString("action")));
            if(grantFence==null||!authorityCurrent(authority))throw new ScopedUi.Refused("task_not_ready");
            if (activeCommand != 0 || !ui.admits(request.optString("action"),owner) || !FileScopes.quiescent()) { error(ws,id,"busy");return; }
            if(FileScopes.ACTIONS.contains(request.optString("action")))
                timing = fileCommandTimeline.begin(owner,request.optString("action"),sequence,receivedNanos,mainStartedNanos);
            if(multiMode)trust.consume(config,owner,sequence);else trust.consume(config, sequence);
            if(!authorityCurrent(authority))throw new ScopedUi.Refused("task_not_ready");
            request.remove("sequence");
            String action = request.getString("action");
            if (authority.events) {
                if (leased) throw new ScopedUi.Refused("invalid_request");
                if ("event_status".equals(action)) {
                    if(request.length()!=2)throw new ScopedUi.Refused("invalid_request");
                    success(ws,id,NotificationEvents.status(this));
                } else {
                    MultiAiProtocol.exact(request,"id","action","epoch","after","limit");
                    String epoch=request.getString("epoch");
                    long after=ServiceProtocol.integer(request,"after"),limit=ServiceProtocol.integer(request,"limit");
                    if(!epoch.isEmpty()&&!epoch.matches("[a-f0-9]{32}")||after<0||after>TaskLease.MAX_INTEGER||limit<1||limit>16)throw new ScopedUi.Refused("invalid_request");
                    JSONObject result=NotificationEvents.read(this,owner,request);
                    if(!authorityCurrent(authority))throw new ScopedUi.Refused("permission_denied");
                    success(ws,id,result);
                }
                return;
            }
            if ("ping".equals(action)) {
                if (request.length() != 2) throw new ScopedUi.Refused("invalid_request");
                success(ws, id, RelayApi.json("authorized", true, "persistent", true, "all_apps", true, "allow_control", true,
                        "client", RelayApi.json("platform", "android", "version_name", BuildConfig.VERSION_NAME,
                                "version_code", BuildConfig.VERSION_CODE, "build_type", BuildConfig.BUILD_TYPE),
                        "device_unlocked", ui.unlocked(), "screen_state", ui.screenState().wireName, "auto_wake", true,
                        "network_transport", network(), "last_file_command", fileCommandPerformance(owner),
                        "last_file_performance", FileScopes.performance(owner) == null ? JSONObject.NULL : FileScopes.performance(owner),
                        "capabilities", new org.json.JSONArray().put("companion_v1").put("paged_tree_v1").put("node_text_v1").put("screen_recovery_v1").put("atomic_input_v1").put("system_actions_v1").put("drag_v1").put("gesture_v1").put("semantic_node_v1").put("windows_v1").put("visual_window_v1").put("volume_v1").put("files_v1").put("file_share_v1").put("gallery_publish_v1").put("notification_events_v1").put("touch_session_v1").put("media_capture_v1").put("sim_pin_keypad_v1").put("remote_connections_v1").put("battery_status_v1"),
                        "observation_limits", RelayApi.json("max_inspected_nodes", PagedTree.MAX_NODES, "max_capture_bytes", PagedTree.MAX_CAPTURE_BYTES,
                                "max_page_nodes", PagedTree.MAX_PAGE_NODES, "max_page_bytes", TreeReadBudget.MAX_BYTES, "max_text_chunk_utf16", ObservedText.MAX_CHUNK_UTF16, "snapshot_ttl_ms", 30000),
                        "gesture_limits", RelayApi.json("max_tracks", GesturePlan.trackLimit(android.accessibilityservice.GestureDescription.getMaxStrokeCount()),
                                "max_points_per_track", GesturePlan.MAX_POINTS, "max_duration_ms", GesturePlan.MAX_TOTAL_MS,
                                "max_strokes_per_dispatch", android.accessibilityservice.GestureDescription.getMaxStrokeCount())));
                return;
            }
            if (activeCommand != 0) { error(ws, id, "busy"); return; }
            if("battery_status".equals(action)){
                MultiAiProtocol.exact(request,"id","action");success(ws,id,BatteryState.describe(this));return;
            }
            if("media_status".equals(action)||"media_stop".equals(action)){
                MultiAiProtocol.exact(request,"id","action","capture_id");
                String captureId=request.getString("capture_id");if(!captureId.matches("[a-f0-9]{32}"))throw new ScopedUi.Refused("invalid_request");
                success(ws,id,"media_status".equals(action)?MediaCapture.status(owner,captureId):MediaCapture.stop(owner,captureId));return;
            }
            if("sim_pin_prepare".equals(action)){
                MultiAiProtocol.exact(request,"id","action");success(ws,id,simPinInput.prepare(owner));return;
            }
            if(RemoteConnections.ACTIONS.contains(action))RemoteConnections.validate(request);
            else if(!"sim_pin_submit".equals(action))ui.validateRequest(request);
            else MultiAiProtocol.exact(request,"id","action","challenge","pin");
            long command = ++commandSerial; activeCommand = command;
            String requestId = id;
            CommandTimeline.Span commandTiming = timing;
            if(commandTiming != null) commandTiming.preparing();
            java.util.function.BooleanSupplier commandCurrent = () -> authorityCurrent(authority) && activeCommand == command;
            JSONObject actionRequest=request;
            CompanionUi.Reply reply = new CompanionUi.Reply() {
                private boolean finish() {
                    if (!commandCurrent.getAsBoolean()) {
                        if(commandTiming != null) commandTiming.finish(CommandTimeline.Phase.authority_lost);
                        return false;
                    }
                    activeCommand = 0; return true;
                }
                private void sent(boolean queued) {
                    if(commandTiming != null) commandTiming.finish(queued ? CommandTimeline.Phase.reply_enqueued : CommandTimeline.Phase.reply_rejected);
                }
                public void success(JSONObject value) { if (finish()) sent(PreviewService.this.success(ws, requestId, value)); }
                public void error(String code) { if (finish()) sent(PreviewService.this.error(ws, requestId, code)); }
            };
            if(RemoteConnections.ACTIONS.contains(action)){
                RemoteConnections.execute(this,config,owner,grantFence,request,commandCurrent,reply);return;
            }
            if("sim_pin_submit".equals(action)){try{simPinInput.submit(owner,request,commandCurrent,reply);}catch(ScopedUi.Refused e){reply.error(e.code);}catch(Exception e){reply.error("invalid_request");}return;}
            recovery.prepare(commandCurrent, new ScreenPreparation.Completion() {
                @Override public void ready(boolean recovered) {
                    if (!commandCurrent.getAsBoolean()) {
                        if(commandTiming != null) commandTiming.finish(CommandTimeline.Phase.authority_lost);
                        return;
                    }
                    if (recovered && actionRequest.has("snapshot_id")) { reply.error("fresh_snapshot_required"); return; }
                    if (recovered && actionRequest.has("observation_id")) { reply.error("fresh_observation_required"); return; }
                    if(commandTiming != null) commandTiming.executing();
                    ui.execute(actionRequest, reply, commandCurrent, authority.claim, () -> authorityCurrent(authority));
                }
                @Override public void error(String code) { reply.error(code); }
                @Override public void error(String code, String detail) {
                    if (!commandCurrent.getAsBoolean()) {
                        if(commandTiming != null) commandTiming.finish(CommandTimeline.Phase.authority_lost);
                        return;
                    }
                    activeCommand = 0;
                    boolean queued = ws.send(RelayApi.json("id", requestId, "ok", false,
                            "error", RelayApi.json("code", code, "next_step", detail)).toString());
                    if(commandTiming != null) commandTiming.finish(queued ? CommandTimeline.Phase.reply_enqueued : CommandTimeline.Phase.reply_rejected);
                }
            });
        } catch (ScopedUi.Refused failure) {
            boolean queued = id != null && error(ws, id, failure.code);
            if(timing != null) timing.finish(queued ? CommandTimeline.Phase.reply_enqueued : CommandTimeline.Phase.reply_rejected);
        }
        catch (Exception failure) {
            // Storage failure must stop execution; never proceed without persisted replay protection.
            boolean queued = id != null && error(ws, id, "request_or_storage_failure");
            if(timing != null) timing.finish(queued ? CommandTimeline.Phase.reply_enqueued : CommandTimeline.Phase.reply_rejected);
        }
    }
    private JSONObject fileCommandPerformance(String owner) {
        CommandTimeline.Snapshot s = fileCommandTimeline.snapshot(owner);
        return s == null ? null : RelayApi.json("sequence",s.sequence(),"action",s.action(),"phase",s.phase().name(),
                "elapsed_ms",s.elapsedMs(),"main_queue_ms",s.mainQueueMs(),"admission_ms",s.admissionMs(),
                "preparation_ms",s.preparationMs(),"execution_reply_ms",s.executionReplyMs());
    }
    private boolean success(WebSocket ws, String id, JSONObject value) { return ws.send(RelayApi.json("id", id, "ok", true, "result", value).toString()); }
    private boolean error(WebSocket ws, String id, String code) {
        String next = AtomicInput.nextStep(code);
        if (next == null) next = FileScopes.recoveryHint(code);
        if (next == null) next = WindowInventory.nextStep(code);
        JSONObject details = next == null ? RelayApi.json("code", code) : RelayApi.json("code", code, "next_step", next);
        return ws.send(RelayApi.json("id", id, "ok", false, "error", details).toString());
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
