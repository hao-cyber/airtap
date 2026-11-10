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
    private static boolean delegationAllowed(Context context, EnrollmentStore.Config config, JSONObject pending) throws Exception {
        if (!pending.has("delegated_by")) return true;
        String actor=pending.getString("delegated_by");
        return new TrustedGrant(context).delegationCurrent(config,pending)
                &&!OwnerRevocations.allPending(context,config)&&!OwnerRevocations.claimsPending(context,config).contains(actor);
    }
    private static void cancelDelegation(Context context, EnrollmentStore.Config config, JSONObject pending) throws Exception {
        // The original approve POST may already have committed remotely. Persist a targeted
        // revoke before forgetting local consent; a later retry cannot authorize the candidate.
        OwnerRevocations.queue(context,config,pending.getString("claim"));
        new TrustedGrant(context).cancelApproval(config,pending.getString("approval_id"));
        OwnerRevocations.flush(context,config,null);
    }
    /** Returns true only when a new request was started; in-flight/backoff callers must wait. */
    static boolean resume(Context context, EnrollmentStore.Config config, Result callback) throws Exception {
        Context app = context.getApplicationContext();
        if (!sameIdentity(config, new EnrollmentStore(app).read())) return false;
        JSONObject pending = new TrustedGrant(app).pending(config);
        if (pending == null) return false;
        if (!delegationAllowed(app,config,pending)) {
            cancelDelegation(app,config,pending);
            if(callback!=null)callback.complete(Outcome.STOPPED);
            return false;
        }
        String id = pending.getString("approval_id"), claim = pending.getString("claim");
        boolean multi = new TrustedGrant(app).multi(config);
        final Attempt run;
        synchronized (LOCK) {
            if (attempt == null || !id.equals(attempt.id)) attempt = new Attempt(id);
            run = attempt;
            if (run.inFlight || SystemClock.elapsedRealtime() < run.retryAt) return false;
            run.inFlight = true;
        }
        try {
            JSONObject approval=RelayApi.json("claim_id", claim, "allow_tap", false, "allow_control", true,
                    "allowed_packages", new JSONArray(), "persistent", true, "all_apps", true);
            // The relay must check the delegator in the same transaction that creates
            // the new grant; local consent may lag a controller's remote self-revocation.
            if(pending.has("delegated_by")) approval.put("delegated_by",pending.getString("delegated_by"));
            RelayApi.requestStatus(config.url, multi ? "/v1/device/grants/approve" : "/v1/device/approve", "POST", config.token,
                    approval, (body, status, error) -> {
                Outcome outcome = Outcome.RETRY;
                try {
                    TrustedGrant trust = new TrustedGrant(app);
                    EnrollmentStore.Config current = new EnrollmentStore(app).read();
                    JSONObject stillPending = sameIdentity(config, current) ? trust.pending(current) : null;
                    JSONObject active = sameIdentity(config, current) ? trust.grant(current, claim) : null;
                    if (stillPending == null || !id.equals(stillPending.optString("approval_id"))) {
                        // A completed duplicate may report success, but must never rewrite its sequence.
                        outcome = active != null && id.equals(active.optString("approval_id")) ? Outcome.APPROVED : Outcome.SUPERSEDED;
                    } else if (!delegationAllowed(app,config,stillPending)) {
                        cancelDelegation(app,config,stillPending);outcome=Outcome.STOPPED;
                    } else if (error != null) {
                        if (RelayFailure.approvalRejected(status, body, multi)) {
                            trust.cancelApproval(config, id); outcome = Outcome.STOPPED;
                        }
                    } else {
                        approved(body);
                        if (multi && !claim.equals(body.getString("claim_id"))) throw new IllegalArgumentException();
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
