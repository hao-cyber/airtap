package io.airtap.preview;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Durable owner consent and trust. An unfinished approval never authorizes device commands. */
final class TrustedGrant {
    static final long DISPLAY_EXPIRY = 253402300799L;
    private static final Object LOCK = new Object();
    private final EnrollmentStore store;
    private final SharedPreferences state;
    TrustedGrant(Context context) {
        store = new EnrollmentStore(context, "trusted-ai");
        state = context.getSharedPreferences("trust-state", 0);
    }
    private String identity(EnrollmentStore.Config config) throws Exception {
        if (config == null || !config.publicService() || !config.enrolled()) throw new IllegalArgumentException();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest((config.url + "\n" + config.token).getBytes(StandardCharsets.UTF_8));
        StringBuilder value = new StringBuilder();
        for (byte b : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
    private JSONObject record(EnrollmentStore.Config config) throws Exception {
        if (config == null || !config.publicService() || !config.enrolled()) return null;
        JSONObject value = store.readRecord();
        if (value == null || !identity(config).equals(value.optString("identity"))) return null;
        long sequence = ServiceProtocol.integer(value, "sequence");
        if (!value.getString("claim").matches("[a-f0-9]{32}") || sequence < 0 || sequence > 9007199254740991L) throw new IllegalArgumentException();
        return value;
    }
    private boolean currentIntent(JSONObject value, String id) {
        return value != null && id != null && id.matches("[a-f0-9]{32}")
                && id.equals(value.optString("approval_id")) && id.equals(state.getString("approval_id", ""));
    }
    JSONObject read(EnrollmentStore.Config config) throws Exception {
        synchronized (LOCK) {
            if (state.getBoolean("stopped", true)) return null;
            JSONObject value = record(config);
            if (value == null) return null;
            // Existing v4 records had no phase or approval ID. Preserve their sequence unchanged.
            if (!value.has("phase")) return value;
            return "approved".equals(value.optString("phase")) && currentIntent(value, value.optString("approval_id")) ? value : null;
        }
    }
    JSONObject pending(EnrollmentStore.Config config) throws Exception {
        synchronized (LOCK) {
            JSONObject value = record(config);
            if (value == null || !currentIntent(value, value.optString("approval_id"))) return null;
            String phase = value.optString("phase");
            // A crash between the encrypted grant write and activation marker is recoverable too.
            return "pending".equals(phase) || ("approved".equals(phase) && state.getBoolean("stopped", true)) ? value : null;
        }
    }
    String beginApproval(EnrollmentStore.Config config, String claim, String name) throws Exception {
        synchronized (LOCK) {
            if (claim == null || !claim.matches("[a-f0-9]{32}")) throw new IllegalArgumentException();
            JSONObject active = read(config);
            if (active != null) {
                if (!claim.equals(active.getString("claim"))) throw new IllegalStateException("existing_grant");
                return null; // Already approved: never reset an existing replay sequence.
            }
            JSONObject previous = pending(config);
            if (previous != null && claim.equals(previous.getString("claim"))) return previous.getString("approval_id");
            String id = EnrollmentStore.token().substring(0, 32);
            JSONObject intent = RelayApi.json("identity", identity(config), "claim", claim,
                    "name", name == null ? "我的 AI" : name, "sequence", 0, "phase", "pending", "approval_id", id);
            // Persist a fresh fence before writing consent or sending any approval request.
            if (!state.edit().putBoolean("stopped", true).putString("approval_id", id).commit()) {
                stop(); throw new IllegalStateException("approval_intent_not_persisted");
            }
            try { store.saveRecord(intent); }
            catch (Exception failure) { stop(); throw failure; }
            return id;
        }
    }
    boolean completeApproval(EnrollmentStore.Config config, String id) throws Exception {
        synchronized (LOCK) {
            JSONObject value = record(config);
            if (!currentIntent(value, id)) return false;
            String phase = value.optString("phase");
            if (!"pending".equals(phase) && !"approved".equals(phase)) return false;
            if ("approved".equals(phase) && !state.getBoolean("stopped", true)) return true;
            value.put("phase", "approved");
            // Keep the same encrypted record and sequence during every idempotent completion.
            store.saveRecord(value);
            if (!state.edit().putBoolean("stopped", false).commit()) {
                // SharedPreferences may update memory despite a failed disk commit; stay closed.
                state.edit().putBoolean("stopped", true).commit();
                throw new IllegalStateException("grant_activation_not_persisted");
            }
            return true;
        }
    }
    void cancelApproval(EnrollmentStore.Config config, String id) throws Exception {
        synchronized (LOCK) {
            JSONObject value = pending(config);
            if (currentIntent(value, id)) stop();
        }
    }
    void consume(EnrollmentStore.Config config, long sequence) throws Exception {
        synchronized (LOCK) {
            JSONObject value = read(config);
            if (value == null || sequence <= value.getLong("sequence") || sequence > 9007199254740991L) throw new ScopedUi.Refused("sequence_already_consumed");
            value.put("sequence", sequence);
            store.saveRecord(value);
        }
    }
    void stop() {
        synchronized (LOCK) {
            // Clearing the durable intent fence also invalidates every late network callback.
            boolean saved = state.edit().putBoolean("stopped", true).remove("approval_id").commit();
            try { store.forget(); }
            catch (Exception ignored) { if (!saved) throw new IllegalStateException("trust_stop_not_persisted"); }
        }
    }
    static void show(JSONObject grant) throws Exception {
        PreviewState.persistent = true;
        PreviewState.claim = grant.getString("claim");
        PreviewState.candidate = grant.getString("name");
        PreviewState.approvedExpiry = DISPLAY_EXPIRY;
        PreviewState.allowControl = true;
        PreviewState.allowTap = false;
        PreviewState.allowedPackages = java.util.Collections.emptyList();
    }
}
