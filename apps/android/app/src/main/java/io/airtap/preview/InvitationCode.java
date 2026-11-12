package io.airtap.preview;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Short-lived owner invitation presentation; it never approves or replaces a grant. */
final class InvitationCode {
    private final Activity owner;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String identityKey = "", code, error;
    private int attempts;
    private okhttp3.Call call;

    InvitationCode(Activity owner, Runnable changed) { this.owner = owner; this.changed = changed; }

    void close() { identityKey = ""; if (call != null) call.cancel(); main.removeCallbacksAndMessages(null); }

    void render(LinearLayout box, EnrollmentStore.Config config, String ticket, long expires) {
        String key = config.url + ":" + InvitationArtifacts.fingerprint(config.token) + ":" + ticket + ":" + expires;
        if (!identityKey.equals(key)) {
            if (call != null) call.cancel();
            identityKey = key; code = null; error = null; attempts = 0;
            request(config, ticket, expires, key);
        }
        if (code == null) {
            box.addView(AppUi.text(owner, error == null ? "正在生成邀请码…" : error, 16));
            if (error != null) box.addView(button("重试生成邀请码", () -> {
                identityKey = ""; changed.run();
            }, false));
            return;
        }
        box.addView(AppUi.text(owner, "邀请码", 16));
        TextView digits = AppUi.text(owner, code.substring(0, 4) + " " + code.substring(4), 40);
        digits.setTextIsSelectable(true);
        digits.setContentDescription("八位邀请码 " + String.join(" ", code.split("")));
        box.addView(digits);
        String deadline = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(expires * 1000));
        box.addView(AppUi.text(owner, "告诉你的 AI 这 8 位数字，有效至 " + deadline + "。\nAI 会返回另一组 6 位核对码，回来核对并确认连接。", 16));
        AppUi.space(box, 12);
        box.addView(button("复制给我的 AI", () -> {
            String text = prompt(config, expires); if (text == null) return;
            ClipData clip = ClipData.newPlainText("Airtap 邀请码", text);
            PersistableBundle extras = new PersistableBundle(); extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
            owner.getSystemService(ClipboardManager.class).setPrimaryClip(clip);
            android.widget.Toast.makeText(owner, "已复制一句接入邀请", android.widget.Toast.LENGTH_SHORT).show();
        }, true));
        box.addView(button("分享给我的 AI", () -> {
            String text = prompt(config, expires); if (text == null) return;
            owner.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "发送给自己的 AI"));
        }, false));
    }

    private Button button(String label, Runnable action, boolean primary) {
        Button b = new Button(owner); b.setText(label); b.setFilterTouchesWhenObscured(true);
        AppUi.button(b, primary ? AppUi.PRIMARY : AppUi.SECONDARY);
        b.setOnClickListener(v -> action.run()); return b;
    }

    private String prompt(EnrollmentStore.Config config, long expires) {
        if (code == null || expires <= System.currentTimeMillis() / 1000) {
            changed.run(); return null;
        }
        return "用 Airtap 连接我的手机，邀请码 " + code.substring(0, 4) + " " + code.substring(4)
                + "。接入说明：" + config.origin() + "/connect 。先用实际工具检查能否接入并保留连接，"
                + "申请后告诉我六位核对码。等我在手机确认后自行查状态，不用等我再回复。连接后继续我已交代的任务；还没有任务时告诉我已经可以使用。";
    }

    private void request(EnrollmentStore.Config config, String ticket, long expires, String key) {
        if (!identityKey.equals(key) || owner.isDestroyed() || expires <= System.currentTimeMillis() / 1000) return;
        attempts++;
        try {
            call = RelayApi.requestStatus(config.url, "/v1/device/invitation-code", "POST", config.token,
                    RelayApi.json("ticket", ticket), (body, status, failure) -> {
                if (!identityKey.equals(key) || owner.isDestroyed()) return;
                call = null;
                try {
                    if (failure != null) throw new IllegalStateException();
                    ServiceProtocol.version(body);
                    Object value = body.opt("code");
                    if (!(value instanceof String) || !((String) value).matches("[0-9]{8}")
                            || MultiAiProtocol.integer(body, "pairing_expires_at", false) != expires
                            || expires <= System.currentTimeMillis() / 1000) throw new IllegalArgumentException();
                    code = (String) value; error = null; changed.run();
                } catch (Exception unavailable) { retry(config, ticket, expires, key); }
            });
        } catch (Exception unavailable) { retry(config, ticket, expires, key); }
    }

    private void retry(EnrollmentStore.Config config, String ticket, long expires, String key) {
        if (attempts < 3 && expires > System.currentTimeMillis() / 1000 + 5) {
            main.postDelayed(() -> request(config, ticket, expires, key), attempts * 1500L);
        } else {
            error = "暂时没有取得邀请码。原邀请和已有 AI 连接保留，可重试。"; changed.run();
        }
    }
}
