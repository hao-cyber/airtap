package io.airtap.preview;

import android.content.Context;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;

/** Resolves the same owner-approved migration without guessing the outcome of an earlier POST. */
final class MigrationRecovery {
    enum Outcome { COMMITTED, REJECTED, RETRY, STOPPED, SUPERSEDED }
    interface Result { void complete(Outcome outcome); }
    private static final Object LOCK = new Object();
    private static Attempt attempt;
    private static final class Attempt {
        final String id, fence, claim;
        boolean inFlight;
        int failures;
        long retryAt;
        Attempt(JSONObject pending, JSONObject grant) throws Exception {
            id=pending.getString("id");fence=pending.getString("fence");claim=grant.getString("claim");
        }
    }
    private MigrationRecovery() {}
    private static boolean same(EnrollmentStore.Config a,EnrollmentStore.Config b) {
        return a!=null&&b!=null&&a.enrolled()&&b.enrolled()&&a.publicService()&&b.publicService()
                &&a.url.equals(b.url)&&a.token.equals(b.token);
    }
    private static boolean current(Context app,EnrollmentStore.Config config,Attempt run) throws Exception {
        if(!same(config,new EnrollmentStore(app).read())||OwnerRevocations.allPending(app,config)||OwnerRevocations.claimsPending(app,config).contains(run.claim))return false;
        JSONObject pending=new TrustedGrant(app).migration(config);
        return pending!=null&&run.id.equals(pending.getString("id"))&&run.fence.equals(pending.getString("fence"));
    }
    private static void finish(Attempt run,Outcome outcome,Result callback) {
        synchronized(LOCK){
            run.inFlight=false;
            if(outcome==Outcome.RETRY)run.retryAt=SystemClock.elapsedRealtime()+Math.min(60000L,5000L<<Math.min(run.failures++,4));
            else if(attempt==run)attempt=null;
        }
        if(callback!=null)callback.complete(outcome);
    }
    private static boolean single(JSONObject body,String claim) throws Exception {
        ServiceProtocol.version(body);
        if(body.has("mode"))throw new IllegalArgumentException();
        String state=body.getString("state");
        if(!"approved".equals(state)){
            if(java.util.Set.of("idle","waiting","pending","revoked").contains(state))return false;
            throw new IllegalArgumentException();
        }
        if(!body.has("session_expires_at")||!body.isNull("session_expires_at")||!ServiceProtocol.bool(body,"persistent")
                ||!ServiceProtocol.bool(body,"all_apps")||!ServiceProtocol.bool(body,"allow_control")
                ||ServiceProtocol.bool(body,"allow_tap")||body.getJSONArray("allowed_packages").length()!=0)throw new IllegalArgumentException();
        return claim.equals(MultiAiProtocol.hex(body.getJSONObject("candidate"),"claim_id"));
    }
    /** Returns a final result only for a verified multi state, otherwise the caller handles single. */
    private static Outcome multi(Context app,EnrollmentStore.Config config,Attempt run,JSONObject body) throws Exception {
        if(!body.has("mode"))return null;
        java.util.Set<String> claims=MultiAiProtocol.claims(body);
        TrustedGrant trust=new TrustedGrant(app);
        return trust.completeMigration(config,run.id,claims)?Outcome.COMMITTED:Outcome.SUPERSEDED;
    }
    private static void reconcile(Context app,EnrollmentStore.Config config,Attempt run,boolean rejectedFirst,Result callback) {
        try {
            RelayApi.requestStatus(config.url,"/v1/device/pairing","GET",config.token,null,(body,status,error)->{
                try {
                    if(!current(app,config,run)){finish(run,Outcome.SUPERSEDED,callback);return;}
                    TrustedGrant trust=new TrustedGrant(app);
                    if(error!=null||status!=200){
                        Outcome outcome=Outcome.RETRY;
                        if(status==401||status==403||status==410)outcome=trust.stopIfCurrent(config,run.fence)?Outcome.STOPPED:Outcome.SUPERSEDED;
                        finish(run,outcome,callback);return;
                    }
                    Outcome result=multi(app,config,run,body);
                    if(result!=null){finish(run,result,callback);return;}
                    if(!single(body,run.claim)){
                        finish(run,trust.stopIfCurrent(config,run.fence)?Outcome.STOPPED:Outcome.SUPERSEDED,callback);return;
                    }
                    if(rejectedFirst){trust.cancelMigration(config,run.id);finish(run,Outcome.REJECTED,callback);return;}
                    send(app,config,run,callback);
                }catch(Exception unknown){finish(run,Outcome.RETRY,callback);}
            });
        }catch(Exception unavailable){finish(run,Outcome.RETRY,callback);}
    }
    private static void send(Context app,EnrollmentStore.Config config,Attempt run,Result callback) throws Exception {
        if(!current(app,config,run)){finish(run,Outcome.SUPERSEDED,callback);return;}
        // Persist ambiguity before network dispatch, including a crash before its callback.
        boolean first=new TrustedGrant(app).dispatchMigration(config,run.id);
        if(!current(app,config,run)){finish(run,Outcome.SUPERSEDED,callback);return;}
        try {
            RelayApi.requestStatus(config.url,"/v1/device/multi/enable","POST",config.token,
                    RelayApi.json("capabilities",new JSONArray().put("multi_ai_v1").put("task_lease_v1"),"expected_claim_id",run.claim),(body,status,error)->{
                try {
                    if(!current(app,config,run)){finish(run,Outcome.SUPERSEDED,callback);return;}
                    if(error==null&&status==200){
                        Outcome result=multi(app,config,run,body);
                        if(result!=null){finish(run,result,callback);return;}
                    }
                    // Only a definitive first rejection can return to single, after a fresh read.
                    // A retry's rejection cannot disprove an earlier, still unknown POST.
                    if(first&&(status==404||status==409)){reconcile(app,config,run,true,callback);return;}
                    finish(run,Outcome.RETRY,callback);
                }catch(Exception unknown){finish(run,Outcome.RETRY,callback);}
            });
        }catch(Exception unavailable){finish(run,Outcome.RETRY,callback);}
    }
    static boolean resume(Context context,EnrollmentStore.Config config,Result callback) throws Exception {
        Context app=context.getApplicationContext();
        if(!same(config,new EnrollmentStore(app).read()))return false;
        TrustedGrant trust=new TrustedGrant(app);JSONObject pending=trust.migration(config),grant=trust.read(config);
        if(pending==null||grant==null)return false;
        final Attempt run;
        synchronized(LOCK){
            if(attempt==null||!pending.getString("id").equals(attempt.id))attempt=new Attempt(pending,grant);
            run=attempt;if(run.inFlight||SystemClock.elapsedRealtime()<run.retryAt)return false;run.inFlight=true;
        }
        reconcile(app,config,run,false,callback);return true;
    }
}
