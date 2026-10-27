package io.airtap.preview;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import org.json.JSONArray;

/** Pair once with an existing personal AI. No technical setup in the normal journey. */
public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConnectionFeedback connectionFeedback = new ConnectionFeedback();
    private EnrollmentStore.Config config;
    private boolean visible, busy, polling, storageError, upgrade, fileStorageBusy;
    private int epoch;
    private final SetupRecovery.Backoff setupBackoff = new SetupRecovery.Backoff(), pollBackoff = new SetupRecovery.Backoff();
    private okhttp3.Call setupCall, pollCall;
    private Runnable setupRetry;
    private long nextPoll;
    private boolean setupPaused, setupExhausted, invitationUncertain;
    private AlertDialog preparationDialog;
    private String ticket, claim, code, candidate, message = "", renderKey = "";
    private long invitationExpiry;
    private InvitationArtifacts invitationArtifacts;
    private LinearLayout content;
    private TextView feedback;
    private UpdateNotice updates;
    private final FileGrantAttempt fileGrantAttempt = new FileGrantAttempt();
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            PreviewService service = PreviewService.instance;
            if (service != null && !service.isAuthorized()) service.restore();
            continueSetup(); render(); refreshPreparation(); poll(); main.postDelayed(this, 2000);
        }
    };
    @Override public void onCreate(Bundle saved) {
        setTheme(R.style.AirtapTheme); super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        try { config = new EnrollmentStore(this).read(); PreviewState.config = config; }
        catch (Exception failure) { storageError = true; message = "无法读取原连接，请检查应用数据；不会覆盖原手机身份。"; }
        upgrade = config != null && (!config.publicService() || config.enrolled()) && getPreferences(0).getInt("protocol", 0) != 4;
        if (saved != null && !upgrade && config != null && config.url.equals(saved.getString("identity"))) {
            try {
                InvitationArtifacts restored = new InvitationArtifacts(saved.getString("identity"), saved.getString("invitationIdentityFingerprint"),
                        saved.getString("ticket"), saved.getLong("invitationExpiry"), saved.getString("invitationCliSha256"), saved.getString("invitationGuideSha256"));
                if (restored.matches(config.url, config.token, restored.ticket(), restored.expiry(), System.currentTimeMillis()/1000)) {
                    invitationArtifacts = restored; ticket = restored.ticket(); invitationExpiry = restored.expiry();
                }
            } catch (RuntimeException invalid) { /* Old/incomplete/expired saved invitations are never exported without their expected digests. */ }
        }
        LinearLayout root = AppUi.root(this);
        root.addView(AppUi.header(this, AppUi.text(this, "Airtap", 18), button("设置", v -> settings())));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = AppUi.column(this, 24); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        feedback = AppUi.text(this, "", 14); feedback.setPadding(24, 8, 24, 16); root.addView(feedback);
        setContentView(root); root.requestApplyInsets(); render();
        updates = new UpdateNotice(this); updates.check(false);
    }
    private Button button(String name, View.OnClickListener click) {
        Button b = new Button(this) {
            @Override public boolean onFilterTouchEventForSecurity(android.view.MotionEvent event) {
                return (event.getFlags() & (android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED | android.view.MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)) == 0
                        && super.onFilterTouchEventForSecurity(event);
            }
        };
        b.setText(name); b.setFilterTouchesWhenObscured(true); b.setOnClickListener(click); AppUi.button(b, AppUi.SECONDARY); return b;
    }
    private void primary(String name, View.OnClickListener click) { Button b = button(name, click); b.setEnabled(!busy && !storageError); AppUi.button(b, AppUi.PRIMARY); content.addView(b); }
    private void heading(String value) { TextView title = AppUi.text(this, value, 28); AppUi.heading(title); content.addView(title); }
    private void paragraph(String value) { content.addView(AppUi.text(this, value, 16)); AppUi.space(content, 16); }
    private JSONObject trust() {
        try { return new TrustedGrant(this).read(config); }
        catch (Exception failure) { storageError = true; message = "暂时无法读取已保存的 AI 连接，请检查手机存储。原记录已保留，无需先重新配对。"; return null; }
    }
    private JSONObject pendingApproval() {
        try { return new TrustedGrant(this).pending(config); }
        catch (Exception failure) { storageError = true; message = "暂时无法读取已保存的 AI 连接，请检查手机存储。原记录已保留，无需先重新配对。"; return null; }
    }
    private void render() {
        if (content == null || isDestroyed()) return;
        PreviewService service = PreviewService.instance;
        boolean enrolled = config != null && config.enrolled(), accessibility = service != null, notifications = SessionService.notificationsAllowed(this);
        JSONObject trusted = trust(), pending = trusted == null ? pendingApproval() : null;
        message = connectionFeedback.reconcile(message, trusted == null ? null : trusted.optString("claim"), pending == null ? null : pending.optString("claim"));
        long now = System.currentTimeMillis() / 1000;
        if (ticket != null && invitationExpiry <= now) { ticket = null; invitationArtifacts = null; claim = null; code = null; message = "邀请已过期，点下面按钮重新生成即可。"; }
        RecoveryStatus recovery = service == null ? RecoveryStatus.WAITING_FOR_AI : service.recoveryStatus();
        String key = recovery + ":" + invitationUncertain + ":" + setupExhausted + ":" + enrolled + ":" + accessibility + ":" + notifications + ":" + (trusted != null) + ":" + (pending == null ? "" : pending.optString("approval_id")) + ":" + (ticket != null) + ":" + claim + ":" + busy + ":" + (service != null && service.isConnected());
        feedback.setText(message);
        if (key.equals(renderKey)) return; renderKey = key; content.removeAllViews();
        if (upgrade || !enrolled || (accessibility && notifications)) {
            content.addView(AppUi.deviceIdentity(this)); AppUi.space(content, 24);
        }
        if (upgrade) {
            heading("连接新版 Airtap"); paragraph("新版让你的 AI 一次连接、持续使用整台手机。点击下面按钮准备新连接，再把邀请交给你的 AI。原来的 AI 连接会解除。");
            primary("开始新版连接", v -> switchService(BuildConfig.PUBLIC_RELAY_ORIGIN));
        } else if (!enrolled) {
            heading("给你的 AI 一部手机"); paragraph("Airtap 装在你想交给 AI 使用的这台安卓手机上。保持手机联网即可，无需 USB 或电脑。");
            paragraph("按提示准备手机，再把邀请发给你已有的个人 AI。通常只需连接一次。"); primary(busy ? "正在连接…" : "开始使用", v -> enroll());
            content.addView(button("第一次使用？看接入步骤", v -> updates.open("/help#start")));
        } else if (!accessibility || !notifications) {
            permissions(accessibility);
            if (trusted != null || pending != null) paragraph("原 AI 连接已保留。恢复上面这一项后会继续连接，无需重新配对。");
        } else if (trusted != null) {
            heading(recoveryTitle(recovery));
            paragraph(recoveryDetail(recovery));
            paragraph("回到自己的 AI 对话，直接告诉它你想做什么。已保留整台手机的授权，无需每次重新配对。你随时可以停止并解除连接。");
            content.addView(button("远程使用准备", v -> remotePreparation()));
            primary("回到手机桌面", v -> startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            content.addView(button("停止并解除 AI 连接", v -> disconnect()));
        } else if (pending != null) {
            heading("正在完成 AI 连接");
            paragraph("已记住你刚才核对并确认的 AI。网络恢复后会继续完成连接，无需再次确认；完成前不会开放手机操作。");
            primary("停止并取消这次连接", v -> disconnect());
        } else if (claim != null) {
            heading("这是你自己的 AI 吗？"); paragraph(candidate + " 请求连接（名称由对方提供）。请与 AI 告诉你的六位数字核对：");
            content.addView(AppUi.text(this, code.substring(0, 3) + " " + code.substring(3), 44)); AppUi.space(content, 20);
            paragraph("确认后，你的 AI 可以持续查看和操作整台手机，包括按需亮屏、切换应用、点击和输入。需要做什么，由你告诉自己的 AI。你随时可以停止并解除连接。");
            primary(busy ? "正在连接…" : "数字一致，连接我的 AI", v -> approve()); content.addView(button("不是我的 AI，取消", v -> disconnect()));
        } else if (invitationUncertain) {
            heading(busy ? "正在核查邀请" : "继续连接自己的 AI");
            paragraph("原手机身份已保留。Airtap 会先核查服务上的邀请，不会因一次网络中断自动换掉已有邀请或 AI 连接。");
            primary(busy ? "正在核查…" : "再次检查邀请状态", v -> checkInvitation(true));
            content.addView(button("重新生成邀请", v -> invite()));
        } else if (ticket != null) {
            heading("把邀请发给你的 AI"); paragraph("复制或分享这台手机生成的完整邀请，发到你自己的私人 AI 对话。AI 告诉你六位数字后，回到 Airtap 核对并确认。");
            primary("复制接入邀请", v -> copy()); content.addView(button("分享给我的 AI", v -> share()));
            content.addView(button("查看接入邀请", v -> {
                TextView text = AppUi.text(this, prompt(), 14); text.setTextIsSelectable(true); text.setPadding(24, 24, 24, 24);
                ScrollView scroll = new ScrollView(this); scroll.addView(text);
                AlertDialog dialog = new AlertDialog.Builder(this).setTitle("我的 AI 接入邀请").setView(scroll).setPositiveButton("返回", null).create();
                dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); dialog.show();
            }));
            paragraph("正在等待你的 AI…\n邀请有效期为 5 分钟，接入成功后无需重复发送。"); content.addView(button("重新生成邀请", v -> invite()));
        } else {
            heading("连接你自己的 AI"); paragraph("手机已准备好。把邀请交给你已有的个人 AI，核对一次数字后即可使用。");
            paragraph("使用你平时的个人 AI 对话即可。AI 需要能调用工具，让它按邀请自行检查；你不用运行命令或安装 Codex 插件。"); content.addView(button("远程使用准备", v -> remotePreparation())); primary(busy ? "正在准备…" : "邀请我的 AI", v -> invite());
            content.addView(button("第一次使用？看接入步骤", v -> updates.open("/help#start")));
        }
    }
    private void permissionStep(String title, String detail) {
        LinearLayout row = AppUi.section(content, title, "");
        row.addView(AppUi.text(this, detail, 15));
    }
    private void permissions(boolean accessibility) {
        if (!accessibility) {
            paragraph("准备手机 · 第 1 步，共 2 步");
            heading("让 AI 能操作手机");
            paragraph("开启后，Airtap 才能让你的 AI 读取屏幕、点击和输入。系统把这个开关放在“无障碍”里。");
            boolean attempted = getPreferences(0).getBoolean("operation_setup_started", false);
            if (attempted) {
                TextView status = AppUi.text(this, "还没检测到开启，按下面的步骤继续即可。开启成功后，这里会自动进入下一步。", 15);
                AppUi.inset(status, false); content.addView(status);
            }
            permissionStep("1  找到「Airtap 手机操作」", "稍后会打开系统设置。如果先看到无障碍列表，在“已下载的应用”或“已安装的服务”里找 Airtap。");
            permissionStep("2  打开里面的开关", "开关可能叫“使用 Airtap 手机操作”或“启用服务”。按系统提示确认即可。");
            permissionStep("3  返回 Airtap", "用返回键或返回手势回到这里，也可以从桌面重新打开 Airtap。我们会自动检查是否成功。");
            primary(attempted ? "继续开启手机操作" : "去系统设置开启", v -> openOperationSettings(false));
            content.addView(button("找不到 Airtap？", v -> missingOperationHelp()));
            content.addView(button("提示“受限设置”？", v -> restrictedOperationHelp()));
        } else {
            paragraph("准备手机 · 第 2 步，共 2 步");
            heading("保持连接通知可见");
            TextView success = AppUi.text(this, "✓ 手机操作已开启", 16);
            AppUi.inset(success, false); content.addView(success);
            paragraph("通知会显示 AI 的连接状态，你也可以从通知里随时停止连接。");
            boolean asked = getPreferences(0).getBoolean("notification_setup_started", false);
            boolean needsSettings = asked && !shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS);
            paragraph(needsSettings ? "在系统通知设置里打开“允许通知”，再返回 Airtap。" : "点击下面的按钮，在系统提示中选择“允许”。完成后会自动进入邀请 AI 的页面。");
            primary(needsSettings ? "打开通知设置" : "允许连接通知", v -> {
                boolean canRequest = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        && (!asked || shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS));
                getPreferences(0).edit().putBoolean("notification_setup_started", true).apply();
                message = ""; renderKey = "";
                if (canRequest) requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
                else {
                    try { startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())); }
                    catch (Exception failure) { change("请打开手机设置 → 应用 → Airtap → 通知，打开“允许通知”后返回。"); }
                }
            });
        }
    }
    private void openOperationSettings(boolean listOnly) {
        getPreferences(0).edit().putBoolean("operation_setup_started", true).apply();
        message = ""; renderKey = "";
        android.content.ComponentName component = new android.content.ComponentName(this, PreviewService.class);
        if (!listOnly) {
            try {
                // This optional system action accepts a flattened component name; some phones only expose the list.
                startActivity(new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                        .putExtra(Intent.EXTRA_COMPONENT_NAME, component.flattenToString()));
                return;
            } catch (android.content.ActivityNotFoundException | SecurityException missingDetailPage) { }
        }
        try {
            Bundle highlight = new Bundle(); highlight.putString(":settings:fragment_args_key", component.flattenToString());
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .putExtra(":settings:fragment_args_key", component.flattenToString())
                    .putExtra(":settings:show_fragment_args", highlight));
        } catch (android.content.ActivityNotFoundException | SecurityException failure) {
            change("请打开手机设置，搜索“无障碍”，再按上面的步骤开启 Airtap 手机操作。");
        }
    }
    private void missingOperationHelp() {
        new AlertDialog.Builder(this).setTitle("在列表里找到 Airtap")
                .setMessage("1. 打开系统的“无障碍”页面。\n\n2. 找到“已下载的应用”“已安装的应用”或“已安装的服务”（名称因手机而异）。\n\n3. 点“Airtap 手机操作”，打开里面的开关。\n\n完成后用返回键回到 Airtap。")
                .setPositiveButton("打开无障碍列表", (dialog, which) -> openOperationSettings(true))
                .setNegativeButton("返回", null).show();
    }
    private void restrictedOperationHelp() {
        new AlertDialog.Builder(this).setTitle("系统提示“受限设置”")
                .setMessage("如果安装包来自 airtap.memtrix.dev，且你希望让自己的 AI 操作手机，可按下面的步骤继续：\n\n1. 点击下方按钮打开 Airtap 应用信息。\n\n2. 在右上角“⋮”菜单中选择“允许受限制的设置”，按系统提示确认。部分手机的入口名称不同。\n\n3. 返回 Airtap，再点“继续开启手机操作”。\n\n没有这个菜单时，先回到无障碍列表查看 Airtap 能否直接开启。")
                .setPositiveButton("打开 Airtap 应用信息", (dialog, which) -> {
                    getPreferences(0).edit().putBoolean("operation_setup_started", true).apply();
                    message = ""; renderKey = "";
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + getPackageName()))); }
                    catch (android.content.ActivityNotFoundException | SecurityException failure) { change("请打开手机设置 → 应用 → Airtap，按帮助中的步骤继续。"); }
                }).setNegativeButton("返回", null).show();
    }
    private void change(String text) { message = text; renderKey = ""; render(); }
    private boolean currentSetup(int run, EnrollmentStore.Config identity) {
        return visible && !isDestroyed() && run == epoch && config == identity;
    }
    private void fenceSetup() {
        ++epoch;
        if (setupCall != null) setupCall.cancel();
        if (pollCall != null) pollCall.cancel();
        if (setupRetry != null) main.removeCallbacks(setupRetry);
        setupCall = null; pollCall = null; setupRetry = null; busy = false; polling = false;
        nextPoll = 0; pollBackoff.reset();
    }
    private boolean invitationOutstanding() {
        return config != null && config.url.equals(getPreferences(0).getString("invitation_identity", ""));
    }
    private void continueSetup() {
        if (!visible || storageError || upgrade || busy || setupExhausted) return;
        if (trust() != null || pendingApproval() != null) {
            getPreferences(0).edit().remove("setup_continue").remove("invitation_identity").apply();
            invitationUncertain = false; return;
        }
        if (storageError) return;
        if (config != null && !config.enrolled() && getPreferences(0).getBoolean("setup_continue", false)) { enroll(); return; }
        if (config == null || !config.enrolled() || PreviewService.instance == null || !SessionService.notificationsAllowed(this)) return;
        if (setupPaused) { setupPaused = false; if (invitationOutstanding() && ticket == null && claim == null) checkInvitation(false); }
        if (busy || ticket != null || claim != null || invitationUncertain) return;
        if (invitationOutstanding()) { checkInvitation(false); return; }
        if (getPreferences(0).getBoolean("setup_continue", false)) invite();
    }
    private void setupFailed(String text) { busy = false; setupExhausted = true; change(text); }
    private void retrySetup(int run, EnrollmentStore.Config identity, Runnable retry, String exhausted) {
        long delay = setupBackoff.nextDelay();
        if (delay < 0) { setupFailed(exhausted); return; }
        change("连接暂时中断，正在自动检查并继续…");
        setupRetry = () -> { setupRetry = null; if (currentSetup(run, identity)) retry.run(); };
        main.postDelayed(setupRetry, delay);
    }
    private void enroll() {
        if (busy || storageError) return;
        String origin = config != null ? config.origin() : getPreferences(0).getString("service", BuildConfig.PUBLIC_RELAY_ORIGIN);
        try {
            origin = RelayAddress.requireRoot(origin);
            if (config == null) {
                EnrollmentStore.Config next = new EnrollmentStore.Config(origin, EnrollmentStore.token().substring(0, 32), EnrollmentStore.token(), false);
                new EnrollmentStore(this).save(next); config = next; PreviewState.config = next;
            }
            if (!getPreferences(0).edit().putBoolean("setup_continue", true).commit()) throw new IllegalStateException();
        } catch (Exception failure) { change("无法保存接入准备，请检查服务地址和手机存储。原手机身份不会被覆盖。"); return; }
        fenceSetup(); setupBackoff.reset(); setupExhausted = false; busy = true;
        int run = epoch; EnrollmentStore.Config identity = config; change("正在连接服务…");
        discoverEnrollment(run, identity);
    }
    private void discoverEnrollment(int run, EnrollmentStore.Config identity) {
        setupCall = RelayApi.requestStatus(identity.origin(), "/v1/service", "GET", null, null, (body, status, error) -> {
            if (!currentSetup(run, identity)) return;
            if (error != null) {
                if (SetupRecovery.transientFailure(status)) retrySetup(run, identity, () -> discoverEnrollment(run, identity), "暂时连不上服务。手机身份已保留，联网后点“开始使用”继续即可。");
                else setupFailed("服务暂时无法接入。" + error);
                return;
            }
            try { ServiceProtocol.discovery(body); checkEnrollment(run, identity); }
            catch (Exception invalid) { setupFailed("服务版本不匹配，请检查安装包与服务。"); }
        });
    }
    private void checkEnrollment(int run, EnrollmentStore.Config identity) {
        setupCall = RelayApi.requestStatus(identity.url, "/v1/device/pairing", "GET", identity.token, null, (body, status, error) -> {
            if (!currentSetup(run, identity)) return;
            try {
                if (error == null) {
                    SetupRecovery.Pairing state = SetupRecovery.pairing(body);
                    if (!"idle".equals(state.state()) && !"revoked".equals(state.state())
                            && !getPreferences(0).edit().putString("invitation_identity", identity.url).remove("setup_continue").commit())
                        throw new IllegalStateException();
                    finishEnrollment(identity); return;
                }
                // Enrollment is idempotent for this exact ID/token. 503 can be a reserved
                // identity whose initial activation was interrupted; no new identity is made.
                if (status == 404 || status == 503) { postEnrollment(run, identity); return; }
                if (SetupRecovery.transientFailure(status)) retrySetup(run, identity, () -> checkEnrollment(run, identity), "登记状态暂时无法确认。手机身份已保留，联网后点“开始使用”继续。");
                else setupFailed("原手机登记无法继续。" + error);
            } catch (Exception invalid) { setupFailed("登记状态无法读取。原手机身份已保留，请检查服务版本或稍后重试。"); }
        });
    }
    private void postEnrollment(int run, EnrollmentStore.Config identity) {
        setupCall = RelayApi.requestStatus(identity.origin(), "/v1/enroll", "POST", null,
                RelayApi.json("device_id", identity.deviceId, "device_token", identity.token), (body, status, error) -> {
            if (!currentSetup(run, identity)) return;
            try {
                if (error == null) { ServiceProtocol.enrollment(body, identity.deviceId); finishEnrollment(identity); return; }
                if (!SetupRecovery.transientFailure(status) && status != 200) { setupFailed("登记尚未完成。" + error); return; }
            } catch (Exception invalid) { /* A lost/malformed acknowledgement still requires a read before retry. */ }
            retrySetup(run, identity, () -> checkEnrollment(run, identity), "登记尚未确认，手机身份已保留。联网后点“开始使用”即可继续。");
        });
    }
    private void finishEnrollment(EnrollmentStore.Config identity) throws Exception {
        EnrollmentStore.Config next = new EnrollmentStore.Config(identity.origin(), identity.deviceId, identity.token, true);
        if (!getPreferences(0).edit().putInt("protocol", 4).commit()) throw new IllegalStateException();
        new EnrollmentStore(this).save(next);
        config = next; PreviewState.config = next; busy = false; setupBackoff.reset(); change(""); continueSetup();
    }
    private void invite() {
        if (busy || config == null || trust() != null || pendingApproval() != null || storageError) return;
        try {
            new TrustedGrant(this).stop();
            // Persist an unknown-result marker BEFORE the one non-idempotent request.
            if (!getPreferences(0).edit().putString("invitation_identity", config.url).remove("setup_continue").commit()) throw new IllegalStateException();
        } catch (Exception failure) { change("无法保存邀请准备，请检查手机存储后重试。"); return; }
        fenceSetup(); setupBackoff.reset(); setupExhausted = false; invitationUncertain = false;
        connectionFeedback.clear(); busy = true; int run = epoch; EnrollmentStore.Config identity = config;
        ticket = null; invitationArtifacts = null; claim = null; code = null; invitationExpiry = 0; change("正在准备邀请…");
        setupCall = RelayApi.requestTimedStatus(identity.url, "/v1/device/pairing", "POST", identity.token, null, (body, status, error, serverTime) -> {
            if (!currentSetup(run, identity)) return;
            if (error == null) {
                try {
                    long expires = InvitationFeedback.validate(body, System.currentTimeMillis()/1000, serverTime);
                    String receivedTicket = body.getString("ticket");
                    InvitationArtifacts artifacts = InvitationArtifacts.fromResponse(identity.url, identity.token, receivedTicket, expires, body);
                    invitationArtifacts = artifacts; invitationExpiry = expires; ticket = receivedTicket; busy = false; change(""); return;
                } catch (InvitationFeedback.Rejected failure) {
                    invitationUncertain = true; busy = false; setupExhausted = true; change(failure.userMessage); return;
                } catch (Exception invalid) { /* Check the stored server state, never replay POST. */ }
            }
            invitationUncertain = true; change("邀请结果尚未确认，正在核查原邀请…"); queryInvitation(run, identity);
        });
    }
    private void checkInvitation(boolean manual) {
        if (busy || config == null || trust() != null || pendingApproval() != null) return;
        fenceSetup(); if (manual) setupBackoff.reset(); setupExhausted = false; invitationUncertain = true; busy = true;
        change("正在核查原邀请…"); queryInvitation(epoch, config);
    }
    private void queryInvitation(int run, EnrollmentStore.Config identity) {
        setupCall = RelayApi.requestStatus(identity.url, "/v1/device/pairing", "GET", identity.token, null, (body, status, error) -> {
            if (!currentSetup(run, identity)) return;
            if (error != null) {
                if (SetupRecovery.transientFailure(status)) retrySetup(run, identity, () -> queryInvitation(run, identity), "暂时无法核实邀请状态。手机身份没有变化，联网后点“再次检查邀请状态”即可。");
                else setupFailed("邀请状态暂时无法读取。" + error);
                return;
            }
            try {
                SetupRecovery.Pairing state = SetupRecovery.pairing(body); busy = false; setupExhausted = true;
                if ("pending".equals(state.state())) {
                    claim = state.claim(); code = state.code(); candidate = state.name(); invitationExpiry = state.expires();
                    invitationUncertain = false; change("已找回 AI 的连接请求，请核对六位数字。");
                } else if ("waiting".equals(state.state())) {
                    change("服务已生成邀请，但这次没有收到邀请正文。已发出的邀请仍可使用；若还没发给 AI，点“重新生成邀请”取得新邀请即可。");
                } else if ("approved".equals(state.state())) {
                    change("服务显示已有 AI 连接，本机确认记录尚未匹配。请先查看原 AI 的连接状态；不会自动替换它。");
                } else {
                    change("已确认当前没有有效邀请，点“重新生成邀请”即可继续。");
                }
            } catch (Exception invalid) { setupFailed("邀请状态格式无法确认。原手机身份已保留，请检查服务版本。"); }
        });
    }
    private String prompt() {
        if (ticket == null || config == null || invitationArtifacts == null
                || !invitationArtifacts.matches(config.url, config.token, ticket, invitationExpiry, System.currentTimeMillis()/1000)) return null;
        return "请接入我的 Airtap 手机。\n" + invitationArtifacts.verificationInstructions()
                + "\n接入说明：\n" + config.url + "/agent.md" + "\nPython CLI：\n" + config.url + "/cli/airtap.py"
                + "\n手机服务地址：\n" + config.url + "\n接入票据（仅下面这一行，勿公开或写入日志）：\nAIRTAP_PAIRING_TICKET=" + ticket
                + "\n按说明自行选择你能使用的接入工具并申请连接，不需要让我选择技术路线或运行命令。立即把六位核对码告诉我，等我在手机确认。"
                + "连接成功后保存私有连接配置供以后复用，任务结束不要自动撤销。根据我的需求使用手机，不预设只支持少数 App 或任务，已经明确授权的操作无需重复确认。"
                + "手机可能不在我身边，先根据设备能力尝试自动亮屏和恢复连接；确需我处理时说明具体原因与最少步骤。执行后观察结果，未知动作不要重复提交。";
    }
    private void copy() {
        String value = prompt(); if (value == null) return; ClipData clip = ClipData.newPlainText("我的 AI 手机邀请", value);
        android.os.PersistableBundle extras = new android.os.PersistableBundle(); extras.putBoolean("android.content.extra.IS_SENSITIVE", true); clip.getDescription().setExtras(extras);
        getSystemService(ClipboardManager.class).setPrimaryClip(clip); change("已复制，粘贴到你自己的 AI 对话即可。");
    }
    private void share() { String value = prompt(); if (value != null) startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value), "发送给自己的 AI")); }
    private void poll() {
        if (!visible || polling || busy || (ticket == null && !invitationUncertain && claim == null) || config == null || pendingApproval() != null || System.currentTimeMillis() < nextPoll) return;
        polling = true; int run = epoch; EnrollmentStore.Config identity = config;
        pollCall = RelayApi.requestStatus(identity.url, "/v1/device/pairing", "GET", identity.token, null, (body, status, error) -> {
            if (!currentSetup(run, identity)) return;
            polling = false;
            if (error != null) {
                long delay = SetupRecovery.transientFailure(status) ? pollBackoff.nextDelay() : -1;
                nextPoll = delay < 0 ? Long.MAX_VALUE : System.currentTimeMillis() + delay;
                change(delay < 0 ? "暂时无法检查 AI 的接入请求。原邀请未更换，返回此页可继续检查。" : "网络暂时中断，正在自动检查 AI 的接入请求…"); return;
            }
            try {
                SetupRecovery.Pairing state = SetupRecovery.pairing(body); pollBackoff.reset(); nextPoll = 0;
                if ("pending".equals(state.state())) {
                    boolean changed = !state.claim().equals(claim) || !state.code().equals(code) || !state.name().equals(candidate) || invitationUncertain;
                    claim = state.claim(); code = state.code(); candidate = state.name(); invitationUncertain = false;
                    invitationExpiry = state.expires(); if (changed) change("");
                } else if ("revoked".equals(state.state()) || "idle".equals(state.state())) {
                    ticket = null; invitationArtifacts = null; claim = null; code = null; invitationUncertain = false;
                    getPreferences(0).edit().remove("invitation_identity").apply();
                    change("当前邀请已结束，需要时可重新邀请自己的 AI。");
                } else if (message.startsWith("网络暂时中断，正在自动检查") || message.startsWith("暂时无法检查 AI")) {
                    change("");
                }
            } catch (Exception invalid) { nextPoll = Long.MAX_VALUE; change("接入状态暂时无法读取，请检查服务版本；原邀请未更换。"); }
        });
    }
    private void approve() {
        if (busy || claim == null || config == null || PreviewService.instance == null || !SessionService.notificationsAllowed(this)) return;
        ++epoch; String approvedClaim = claim;
        try {
            // Persist consent before HTTP. Completion belongs to this intent, not this Activity.
            String intentId = new TrustedGrant(this).beginApproval(config, approvedClaim, candidate);
            ticket = null; invitationArtifacts = null; claim = null; code = null; invitationExpiry = 0;
            if (intentId == null) {
                connectionFeedback.approved(approvedClaim);
                PreviewService.instance.restore(); change("已记住你的 AI，以后可以直接使用。"); return;
            }
            // The service owns completion and retry immediately, even if Back destroys this
            // Activity before its next refresh. UI callbacks must never own connection liveness.
            PreviewService.instance.restore();
            change("正在完成你已确认的 AI 连接…");
        } catch (Exception failure) {
            change(pendingApproval() != null ? "暂时未能完成连接；已保存的确认会自动重试。若一直无法连接，可取消后重新邀请。"
                    : "确认未能保存，请检查手机存储后重新连接。");
        }
    }
    private void disconnect() {
        fenceSetup(); int run = epoch; setupExhausted = true; invitationUncertain = false;
        getPreferences(0).edit().remove("setup_continue").remove("invitation_identity").apply();
        ticket = null; invitationArtifacts = null; claim = null; code = null; invitationExpiry = 0;
        connectionFeedback.clear();
        PreviewState.stop("已停止并解除本机连接"); new TrustedGrant(this).stop(); change("手机控制已停止。");
        if (config != null) RelayApi.request(config.url, "/v1/device/revoke", "POST", config.token, null, (body, error) -> {
            if (run != epoch || isDestroyed()) return; change(error == null ? "已解除连接。需要时可重新邀请自己的 AI。" : "手机已停止，服务端解除尚未确认；联网后可在设置中重试解除。");
        });
    }
    private void settings() {
        LinearLayout layout = AppUi.column(this, 24);
        layout.addView(AppUi.text(this, "让自己的 AI 使用这台手机", 16));
        layout.addView(button("检查更新", v -> updates.check(true)));
        layout.addView(button("使用帮助", v -> updates.open("/help")));
        layout.addView(button("文件访问", v -> fileAccess()));
        if (config != null && config.enrolled() && config.publicService()) {
            layout.addView(button("问题反馈", v -> startActivity(new Intent(this, FeedbackActivity.class))));
        }
        layout.addView(button("远程使用准备", v -> remotePreparation()));
        layout.addView(button("版本与连接详情", v -> new AlertDialog.Builder(this).setTitle("关于 Airtap")
                .setMessage("版本：" + BuildConfig.VERSION_NAME + "\n服务：" + (config == null ? "未连接" : config.origin()))
                .setPositiveButton("知道了", null).show()));
        layout.addView(button("后台运行设置", v -> backgroundPreparation()));
        layout.addView(button("重试解除 AI 连接", v -> disconnect()));
        {
            EditText address = new EditText(this); address.setHint("进阶：自托管服务 HTTPS 地址"); address.setSingleLine(true); layout.addView(address);
            layout.addView(button("保存自托管地址", v -> {
                try { switchService(RelayAddress.requireRoot(address.getText().toString())); }
                catch (Exception failure) { change("请输入有效的 HTTPS 服务根地址。"); }
            }));
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(layout);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("设置").setView(scroll).setPositiveButton("返回", null).create();
        if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); dialog.show();
    }
    private String backgroundInstructions() {
        String maker = (android.os.Build.MANUFACTURER + " " + android.os.Build.BRAND).toLowerCase(java.util.Locale.ROOT);
        if (maker.contains("meizu")) return "魅族手机通常需要检查两处：\n1. Airtap 应用信息 → 耗电和后台 → 手动管理 → 允许后台运行。\n2. 电池优化设置 → Airtap → 允许后台使用 → 选择‘无限制’。\n只需设置 Airtap。菜单名称因系统版本而异，开启开关不代表已经验证成功；完成后让 AI 测试息屏后的任务。";
        return "请在 Airtap 的应用信息中允许后台运行，再检查电池优化设置，为 Airtap 选择‘无限制’或‘不优化’。不同手机的名称可能不同；只需设置 Airtap。完成后让 AI 测试一次息屏后的任务，确认能正常收到并执行。";
    }
    private void backgroundPreparation() {
        new AlertDialog.Builder(this).setTitle("保持后台连接")
                .setMessage(backgroundInstructions() + "\n\n完成后返回 Airtap，已有配对会保留。")
                .setPositiveButton("打开 Airtap 应用信息", (dialog, which) -> {
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + getPackageName()))); }
                    catch (Exception unavailable) { change(backgroundInstructions()); }
                }).setNeutralButton("电池优化设置", (dialog, which) -> {
                    try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                    catch (Exception unavailable) { change("请在系统设置里搜索电池优化，找到 Airtap 并允许后台使用。" + backgroundInstructions()); }
                }).setNegativeButton("返回", null).show();
    }
    private static String recoveryTitle(RecoveryStatus status) {
        return switch (status) {
            case CONNECTED -> "你的 AI 已连接";
            case SCREEN_PREPARABLE -> "已连接，任务到来时唤醒";
            case SYSTEM_AUTH_REQUIRED -> "请完成手机解锁验证";
            case SECURE_CONNECTION_REQUIRED -> "请检查安全连接更新";
            case NOTIFICATIONS_REQUIRED -> "请开启连接通知";
            case NOTIFICATION_START_RETRY -> "正在恢复连接通知";
            case NETWORK_RECONNECTING -> "网络恢复后自动连接";
            case SCREEN_UNAVAILABLE -> "已连接，屏幕暂不可用";
            case WAITING_FOR_AI, VERIFYING_CONNECTION -> "正在核实原 AI 连接";
        };
    }
    private static String recoveryDetail(RecoveryStatus status) {
        return switch (status) {
            case CONNECTED -> "连接正常，可以让自己的 AI 开始使用。";
            case SCREEN_PREPARABLE -> "手机正在息屏或显示系统允许退出的锁屏。任务到来时会尝试自动唤醒，你不用一直点亮屏幕。";
            case SYSTEM_AUTH_REQUIRED -> "系统当前要求密码或生物识别，需要本人在手机完成验证。验证后会恢复原连接，AI 再查看屏幕继续。";
            case SECURE_CONNECTION_REQUIRED -> "无法验证服务的安全连接。原 AI 配对已保留，请检查 Airtap 和系统更新；不要关闭证书校验或重新配对来绕过这个问题。";
            case NOTIFICATIONS_REQUIRED -> "连接记录仍在。开启 Airtap 的系统通知后会自动继续，无需重新配对。";
            case NOTIFICATION_START_RETRY -> "系统暂时没有启动连接通知，Airtap 正在自动重试。原 AI 授权已保留；持续未恢复时，可查看后台运行设置。";
            case NETWORK_RECONNECTING -> "当前网络或服务连接中断，Airtap 会自动重连。保持手机联网即可，无需重新发送邀请。";
            case SCREEN_UNAVAILABLE -> "连接已经建立，但系统暂时无法准备屏幕。AI 会收到具体状态；可查看远程使用准备中的系统检查。";
            case WAITING_FOR_AI, VERIFYING_CONNECTION -> "正在读取并核实已保存的连接，完成后会自动继续，不需要再次核码。";
        };
    }
    private RemoteReadiness.Snapshot readiness() {
        RemoteReadiness.Network network = RemoteReadiness.Network.UNKNOWN;
        Boolean battery = null, secure = null, authentication = null;
        try {
            android.net.ConnectivityManager manager = getSystemService(android.net.ConnectivityManager.class);
            android.net.Network active = manager == null ? null : manager.getActiveNetwork();
            android.net.NetworkCapabilities capabilities = active == null ? null : manager.getNetworkCapabilities(active);
            network = active == null ? RemoteReadiness.Network.OFFLINE
                    : capabilities != null && capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    ? RemoteReadiness.Network.AVAILABLE : RemoteReadiness.Network.UNCONFIRMED;
        } catch (Exception unavailable) { /* A missing check is not a passed check. */ }
        try {
            android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
            if (power != null) battery = power.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception unavailable) { }
        try {
            android.app.KeyguardManager keyguard = getSystemService(android.app.KeyguardManager.class);
            if (keyguard != null) { secure = keyguard.isDeviceSecure(); authentication = keyguard.isDeviceLocked(); }
        } catch (Exception unavailable) { }
        return new RemoteReadiness.Snapshot(network, PreviewService.instance != null,
                SessionService.notificationsAllowed(this), battery, secure, authentication);
    }
    private void refreshPreparation() {
        if (preparationDialog != null && preparationDialog.isShowing()) preparationDialog.setMessage(readiness().description());
    }
    private void remotePreparation() {
        preparationDialog = new AlertDialog.Builder(this).setTitle("远程使用准备")
                .setMessage(readiness().description())
                .setPositiveButton("查看后台运行设置", (dialog, which) -> backgroundPreparation())
                .setNegativeButton("知道了", null)
                .setNeutralButton("查看屏幕锁定设置", (dialog, which) -> {
                    try { startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); }
                    catch (Exception unavailable) { change("请在手机系统设置里查看屏幕锁定方式。"); }
                }).create();
        preparationDialog.setOnDismissListener(dialog -> preparationDialog = null);
        preparationDialog.show();
    }
    private void switchService(String address) {
        if (busy || storageError) return;
        try {
            String origin = RelayAddress.requireRoot(address);
            fenceSetup(); setupExhausted = false; invitationUncertain = false;
            getPreferences(0).edit().remove("setup_continue").remove("invitation_identity").apply();
            connectionFeedback.clear();
            EnrollmentStore.Config previous = config;
            PreviewState.stop("正在准备新连接"); new TrustedGrant(this).stop();
            // Save the new identity atomically before replacing the displayed configuration.
            EnrollmentStore.Config next = new EnrollmentStore.Config(origin, EnrollmentStore.token().substring(0, 32), EnrollmentStore.token(), false);
            new EnrollmentStore(this).save(next);
            config = next; PreviewState.config = next; upgrade = false;
            if (!getPreferences(0).edit().putInt("protocol", 4).putString("service", origin).commit()) throw new IllegalStateException();
            ++epoch; ticket = null; invitationArtifacts = null; claim = null; polling = false;
            if (previous != null) RelayApi.request(previous.url, "/v1/device/revoke", "POST", previous.token, null, (body, error) -> {});
            enroll();
        } catch (Exception failure) { change("无法准备新连接，请检查服务地址和手机存储后重试。"); }
    }
    @Override protected void onSaveInstanceState(Bundle saved) {
        super.onSaveInstanceState(saved);
        if (config != null) saved.putString("identity", config.url);
        saved.putString("ticket", ticket); saved.putLong("invitationExpiry", invitationExpiry);
        if (invitationArtifacts != null) {
            saved.putString("invitationIdentityFingerprint", invitationArtifacts.identityFingerprint());
            saved.putString("invitationCliSha256", invitationArtifacts.cliSha256());
            saved.putString("invitationGuideSha256", invitationArtifacts.guideSha256());
        }
    }
    private static String fileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
    private void storageFeedback(String text) {
        change(text);
        if(visible&&!isDestroyed())new AlertDialog.Builder(this).setTitle("文件访问").setMessage(text).setPositiveButton("知道了",null).show();
    }
    private void fileAccess() {
        if (fileStorageBusy) return;
        fileStorageBusy=true;
        FileScopes.ownerStorage(this,false,(usage,error)->{
            fileStorageBusy=false;if(isDestroyed()||!visible)return;
            if(error!=null){storageFeedback(error.equals("file_busy")?"文件操作还在结束，请稍后再打开文件访问。":"暂时无法读取文件占用，请稍后再试。");return;}
            new AlertDialog.Builder(this).setTitle("文件访问")
                    .setMessage("可清理的工作文件与传输记录："+fileSize(usage.workspaceBytes())+"（"+usage.workspaceFiles()+" 项）。包含以前 AI 连接留下的工作文件。\n\n"
                            +"工作文件及保留的传输材料合计 "+fileSize(usage.usedBytes())+"，当前容量上限 "+fileSize(PrivateFileStorage.BUDGET)+"。"
                            +(usage.reservedBytes()>0?"未完成上传另预留 "+fileSize(usage.reservedBytes())+"。":usage.reservedBytes()<0?"部分传输预留暂时无法统计，可先清理工作文件或反馈问题。":"")
                            +"手机共享文件夹中的原文件不计入这里，也不会被清理。")
                    .setPositiveButton("手机文件夹权限",(dialog,which)->sharedFolderAccess())
                    .setNeutralButton("清理文件…",(dialog,which)->new AlertDialog.Builder(this).setTitle("选择清理范围").setItems(new String[]{"清理工作文件","回收过期和旧连接暂存"},(d,n)->{if(n==0)confirmClearWorkFiles(usage);else confirmReclaimStaging();}).setNegativeButton("取消",null).show())
                    .setNegativeButton("返回",null).show();
        });
    }
    private void confirmClearWorkFiles(PrivateFileStorage.Usage usage) {
        new AlertDialog.Builder(this).setTitle("清理 Airtap 工作文件？")
                .setMessage("将删除当前和以前 AI 连接保存在 Airtap 内的工作文件，以及这些文件的未完成传输和记录，约 "+fileSize(usage.workspaceBytes())+"。请先保存还需要的文件。\n\n"
                        +"AI 配对、手机文件夹授权及文件夹里的原文件保持不变。涉及共享文件夹的传输材料会保留，不在本次清理范围内。")
                .setNegativeButton("取消",null).setPositiveButton("确认清理",(dialog,which)->{
                    if(fileStorageBusy)return;fileStorageBusy=true;change("正在清理 Airtap 工作文件…");
                    FileScopes.ownerStorage(this,true,(remaining,error)->{
                        fileStorageBusy=false;if(isDestroyed())return;
                        if(error!=null){storageFeedback(error.equals("file_busy")?"文件操作还在结束，本次未清理。请稍后重试。":"清理未全部完成，部分工作文件可能已经删除。配对和共享文件夹未改动，可再次尝试清理。");return;}
                        storageFeedback("工作文件已清理，AI 连接保持不变。Airtap 内还保留 "+fileSize(remaining.usedBytes())+" 的其他文件传输材料。"
                                +(remaining.usedBytes()>=PrivateFileStorage.BUDGET*9/10?"仍接近上限时，请在问题反馈中提供容量数值，请维护者协助检查保留的传输记录；无需上传文件内容。":""));
                    });
                }).show();
    }
    private void confirmReclaimStaging() {
        new AlertDialog.Builder(this).setTitle("回收内部暂存？")
                .setMessage("只回收过期上传和以前 AI 连接留下的内部文件副本。当前连接未过期的上传仍可继续。共享文件夹原文件、配对和文件夹权限不变；未知创建结果与已知文档身份会保留，避免重复创建或误删。")
                .setNegativeButton("取消",null).setPositiveButton("确认回收",(dialog,which)->{
                    if(fileStorageBusy)return;fileStorageBusy=true;
                    FileScopes.ownerReclaimStaging(this,(usage,error)->{
                        fileStorageBusy=false;if(isDestroyed())return;
                        storageFeedback(error==null?"内部暂存已回收，当前还占用 "+fileSize(usage.usedBytes())+"。文件夹中的原文件没有删除。":error.equals("file_busy")?"文件操作正在进行，请稍后重试。":"回收未全部完成，请稍后重试；共享文件夹原文件没有删除。");
                    });
                }).show();
    }
    private void sharedFolderAccess() {
        try {
            String owner = FileScopes.owner(this);
            if (owner == null) { change("先连接自己的 AI，再设置它可访问的文件夹。"); return; }
            String chosen = FileScopes.chosenName(this, owner);
            new AlertDialog.Builder(this).setTitle("文件访问")
                    .setMessage("你的 AI 可以在 Airtap 工作目录中收发文件。这个目录属于当前 AI 连接，其他 App 不能直接打开其中的文件。\n\n"
                            + (chosen == null ? "需要使用手机上的文件时，可选择一个文件夹。AI 可读取其中的文件，并在系统允许时新建文件；不会覆盖已有文件。" : "已允许访问：" + chosen + "。你可以随时取消；原文件不会删除。")
                            + "\n\n如果系统不让直接选择“下载”，可在里面新建“Airtap文件”子文件夹，再选中它。\n\n当前每个文件最多 8 MB。选文件夹是可选步骤，不影响其他手机操作。")
                    .setPositiveButton(chosen == null ? "选择文件夹" : "更换文件夹", (dialog, which) -> {
                        try {
                            int request = getPreferences(0).getInt("file_picker_request", 71) + 1;
                            if (request > 65000) request = 72;
                            if (!getPreferences(0).edit().putInt("file_picker_request", request).commit()) throw new IllegalStateException();
                            fileGrantAttempt.begin(request, owner); FileScopes.picker(this, true);
                            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), request);
                        } catch (Exception unavailable) { fileGrantAttempt.cancel(); FileScopes.picker(this, false); change("这台设备暂时无法打开系统文件夹选择器。Airtap 工作目录仍可使用。"); }
                    }).setNeutralButton(chosen == null ? "返回" : "取消文件夹授权", (dialog, which) -> {
                        if (chosen != null) { FileScopes.revoke(this); change("已取消文件夹授权，原文件未删除。"); }
                    }).setNegativeButton("知道了", null).show();
        } catch (Exception failure) { change("暂时无法读取文件授权，请稍后再试。"); }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (!fileGrantAttempt.matches(requestCode)) return;
        String grantOwner = fileGrantAttempt.consume(requestCode);
        try {
            if (resultCode == RESULT_OK && data != null) {
                FileScopes.grant(this, grantOwner, data.getData(), data.getFlags());
                change("已允许当前 AI 访问所选文件夹。回到你的 AI 对话安排文件任务即可。");
            }
        } catch (Exception failure) { change("文件夹授权未完成。请重新选择，确认当前 AI 仍已连接。"); }
        finally { FileScopes.picker(this, false); }
    }
    @Override public void onResume() {
        super.onResume(); visible = true;
        // Returning to our protected screen ends a cancelled or interrupted picker attempt.
        if (FileScopes.pickerActive(this)) { fileGrantAttempt.cancel(); FileScopes.picker(this, false); }
        setupPaused = true; pollBackoff.reset(); nextPoll = 0;
        main.post(refresh);
    }
    @Override public void onPause() {
        visible = false; setupPaused = busy || setupPaused; fenceSetup(); main.removeCallbacks(refresh); super.onPause();
    }
    @Override public void onDestroy() { ++epoch; main.removeCallbacksAndMessages(null); super.onDestroy(); }
}
