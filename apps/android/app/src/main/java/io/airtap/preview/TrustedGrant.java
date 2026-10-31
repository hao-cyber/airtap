package io.airtap.preview;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Durable owner consent. Remote state never creates a local control grant. */
final class TrustedGrant {
    static final long DISPLAY_EXPIRY = 253402300799L;
    private static final long MAX_SEQUENCE = 9007199254740991L;
    private static final Object LOCK = new Object();
    private final EnrollmentStore store;
    private final EnrollmentStore enrollment;
    private final SharedPreferences state;
    TrustedGrant(Context context) {
        store = new EnrollmentStore(context, "trusted-ai");
        enrollment = new EnrollmentStore(context);
        state = context.getSharedPreferences("trust-state", 0);
    }
    private static String digest(String input) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder value = new StringBuilder(64);
        for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
    private String identity(EnrollmentStore.Config config) throws Exception {
        if (config == null || !config.publicService() || !config.enrolled()) throw new IllegalArgumentException();
        return digest(config.url + "\n" + config.token);
    }
    private static String randomId() { return EnrollmentStore.token().substring(0, 32); }
    private static void h32(String value) { if (value == null || !value.matches("[a-f0-9]{32}")) throw new IllegalArgumentException(); }
    private static void number(long value, boolean positive) { if (value < (positive ? 1 : 0) || value > MAX_SEQUENCE) throw new IllegalArgumentException(); }
    private static boolean schema2(JSONObject value) {
        if (value == null || !value.has("schema_version")) return false;
        if (ServiceProtocol.integer(value, "schema_version") != 2 || !"multi".equals(value.optString("mode"))) throw new IllegalArgumentException("unsupported_trust_schema");
        return true;
    }
    private static void validateGrant(JSONObject value, boolean pending) throws Exception {
        h32(value.getString("claim")); number(ServiceProtocol.integer(value, "sequence"), false);
        if (value.has("approval_id")) h32(value.getString("approval_id"));
        if (pending) {
            h32(value.getString("approval_id"));
            if (!"pending".equals(value.optString("phase")) && !"approved".equals(value.optString("phase"))) throw new IllegalArgumentException();
        } else if (value.has("phase") && !"approved".equals(value.optString("phase"))) throw new IllegalArgumentException();
    }
    private static JSONObject optional(JSONObject value, String key) throws Exception { return value.isNull(key) ? null : value.getJSONObject(key); }
    private static void validateMulti(JSONObject value) throws Exception {
        if (!value.has("pending") || !value.has("invitation")) throw new IllegalArgumentException("incomplete_trust_schema");
        h32(value.getString("carrier_id"));
        String nonce = value.getString("stop_nonce"); if (!nonce.isEmpty()) h32(nonce);
        number(ServiceProtocol.integer(value, "invitation_sequence"), false);
        JSONArray grants = value.getJSONArray("grants"); Set<String> claims = new LinkedHashSet<>();
        if (grants.length() > 4) throw new IllegalArgumentException("grant_limit_reached");
        for (int i = 0; i < grants.length(); i++) {
            JSONObject grant = grants.getJSONObject(i); validateGrant(grant, false);
            if (!claims.add(grant.getString("claim"))) throw new IllegalArgumentException();
            ServiceProtocol.bool(grant, "lease_required"); ServiceProtocol.bool(grant, "lease_ready");
        }
        JSONObject pending = optional(value, "pending"), invite = optional(value, "invitation");
        if (pending != null) {
            validateGrant(pending, true);
            if (claims.contains(pending.getString("claim"))) throw new IllegalArgumentException();
        }
        if (invite != null) {
            h32(invite.getString("invitation_id")); h32(invite.getString("ticket"));
            long sequence = ServiceProtocol.integer(invite, "invitation_sequence"); number(sequence, true);
            if (sequence > value.getLong("invitation_sequence")) throw new IllegalArgumentException();
            String phase = invite.getString("phase"), origin = invite.getString("origin");
            if (!phase.equals("creating") && !phase.equals("ready") && !phase.equals("canceled")) throw new IllegalArgumentException();
            if (!origin.equals("multi_ai_v1") && !origin.equals("legacy_v4")) throw new IllegalArgumentException();
        }
        if (grants.length() + ((pending != null || (invite != null && !"canceled".equals(invite.optString("phase")))) ? 1 : 0) > 4) throw new IllegalArgumentException("grant_limit_reached");
    }
    private JSONObject record(EnrollmentStore.Config config) throws Exception {
        if (config == null || !config.publicService() || !config.enrolled()) return null;
        String key = identity(config); JSONObject value = store.readRecord();
        if (value == null || !key.equals(value.optString("identity"))) {
            if (key.equals(state.getString("multi_identity", ""))) throw new IllegalStateException("multi_record_unavailable");
            return null;
        }
        if (schema2(value)) {
            validateMulti(value);
            if (!key.equals(state.getString("multi_identity", ""))) rememberMulti(key);
        }
        else {
            if (key.equals(state.getString("multi_identity", ""))) throw new IllegalStateException("multi_downgrade_refused");
            // Pending single records are validated by their phase-specific callers.
            h32(value.getString("claim")); number(ServiceProtocol.integer(value, "sequence"), false);
        }
        return value;
    }
    private boolean currentIntent(JSONObject value, String id) {
        return value != null && id != null && id.matches("[a-f0-9]{32}")
                && id.equals(value.optString("approval_id")) && id.equals(state.getString("approval_id", ""));
    }
    private boolean currentStop(JSONObject value) throws Exception { return value.getString("stop_nonce").equals(state.getString("stop_nonce", "")); }
    private JSONObject snapshot(JSONObject root, JSONObject grant) throws Exception {
        return grant.put("identity", root.getString("identity")).put("schema_version", 2).put("mode", "multi").put("carrier_id", root.getString("carrier_id"));
    }
    private static JSONObject find(JSONArray grants, String claim) throws Exception {
        for (int i = 0; i < grants.length(); i++) if (claim.equals(grants.getJSONObject(i).getString("claim"))) return grants.getJSONObject(i);
        return null;
    }
    private void rememberMulti(String key) {
        // The encrypted schema is authoritative. A failed secondary marker cannot undo it.
        try { state.edit().putString("multi_identity", key).commit(); }
        catch (RuntimeException unavailable) { /* A later schema-2 read retries after process restart. */ }
    }
    boolean multi(EnrollmentStore.Config config) throws Exception { synchronized (LOCK) { return schema2(record(config)); } }
    void enableMulti(EnrollmentStore.Config config) throws Exception {
        synchronized (LOCK) {
            JSONObject old = record(config);
            if (schema2(old)) {
                rememberMulti(identity(config));
                return;
            }
            JSONObject raw = store.readRecord();
            if (raw != null && !identity(config).equals(raw.optString("identity"))) throw new IllegalStateException("identity_mismatch");
            saveMulti(config, read(config), pending(config));
        }
    }
    /** Caller holds LOCK; publish only the final consent set in one encrypted replacement. */
    private void saveMulti(EnrollmentStore.Config config, JSONObject active, JSONObject pending) throws Exception {
        JSONArray grants = new JSONArray();
        if (active != null) {
            active.remove("migration");
            grants.put(active.put("lease_required", false).put("lease_ready", false));
        }
        JSONObject root = RelayApi.json("schema_version", 2, "mode", "multi", "identity", identity(config),
                "carrier_id", randomId(), "stop_nonce", state.getString("stop_nonce", ""), "grants", grants,
                "pending", pending == null ? JSONObject.NULL : pending, "invitation", JSONObject.NULL, "invitation_sequence", 0);
        if (pending != null) pending.put("lease_required", false).put("lease_ready", false).put("origin", "legacy_v4");
        store.saveRecord(root); // No token or replay sequence is regenerated.
        rememberMulti(identity(config));
    }
    private static JSONObject migrationOf(JSONObject single) throws Exception {
        if (single == null || schema2(single) || !single.has("migration")) return null;
        JSONObject intent = single.getJSONObject("migration");
        h32(intent.getString("id"));
        String phase = intent.getString("phase"), fence = intent.getString("fence");
        if ((!"prepared".equals(phase) && !"unknown".equals(phase)) || !fence.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid_migration");
        return fence.equals(individualFence(single)) ? intent : null;
    }
    String beginMigration(EnrollmentStore.Config config, String expectedFence) throws Exception {
        synchronized (LOCK) {
            JSONObject single = read(config);
            if (single == null || schema2(single) || expectedFence == null || !expectedFence.equals(individualFence(single))) return null;
            JSONObject intent = migrationOf(single);
            if (intent != null) return intent.getString("id");
            String id = randomId();
            single.put("migration", RelayApi.json("id", id, "phase", "prepared", "fence", expectedFence));
            store.saveRecord(single);
            return id;
        }
    }
    JSONObject migration(EnrollmentStore.Config config) throws Exception {
        synchronized (LOCK) { return migrationOf(read(config)); }
    }
    boolean dispatchMigration(EnrollmentStore.Config config, String id) throws Exception {
        synchronized (LOCK) {
            JSONObject single = read(config), intent = migrationOf(single);
            if (intent == null || id == null || !id.equals(intent.getString("id")) || !"prepared".equals(intent.getString("phase"))) return false;
            intent.put("phase", "unknown");
            store.saveRecord(single);
            return true;
        }
    }
    /** Caller may cancel only after an explicit rejection with no earlier unknown dispatch. */
    void cancelMigration(EnrollmentStore.Config config, String id) throws Exception {
        synchronized (LOCK) {
            JSONObject single = read(config), intent = migrationOf(single);
            if (intent == null || id == null || !id.equals(intent.getString("id"))) return;
            single.remove("migration");
            store.saveRecord(single);
        }
    }
    boolean completeMigration(EnrollmentStore.Config config, String id, Set<String> remoteClaims) throws Exception {
        synchronized (LOCK) {
            if (remoteClaims == null) throw new IllegalArgumentException();
            JSONObject single = read(config), intent = migrationOf(single);
            if (intent == null || id == null || !id.equals(intent.getString("id"))) return false;
            // Remote mode is established even if the original grant was removed during migration.
            // A remote-only grant or pending approval can never create local owner consent.
            saveMulti(config, remoteClaims.contains(single.getString("claim")) ? single : null, null);
            return true;
        }
    }
    /** Local empty-state identity only; the caller must independently verify remote multi mode. */
    String emptyModeFence(EnrollmentStore.Config config) throws Exception {
        synchronized (LOCK) {
            if (config == null || !config.publicService() || !config.enrolled()) return null;
            EnrollmentStore.Config current = enrollment.read();
            if (current == null || !current.publicService() || !current.enrolled()
                    || !config.url.equals(current.url) || !config.token.equals(current.token)) return null;
            String key = identity(config);
            JSONObject raw = store.readRecord();
            if (raw != null && !key.equals(raw.optString("identity"))) return null;
            if (schema2(raw) || read(config) != null || pending(config) != null) return null;
            String nonce = state.getString("stop_nonce", "");
            if (!nonce.isEmpty()) h32(nonce);
            return digest("airtap-empty-mode-fence-v1\n" + key + "\n" + nonce);
        }
    }
    boolean completeEmptyMulti(EnrollmentStore.Config config, String expectedFence) throws Exception {
        synchronized (LOCK) {
            if (expectedFence == null || !expectedFence.equals(emptyModeFence(config))) return false;
            saveMulti(config, null, null);
            return true;
        }
    }
    List<JSONObject> grants(EnrollmentStore.Config config) throws Exception {
        synchronized (LOCK) {
            List<JSONObject> result = new ArrayList<>();
            if (state.getBoolean("stopped", true)) return result;
            JSONObject root = record(config); if (root == null) return result;
            if (!schema2(root)) { JSONObject single = read(config); if (single != null) result.add(single); return result; }
            if (!currentStop(root)) return result;
            JSONArray values = root.getJSONArray("grants");
            for (int i = 0; i < values.length(); i++) result.add(snapshot(root, values.getJSONObject(i)));
            return result;
        }
    }
    JSONObject grant(EnrollmentStore.Config config, String claim) throws Exception {
        synchronized (LOCK) { if (claim == null) return null; for (JSONObject value : grants(config)) if (claim.equals(value.getString("claim"))) return value; return null; }
    }
    Set<String> validClaims(EnrollmentStore.Config config) throws Exception {
        synchronized (LOCK) { Set<String> result = new LinkedHashSet<>(); for (JSONObject value : grants(config)) result.add(value.getString("claim")); return result; }
    }
    JSONObject read(EnrollmentStore.Config config) throws Exception {
        synchronized (LOCK) {
            if (state.getBoolean("stopped", true)) return null;
            JSONObject value = record(config); if (value == null) return null;
            if (schema2(value)) { if (!currentStop(value) || value.getJSONArray("grants").length() == 0) return null; return snapshot(value, value.getJSONArray("grants").getJSONObject(0)); }
            if (!value.has("phase")) return value;
            return "approved".equals(value.optString("phase")) && currentIntent(value, value.optString("approval_id")) ? value : null;
        }
    }
    String sessionFence(EnrollmentStore.Config config) throws Exception { synchronized (LOCK) { return fenceOf(read(config)); } }
    /** Pure snapshot identity, never authorization. Multi snapshots refer to the whole carrier. */
    static String fenceOf(JSONObject value) throws Exception {
        if (value == null) return null;
        if (schema2(value)) return digest("airtap-carrier-fence-v2\n" + value.getString("identity") + "\n" + value.getString("carrier_id"));
        return individualFence(value);
    }
    private static String individualFence(JSONObject value) throws Exception {
        String approval = value.has("approval_id") ? "approval:" + value.getString("approval_id") : "legacy";
        return digest("airtap-session-fence-v1\n" + value.getString("identity") + "\n" + value.getString("claim") + "\n" + approval);
    }
    String grantFence(EnrollmentStore.Config config, String claim) throws Exception { synchronized (LOCK) { JSONObject value = grant(config, claim); return value == null ? null : individualFence(value); } }
    boolean isCurrent(EnrollmentStore.Config config, String expectedFence) throws Exception { synchronized (LOCK) { return expectedFence != null && expectedFence.equals(sessionFence(config)); } }
    boolean stopIfCurrent(EnrollmentStore.Config config, String expectedFence) throws Exception { synchronized (LOCK) { if (!isCurrent(config, expectedFence)) return false; stop(); return true; } }
    JSONObject pending(EnrollmentStore.Config config) throws Exception {
        synchronized (LOCK) {
            JSONObject value = record(config);
            boolean multi = schema2(value);
            if (multi) { if (!currentStop(value)) return null; value = optional(value, "pending"); }
            if (value == null || !currentIntent(value, value.optString("approval_id"))) return null;
            String phase = value.optString("phase");
            return "pending".equals(phase) || ("approved".equals(phase) && (multi || state.getBoolean("stopped", true))) ? value : null;
        }
    }
    private JSONObject editableMulti(EnrollmentStore.Config config) throws Exception {
        JSONObject value = record(config); if (!schema2(value)) throw new IllegalStateException("multi_api_required");
        if (!currentStop(value)) {
            value.put("grants", new JSONArray()).put("pending", JSONObject.NULL).put("invitation", JSONObject.NULL)
                    .put("carrier_id", randomId()).put("stop_nonce", state.getString("stop_nonce", ""));
        }
        return value;
    }
    String beginApproval(EnrollmentStore.Config config, String claim, String name) throws Exception {
        synchronized (LOCK) {
            h32(claim);
            if (multi(config)) {
                JSONObject root = editableMulti(config);
                if (grant(config, claim) != null) return null;
                JSONObject previous = optional(root, "pending");
                if (previous != null && currentIntent(previous, previous.optString("approval_id"))) {
                    if (claim.equals(previous.getString("claim"))) return previous.getString("approval_id");
                    throw new IllegalStateException("approval_pending");
                }
                if (root.getJSONArray("grants").length() >= 4) throw new IllegalStateException("grant_limit_reached");
                JSONObject invite = optional(root, "invitation");
                if (invite != null && "canceled".equals(invite.optString("phase"))) throw new IllegalStateException("invitation_canceled");
                boolean legacy = invite != null && "legacy_v4".equals(invite.optString("origin")) && root.getJSONArray("grants").length() == 0;
                String id = randomId();
                JSONObject intent = RelayApi.json("identity", identity(config), "claim", claim, "name", name == null ? "我的 AI" : name,
                        "sequence", 0, "phase", "pending", "approval_id", id, "lease_required", !legacy, "lease_ready", false);
                if (!state.edit().putString("approval_id", id).commit()) throw new IllegalStateException("approval_intent_not_persisted");
                root.put("pending", intent); store.saveRecord(root); return id;
            }
            JSONObject active = read(config);
            if (active != null) { if (!claim.equals(active.getString("claim"))) throw new IllegalStateException("existing_grant"); return null; }
            JSONObject previous = pending(config);
            if (previous != null && claim.equals(previous.getString("claim"))) return previous.getString("approval_id");
            String id = randomId();
            JSONObject intent = RelayApi.json("identity", identity(config), "claim", claim, "name", name == null ? "我的 AI" : name, "sequence", 0, "phase", "pending", "approval_id", id);
            if (!state.edit().putBoolean("stopped", true).putString("approval_id", id).commit()) { stop(); throw new IllegalStateException("approval_intent_not_persisted"); }
            try { store.saveRecord(intent); } catch (Exception failure) { stop(); throw failure; }
            return id;
        }
    }
    boolean completeApproval(EnrollmentStore.Config config, String id) throws Exception {
        synchronized (LOCK) {
            JSONObject root = record(config);
            if (schema2(root)) {
                if (!currentStop(root)) return false;
                JSONObject value = optional(root, "pending");
                if (!currentIntent(value, id)) {
                    for (JSONObject active : grants(config)) if (id != null && id.equals(active.optString("approval_id"))) return true;
                    return false;
                }
                value.put("phase", "approved"); store.saveRecord(root);
                if (state.getBoolean("stopped", true) && !state.edit().putBoolean("stopped", false).commit()) {
                    state.edit().putBoolean("stopped", true).commit(); throw new IllegalStateException("grant_activation_not_persisted");
                }
                JSONArray values = root.getJSONArray("grants");
                if (values.length() >= 4) throw new IllegalStateException("grant_limit_reached");
                if (values.length() == 0) root.put("carrier_id", randomId());
                values.put(value); root.put("pending", JSONObject.NULL).put("invitation", JSONObject.NULL); store.saveRecord(root); return true;
            }
            JSONObject value = root;
            if (!currentIntent(value, id)) return false;
            String phase = value.optString("phase"); if (!"pending".equals(phase) && !"approved".equals(phase)) return false;
            if ("approved".equals(phase) && !state.getBoolean("stopped", true)) return true;
            value.put("phase", "approved"); store.saveRecord(value);
            if (!state.edit().putBoolean("stopped", false).commit()) { state.edit().putBoolean("stopped", true).commit(); throw new IllegalStateException("grant_activation_not_persisted"); }
            return true;
        }
    }
    void cancelApproval(EnrollmentStore.Config config, String id) throws Exception {
        synchronized (LOCK) {
            JSONObject value = pending(config); if (!currentIntent(value, id)) return;
            if (!multi(config)) { stop(); return; }
            JSONObject root = record(config); root.put("pending", JSONObject.NULL);
            JSONObject invite = optional(root, "invitation"); if (invite != null) invite.put("phase", "canceled");
            // Invalidate a delayed approval before touching the shared container; A remains active.
            boolean saved = state.edit().remove("approval_id").commit();
            try { store.saveRecord(root); }
            catch (Exception failure) { if (!saved) throw new IllegalStateException("approval_cancel_not_persisted", failure); throw failure; }
        }
    }
    void consume(EnrollmentStore.Config config, long sequence) throws Exception {
        synchronized (LOCK) { if (multi(config)) throw new IllegalStateException("claim_required"); JSONObject value = read(config);
            if (value == null || sequence <= value.getLong("sequence") || sequence > MAX_SEQUENCE) throw new ScopedUi.Refused("sequence_already_consumed");
            value.put("sequence", sequence); store.saveRecord(value);
        }
    }
    void consume(EnrollmentStore.Config config, String claim, long sequence) throws Exception {
        synchronized (LOCK) {
            if (!multi(config)) { if (grant(config, claim) == null) throw new ScopedUi.Refused("grant_unavailable"); consume(config, sequence); return; }
            if (grant(config, claim) == null) throw new ScopedUi.Refused("grant_unavailable");
            JSONObject root = record(config), value = find(root.getJSONArray("grants"), claim);
            if (sequence <= value.getLong("sequence") || sequence > MAX_SEQUENCE) throw new ScopedUi.Refused("sequence_already_consumed");
            value.put("sequence", sequence); store.saveRecord(root);
        }
    }
    void requireLease(EnrollmentStore.Config config, String claim) throws Exception {
        synchronized (LOCK) {
            if (grant(config, claim) == null) throw new ScopedUi.Refused("grant_unavailable");
            JSONObject root = editableMulti(config), value = find(root.getJSONArray("grants"), claim);
            value.put("lease_required", true).put("lease_ready", true); store.saveRecord(root);
        }
    }
    void revoke(EnrollmentStore.Config config, String claim) throws Exception {
        synchronized (LOCK) {
            JSONObject pending = pending(config);
            if (pending != null && claim != null && claim.equals(pending.getString("claim"))) cancelApproval(config, pending.getString("approval_id"));
            Set<String> keep = validClaims(config); keep.remove(claim); retain(config, keep);
        }
    }
    void retain(EnrollmentStore.Config config, Set<String> remoteClaims) throws Exception {
        synchronized (LOCK) {
            if (remoteClaims == null) throw new IllegalArgumentException();
            if (!multi(config)) { JSONObject value = read(config); if (value != null && !remoteClaims.contains(value.getString("claim"))) stop(); return; }
            JSONObject root = record(config); JSONArray old = root.getJSONArray("grants"), keep = new JSONArray();
            for (int i = 0; i < old.length(); i++) if (remoteClaims.contains(old.getJSONObject(i).getString("claim"))) keep.put(old.getJSONObject(i));
            if (keep.length() != old.length()) { root.put("grants", keep); if (keep.length() == 0) root.put("carrier_id", randomId()); store.saveRecord(root); }
        }
    }
    JSONObject invitation(EnrollmentStore.Config config) throws Exception {
        synchronized (LOCK) { JSONObject root = record(config); return schema2(root) && currentStop(root) ? optional(root, "invitation") : null; }
    }
    JSONObject beginInvitation(EnrollmentStore.Config config, long remoteWatermark) throws Exception {
        synchronized (LOCK) {
            number(remoteWatermark, false); JSONObject root = editableMulti(config), current = optional(root, "invitation");
            if (current != null) return current; // Includes canceled: finish cancellation before any fresh invitation.
            if (optional(root, "pending") != null) throw new IllegalStateException("approval_pending");
            JSONArray grants = root.getJSONArray("grants"); if (grants.length() >= 4) throw new IllegalStateException("grant_limit_reached");
            for (int i = 0; i < grants.length(); i++) if (!ServiceProtocol.bool(grants.getJSONObject(i), "lease_ready")) throw new IllegalStateException("client_upgrade_required");
            long sequence = Math.max(root.getLong("invitation_sequence"), remoteWatermark) + 1; number(sequence, true);
            JSONObject invite = RelayApi.json("invitation_id", randomId(), "invitation_sequence", sequence, "ticket", randomId(), "phase", "creating", "origin", "multi_ai_v1", "pairing_expires_at", 0);
            root.put("invitation_sequence", sequence).put("invitation", invite); store.saveRecord(root); return invite;
        }
    }
    private static boolean sameInvitation(JSONObject invite, String id, long sequence) throws Exception { return invite != null && id != null && id.equals(invite.getString("invitation_id")) && sequence == invite.getLong("invitation_sequence"); }
    private static void invitationMetadata(long expiry, String cliSha, String agentSha) { number(expiry, true); if (cliSha == null || agentSha == null || !cliSha.matches("[a-f0-9]{64}") || !agentSha.matches("[a-f0-9]{64}")) throw new IllegalArgumentException(); }
    boolean completeInvitation(EnrollmentStore.Config config, String id, long sequence, long expiry, String cliSha, String agentSha) throws Exception {
        synchronized (LOCK) {
            invitationMetadata(expiry, cliSha, agentSha); JSONObject root = record(config);
            if (!schema2(root) || !currentStop(root)) return false;
            JSONObject invite = optional(root, "invitation"); if (!sameInvitation(invite, id, sequence) || "canceled".equals(invite.optString("phase"))) return false;
            if ("ready".equals(invite.optString("phase"))) return expiry == invite.getLong("pairing_expires_at") && cliSha.equals(invite.optString("cli_sha256")) && agentSha.equals(invite.optString("agent_sha256"));
            invite.put("phase", "ready").put("pairing_expires_at", expiry).put("cli_sha256", cliSha).put("agent_sha256", agentSha); store.saveRecord(root); return true;
        }
    }
    boolean cancelInvitation(EnrollmentStore.Config config, String id, long sequence) throws Exception {
        synchronized (LOCK) {
            JSONObject root = record(config); if (!schema2(root) || !currentStop(root)) return false;
            JSONObject invite = optional(root, "invitation"); if (!sameInvitation(invite, id, sequence)) return false;
            if (optional(root, "pending") != null) state.edit().remove("approval_id").commit();
            invite.put("phase", "canceled"); root.put("pending", JSONObject.NULL); store.saveRecord(root); return true;
        }
    }
    boolean finishInvitationCancellation(EnrollmentStore.Config config, String id, long sequence) throws Exception {
        synchronized (LOCK) {
            JSONObject root = record(config); if (!schema2(root) || !currentStop(root)) return false;
            JSONObject invite = optional(root, "invitation"); if (!sameInvitation(invite, id, sequence) || !"canceled".equals(invite.optString("phase"))) return false;
            root.put("invitation", JSONObject.NULL); store.saveRecord(root); return true;
        }
    }
    boolean adoptLegacyInvitation(EnrollmentStore.Config config, String id, long sequence, String ticket, long expiry, String cliSha, String agentSha) throws Exception {
        synchronized (LOCK) {
            h32(id); h32(ticket); number(sequence, true); invitationMetadata(expiry, cliSha, agentSha);
            JSONObject root = editableMulti(config); JSONObject existing = optional(root, "invitation");
            if (existing != null) return sameInvitation(existing, id, sequence) && ticket.equals(existing.optString("ticket")) && "legacy_v4".equals(existing.optString("origin"));
            if (root.getJSONArray("grants").length() != 0 || root.getLong("invitation_sequence") != 0) return false;
            JSONObject invite = RelayApi.json("invitation_id", id, "invitation_sequence", sequence, "ticket", ticket, "phase", "ready", "origin", "legacy_v4", "pairing_expires_at", expiry, "cli_sha256", cliSha, "agent_sha256", agentSha);
            root.put("invitation_sequence", sequence).put("invitation", invite); store.saveRecord(root); return true;
        }
    }
    void stop() {
        synchronized (LOCK) {
            String nonce = randomId();
            boolean saved = state.edit().putBoolean("stopped", true).putString("stop_nonce", nonce).remove("approval_id").commit();
            try {
                JSONObject root = store.readRecord();
                if (schema2(root)) {
                    root.put("grants", new JSONArray()).put("pending", JSONObject.NULL).put("invitation", JSONObject.NULL).put("carrier_id", randomId()).put("stop_nonce", nonce);
                    store.saveRecord(root); // Keep schema and high-watermark even after every grant is stopped.
                } else store.forget();
            } catch (Exception failure) { if (!saved) throw new IllegalStateException("trust_stop_not_persisted"); }
        }
    }
    static void show(JSONObject grant) throws Exception {
        PreviewState.persistent = true; PreviewState.claim = grant.getString("claim"); PreviewState.candidate = grant.getString("name");
        PreviewState.approvedExpiry = DISPLAY_EXPIRY; PreviewState.allowControl = true; PreviewState.allowTap = false;
        PreviewState.allowedPackages = java.util.Collections.emptyList();
    }
}
