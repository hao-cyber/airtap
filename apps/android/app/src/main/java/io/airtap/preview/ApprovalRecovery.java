package io.airtap.preview;

import android.content.Context;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;

/** Repeats only the same, durably owner-approved, idempotent pairing request. Never device actions. */
final class ApprovalRecovery {
    enum Outcome { APPROVED, RETRY, STOPPED, SUPERSEDED }
    interface Result { void complete(Outcome outcome); }
    private static final Object LOCK = new Object();
    private static Attempt attempt;
    private static final class Attempt {
        final String id;
        boolean inFlight;
        int failures;
        long retryAt;
        Attempt(String id) { this.id = id; }
    }
    private ApprovalRecovery() {}

    private static boolean sameIdentity(EnrollmentStore.Config a, EnrollmentStore.Config b) {
        return a != null && b != null && a.enrolled() && b.enrolled()
                && a.url.equals(b.url) && a.token.equals(b.token);
    }
    private static void approved(JSONObject body) throws Exception {
        ServiceProtocol.version(body);
        if (!ServiceProtocol.bool(body, "ok") || !ServiceProtocol.bool(body, "persistent")
                || !ServiceProtocol.bool(body, "all_apps") || !ServiceProtocol.bool(body, "allow_control")
                || ServiceProtocol.bool(body, "allow_tap") || !body.has("session_expires_at") || !body.isNull("session_expires_at")
                || body.getJSONArray("allowed_packages").length() != 0) throw new IllegalArgumentException();
    }
    /** Returns true only when a new request was started; in-flight/backoff callers must wait. */
    static boolean resume(Context context, EnrollmentStore.Config config, Result callback) throws Exception {
        Context app = context.getApplicationContext();
        if (!sameIdentity(config, new EnrollmentStore(app).read())) return false;
        JSONObject pending = new TrustedGrant(app).pending(config);
        if (pending == null) return false;
        String id = pending.getString("approval_id"), claim = pending.getString("claim");
        final Attempt run;
        synchronized (LOCK) {
            if (attempt == null || !id.equals(attempt.id)) attempt = new Attempt(id);
            run = attempt;
            if (run.inFlight || SystemClock.elapsedRealtime() < run.retryAt) return false;
            run.inFlight = true;
        }
        try {
            RelayApi.requestStatus(config.url, "/v1/device/approve", "POST", config.token,
                    RelayApi.json("claim_id", claim, "allow_tap", false, "allow_control", true,
                            "allowed_packages", new JSONArray(), "persistent", true, "all_apps", true), (body, status, error) -> {
                Outcome outcome = Outcome.RETRY;
                try {
                    TrustedGrant trust = new TrustedGrant(app);
                    EnrollmentStore.Config current = new EnrollmentStore(app).read();
                    JSONObject stillPending = sameIdentity(config, current) ? trust.pending(current) : null;
                    JSONObject active = sameIdentity(config, current) ? trust.read(current) : null;
                    if (stillPending == null || !id.equals(stillPending.optString("approval_id"))) {
                        // A completed duplicate may report success, but must never rewrite its sequence.
                        outcome = active != null && id.equals(active.optString("approval_id")) ? Outcome.APPROVED : Outcome.SUPERSEDED;
                    } else if (error != null) {
                        if (status == 400 || status == 401 || status == 403 || status == 409 || status == 410) {
                            trust.cancelApproval(config, id); outcome = Outcome.STOPPED;
                        }
                    } else {
                        approved(body);
                        outcome = trust.completeApproval(config, id) ? Outcome.APPROVED : Outcome.SUPERSEDED;
                    }
                } catch (Exception failure) { /* Unknown storage/network result preserves consent, never grants access. */ }
                synchronized (LOCK) {
                    run.inFlight = false;
                    if (outcome == Outcome.RETRY) {
                        run.retryAt = SystemClock.elapsedRealtime() + Math.min(60000L, 5000L << Math.min(run.failures++, 4));
                    } else if (attempt == run) attempt = null;
                }
                if (callback != null) callback.complete(outcome);
            });
            return true;
        } catch (RuntimeException failure) {
            synchronized (LOCK) {
                run.inFlight = false;
                run.retryAt = SystemClock.elapsedRealtime() + 5000;
            }
            throw failure;
        }
    }
}
