package io.airtap.preview;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

/** Optional, bounded public update check. Never sends pairing or device data. */
final class UpdateNotice {
    static final String ORIGIN = "https://airtap.memtrix.dev";
    private final Activity activity;
    private boolean checking;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = TlsPolicy.newClientBuilder()
            .followRedirects(false).callTimeout(12, TimeUnit.SECONDS).build();
    UpdateNotice(Activity activity) { this.activity = activity; }
    void open(String path) {
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ORIGIN + path))); }
        catch (Exception failure) { tell("请用手机浏览器打开 airtap.memtrix.dev。"); }
    }
    void check(boolean manual) {
        if (checking) return;
        var prefs = activity.getSharedPreferences("updates", 0);
        long now = System.currentTimeMillis();
        if (!manual && now - prefs.getLong("checked", 0) < 86400000L) return;
        checking = true;
        client.newCall(new Request.Builder().url(ORIGIN + "/updates/android.json")
                .header("User-Agent", "Airtap-Update/1").build()).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                finish(null, manual, TlsPolicy.isSecurityFailure(error) ? TlsPolicy.USER_MESSAGE : null);
            }
            @Override public void onResponse(Call call, Response response) {
                try (response) {
                    if (!response.isSuccessful() || response.body() == null) throw new IOException();
                    byte[] bytes = response.body().byteStream().readNBytes(8193);
                    if (bytes.length > 8192) throw new IOException();
                    JSONObject data = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                    if (!"airtap-android-update-v1".equals(data.getString("schema"))
                            || !activity.getPackageName().equals(data.getString("application_id"))
                            || data.getInt("version_code") < 1 || data.getInt("min_sdk") < 34
                            || !"/download/android".equals(data.getString("download_path"))) throw new IOException();
                    finish(data, manual);
                } catch (Exception failure) { finish(null, manual); }
            }
        });
    }
    private void finish(JSONObject data, boolean manual) { finish(data, manual, null); }
    private void finish(JSONObject data, boolean manual, String safeError) {
        main.post(() -> {
            checking = false;
            if (activity.isDestroyed() || activity.isFinishing()) return;
            if (data == null) { if (manual) tell(safeError == null ? "暂时无法检查更新，稍后再试。当前连接不受影响。" : safeError); return; }
            var prefs = activity.getSharedPreferences("updates", 0);
            prefs.edit().putLong("checked", System.currentTimeMillis()).apply();
            int code = data.optInt("version_code");
            if (data.optInt("min_sdk") > android.os.Build.VERSION.SDK_INT) {
                if (manual) tell("当前安装包适合这台手机。新版需要更新的 Android 系统。"); return;
            }
            if (code <= BuildConfig.VERSION_CODE) { if (manual) tell("已是当前推荐版本。"); return; }
            if (!manual && prefs.getInt("dismissed", 0) == code) return;
            // The fixed first-party download route is used; remote text cannot supply an install URL.
            new AlertDialog.Builder(activity).setTitle("Airtap 有更新")
                    .setMessage("更新会保留你的数据和连接设置。下载后按系统提示安装即可。")
                    .setPositiveButton("下载更新", (dialog, which) -> open("/download/android"))
                    .setNegativeButton("稍后", (dialog, which) -> prefs.edit().putInt("dismissed", code).apply()).show();
        });
    }
    private void tell(String message) {
        if (!activity.isDestroyed() && !activity.isFinishing()) new AlertDialog.Builder(activity)
                .setTitle("Airtap").setMessage(message).setPositiveButton("知道了", null).show();
    }
}
