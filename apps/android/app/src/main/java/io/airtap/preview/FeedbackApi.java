package io.airtap.preview;

import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

/** Independent bounded report reads/deletes; no collection, upload, redirects, or automatic replay. */
final class FeedbackApi {
    interface Result { void complete(JSONObject body, int status, String code); }
    private static final int MAX_BYTES = 256 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient CLIENT = TlsPolicy.newClientBuilder()
            .callTimeout(20, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();

    static Call list(FeedbackRequestGate.Ticket owner, String cursor, Result result) {
        return request(owner, null, cursor, false, result);
    }
    static Call detail(FeedbackRequestGate.Ticket owner, String id, Result result) {
        return request(owner, id, null, false, result);
    }
    static Call delete(FeedbackRequestGate.Ticket owner, String id, Result result) {
        return request(owner, id, null, true, result);
    }
    private static Call request(FeedbackRequestGate.Ticket owner, String id, String cursor, boolean delete, Result result) {
        if (id != null && !FeedbackReport.validId(id)) throw new IllegalArgumentException();
        HttpUrl.Builder address = HttpUrl.get(RelayAddress.canonical(owner.url) + "/v1/device/reports").newBuilder();
        if (id != null) address.addPathSegment(id);
        else {
            if (delete) throw new IllegalArgumentException();
            address.addQueryParameter("limit", "20");
            if (cursor != null) {
                if (!FeedbackReport.validCursor(cursor)) throw new IllegalArgumentException();
                address.addQueryParameter("cursor", cursor);
            }
        }
        Request.Builder builder = new Request.Builder().url(address.build()).header("Accept", "application/json")
                .header("Authorization", "Bearer " + owner.token);
        if (delete) builder.delete(); else builder.get();
        Call call = CLIENT.newCall(builder.build());
        call.enqueue(new Callback() {
            @Override public void onFailure(Call failed, IOException failure) {
                MAIN.post(() -> result.complete(null, 0, "network_unconfirmed"));
            }
            @Override public void onResponse(Call completed, Response response) {
                int status = response.code();
                try (Response closed = response) {
                    JSONObject body = read(response);
                    // Error bodies are untrusted. Expose only the few codes the UI distinguishes.
                    String error = null;
                    if (status != 200) {
                        String reported = body.optString("error", "");
                        error = (status == 410 && "report_deleted".equals(reported)) ? "report_deleted"
                                : (status == 404 && "report_not_found".equals(reported)) ? "report_not_found" : "unavailable";
                    }
                    String code = error;
                    MAIN.post(() -> result.complete(status == 200 ? body : null, status, code));
                } catch (Exception invalid) {
                    MAIN.post(() -> result.complete(null, status, "response_unconfirmed"));
                }
            }
        });
        return call;
    }

    private static JSONObject read(Response response) throws Exception {
        if (response.body() == null || response.body().contentLength() > MAX_BYTES) throw new IOException();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InputStream stream = response.body().byteStream();
        byte[] chunk = new byte[4096];
        int count;
        while ((count = stream.read(chunk, 0, Math.min(chunk.length, MAX_BYTES + 1 - output.size()))) != -1) {
            output.write(chunk, 0, count);
            if (output.size() > MAX_BYTES) throw new IOException();
        }
        return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
    }
}
