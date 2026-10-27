package io.airtap.preview;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Bounded HTTPS calls on the user's normal network. No redirect or automatic replay. */
final class RelayApi {
    interface Result { void complete(JSONObject body, String error); }
    interface StatusResult { void complete(JSONObject body, int status, String error); }
    interface TimedStatusResult { void complete(JSONObject body, int status, String error, InvitationFeedback.ServerTime serverTime); }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient CLIENT = TlsPolicy.newClientBuilder()
            .callTimeout(20, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();

    static String origin(String raw) {
        return RelayAddress.canonical(raw);
    }

    static Call request(String url, String path, String method, String token, JSONObject body, Result callback) {
        return requestStatus(url, path, method, token, body, (result, status, error) -> callback.complete(result, error));
    }

    static Call requestStatus(String url, String path, String method, String token, JSONObject body, StatusResult callback) {
        return requestTimedStatus(url, path, method, token, body, (json, status, error, time) -> callback.complete(json, status, error));
    }

    static Call requestTimedStatus(String url, String path, String method, String token, JSONObject body, TimedStatusResult callback) {
        Request.Builder request = new Request.Builder().url(origin(url) + path).header("Accept", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if ("POST".equals(method)) {
            request.post(body == null ? RequestBody.create(new byte[0], null)
                    : RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8")));
        } else request.get();
        Call call = CLIENT.newCall(request.build());
        long started = android.os.SystemClock.elapsedRealtime();
        call.enqueue(new Callback() {
            @Override public void onFailure(Call request, java.io.IOException failure) {
                // No exception messages: URLs and tokens must never enter diagnostics.
                String message = TlsPolicy.isSecurityFailure(failure) ? TlsPolicy.USER_MESSAGE
                        : "网络请求未确认（" + failure.getClass().getSimpleName() + "），未自动重试";
                MAIN.post(() -> callback.complete(null, 0, message, null));
            }
            @Override public void onResponse(Call request, Response response) {
                try (Response closed = response) {
                    if (response.code() != 200) {
                        int code = response.code();
                        String reason = "";
                        try { reason = readJson(response).optString("error", ""); }
                        catch (Exception ignored) { /* Only a fixed local fallback is displayed. */ }
                        String error = RelayErrors.message(code, reason);
                        MAIN.post(() -> callback.complete(null, code, error, null));
                        return;
                    }
                    JSONObject json = readJson(response);
                    long received = android.os.SystemClock.elapsedRealtime(); String date = response.header("Date");
                    MAIN.post(() -> callback.complete(json, 200, null, InvitationFeedback.serverTime(date,
                            received - started, android.os.SystemClock.elapsedRealtime() - received)));
                } catch (Exception failure) {
                    MAIN.post(() -> callback.complete(null, response.code(), "中继响应格式无效，请检查 App 与服务版本", null));
                }
            }
        });
        return call;
    }

    private static JSONObject readJson(Response response) throws Exception {
        if (response.body() == null) throw new java.io.IOException();
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        java.io.InputStream stream = response.body().byteStream();
        byte[] chunk = new byte[1024];
        int count;
        while ((count = stream.read(chunk, 0, Math.min(chunk.length, 8193 - output.size()))) != -1) {
            output.write(chunk, 0, count);
            if (output.size() > 8192) throw new java.io.IOException();
        }
        return new JSONObject(new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
    }

    static JSONObject json(Object... values) {
        JSONObject result = new JSONObject();
        try { for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]); }
        catch (Exception failure) { throw new IllegalArgumentException(failure); }
        return result;
    }
}
