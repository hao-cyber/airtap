package io.airtap.preview;

import android.content.Context;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real production state transitions, synthetic persistence and deliberately delayed HTTP. */
public final class ApprovalRecoveryTest {
    private static final String CLAIM_A="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", CLAIM_B="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String SUCCESS="已记住你的 AI，以后可以直接使用。";
    private static Context context;
    private static EnrollmentStore.Config config;
    private static int passed;
    interface Checked { void run() throws Exception; }
    private static void check(boolean condition) { if(!condition) throw new AssertionError(); }
    private static void test(String name, Checked body) throws Exception { reset();body.run();passed++;System.out.println("PASS "+name); }
    private static void resetRuntime() throws Exception {
        var field=ApprovalRecovery.class.getDeclaredField("attempt");field.setAccessible(true);field.set(null,null);
        RelayApi.requests.clear();context=new Context();
    }
    private static void reset() throws Exception {
        FakeDisk.reset();resetRuntime();SystemClock.now=100000;
        config=new EnrollmentStore.Config("https://relay.invalid/d/11111111111111111111111111111111","synthetic-secret-A");FakeDisk.config=config;
    }
    private static void reboot() throws Exception { FakeDisk.reboot();resetRuntime(); }
    private static TrustedGrant trust(){return new TrustedGrant(context);}
    private static String begin() throws Exception {return trust().beginApproval(config,CLAIM_A,"Synthetic AI");}
    private static RelayApi.Request request(int index){return RelayApi.requests.get(index);}
    private static JSONObject accepted(){return RelayApi.json("protocol_version",4,"ok",true,"persistent",true,"all_apps",true,"allow_control",true,"allow_tap",false,"session_expires_at",JSONObject.NULL,"allowed_packages",new JSONArray());}
    private static void accept(int index){request(index).respond(accepted(),200,null);}
    private static void expectCrash(Checked body) throws Exception {try{body.run();throw new AssertionError("missing injected crash");}catch(FakeDisk.Crash expected){}}
    private static void expectFailure(Checked body) throws Exception {try{body.run();throw new AssertionError("missing refusal");}catch(IllegalStateException|IllegalArgumentException|ScopedUi.Refused expected){}}
    private static void closed() throws Exception {check(trust().read(config)==null);}
    private static void explicitNull(JSONObject value,String key) throws Exception {check(value.has(key)&&value.get(key)==JSONObject.NULL);}

    public static void main(String[] args) throws Exception {
        test("JSONObject host fixture matches Android removal versus explicit JSON null",()->{
            JSONObject value=new JSONObject().put("empty",JSONObject.NULL).put("old","value");
            explicitNull(value,"empty");value.put("old",null);check(!value.has("old")&&value.isNull("old"));
            value.put("absent",null);check(!value.has("absent"));JSONObject copied=value.copy();
            explicitNull(copied,"empty");check(!copied.has("old")&&!copied.has("absent"));
            value.put("empty",null);check(!value.has("empty"));explicitNull(copied,"empty");
        });
        test("consent durable before POST, pending never grants controls",()->{
            String id=begin();check(id.equals(trust().pending(config).getString("approval_id")));closed();check(RelayApi.requests.isEmpty());
            check(ApprovalRecovery.resume(context,config,null));closed();check(CLAIM_A.equals(request(0).body.getString("claim_id")));
            check(request(0).body.getJSONArray("allowed_packages").length()==0);check(Boolean.FALSE.equals(request(0).body.opt("allow_tap")));
            accept(0);check(trust().pending(config)==null);check(CLAIM_A.equals(trust().read(config).getString("claim")));
        });
        test("no owner consent: server approval cannot start a recovery",()->{
            check(!ApprovalRecovery.resume(context,config,null));check(RelayApi.requests.isEmpty());closed();
            check(!trust().completeApproval(config,"cccccccccccccccccccccccccccccccc"));
        });
        test("process dies after relay acceptance: same claim resumed without confirmation",()->{
            String id=begin();ApprovalRecovery.resume(context,config,null);
            // Relay accepted, but its response is lost with the old process.
            reboot();closed();check(id.equals(trust().pending(config).getString("approval_id")));
            check(ApprovalRecovery.resume(context,config,null));check(CLAIM_A.equals(request(0).body.getString("claim_id")));accept(0);
            check(id.equals(trust().read(config).getString("approval_id")));
        });
        test("Activity disappears: app-context completion still commits explicit consent",()->{
            begin();final boolean[] visible={true},uiTouched={false};
            ApprovalRecovery.resume(context,config,result->{if(visible[0])uiTouched[0]=true;});visible[0]=false;
            accept(0);check(!uiTouched[0]);check(trust().read(config)!=null);
        });
        test("process crash after pending encrypted write keeps closed recoverable consent",()->{
            FakeDisk.crashAfterSaveAt=1;expectCrash(()->begin());reboot();closed();check(trust().pending(config)!=null);
            ApprovalRecovery.resume(context,config,null);accept(0);check(trust().read(config)!=null);
        });
        test("process crash before encrypted intent write never invents consent",()->{
            FakeDisk.crashBeforeSaveAt=1;expectCrash(()->begin());reboot();closed();check(trust().pending(config)==null);
            check(!ApprovalRecovery.resume(context,config,null));
        });
        test("process crash after marker before consent write remains closed",()->{
            FakeDisk.preferences.crashAfterAt=1;expectCrash(()->begin());reboot();closed();check(trust().pending(config)==null);
            check(!ApprovalRecovery.resume(context,config,null));
        });
        test("process crash after encrypted approved phase before activation is recoverable",()->{
            String id=begin();ApprovalRecovery.resume(context,config,null);FakeDisk.crashAfterSaveAt=2;
            expectCrash(()->accept(0));reboot();closed();check(id.equals(trust().pending(config).getString("approval_id")));
            ApprovalRecovery.resume(context,config,null);accept(0);check(trust().read(config)!=null);
        });
        test("process crash after activation keeps approved grant and sequence",()->{
            begin();ApprovalRecovery.resume(context,config,null);FakeDisk.preferences.crashAfterAt=2;
            expectCrash(()->accept(0));reboot();check(trust().read(config)!=null);check(trust().pending(config)==null);
            trust().consume(config,77);check(!ApprovalRecovery.resume(context,config,null));check(trust().read(config).getLong("sequence")==77);
        });
        test("failed intent marker write sends no POST and does not grant",()->{
            FakeDisk.preferences.failAt=1;expectFailure(()->begin());closed();check(trust().pending(config)==null);
            check(!ApprovalRecovery.resume(context,config,null));check(RelayApi.requests.isEmpty());
        });
        test("failed encrypted intent write sends no POST and deletes incomplete consent",()->{
            FakeDisk.failSaveAt=1;expectFailure(()->begin());closed();check(trust().pending(config)==null);
            check(!ApprovalRecovery.resume(context,config,null));
        });
        test("failed activation commit fails closed then recovers same intent",()->{
            String id=begin();ApprovalRecovery.resume(context,config,null);FakeDisk.preferences.failAt=2;
            final ApprovalRecovery.Outcome[] outcome={null}; // The completion itself catches storage failure.
            accept(0);closed();check(id.equals(trust().pending(config).getString("approval_id")));
            reboot();closed();ApprovalRecovery.resume(context,config,result->outcome[0]=result);accept(0);
            check(outcome[0]==ApprovalRecovery.Outcome.APPROVED);check(trust().read(config)!=null);
        });
        test("failed encrypted completion stays pending after relay success",()->{
            String id=begin();ApprovalRecovery.resume(context,config,null);FakeDisk.failSaveAt=2;accept(0);
            closed();check(id.equals(trust().pending(config).getString("approval_id")));
            SystemClock.now+=5000;ApprovalRecovery.resume(context,config,null);accept(1);check(trust().read(config)!=null);
        });
        test("stop fences a late successful approval callback",()->{
            begin();final ApprovalRecovery.Outcome[] result={null};ApprovalRecovery.resume(context,config,x->result[0]=x);
            trust().stop();accept(0);closed();check(trust().pending(config)==null);check(result[0]==ApprovalRecovery.Outcome.SUPERSEDED);
            reboot();closed();check(!ApprovalRecovery.resume(context,config,null));
        });
        test("stop marker fences leftover encrypted intent when deletion fails",()->{
            begin();ApprovalRecovery.resume(context,config,null);FakeDisk.failForget=true;trust().stop();accept(0);
            closed();check(trust().pending(config)==null);check(FakeDisk.record!=null);
            reboot();closed();check(trust().pending(config)==null);
        });
        test("stop deletion fences intent when preference commit fails",()->{
            begin();ApprovalRecovery.resume(context,config,null);FakeDisk.preferences.failAt=2;trust().stop();accept(0);
            reboot();closed();check(trust().pending(config)==null);
        });
        test("new claim fences old callback while new approval is pending",()->{
            begin();ApprovalRecovery.resume(context,config,null);trust().stop();String newId=trust().beginApproval(config,CLAIM_B,"second AI");
            ApprovalRecovery.resume(context,config,null);accept(0);closed();check(newId.equals(trust().pending(config).getString("approval_id")));
            accept(1);check(CLAIM_B.equals(trust().read(config).getString("claim")));
        });
        test("late old success cannot overwrite new active claim or its sequence",()->{
            begin();ApprovalRecovery.resume(context,config,null);trust().stop();trust().beginApproval(config,CLAIM_B,"second AI");
            ApprovalRecovery.resume(context,config,null);accept(1);trust().consume(config,123);accept(0);
            check(CLAIM_B.equals(trust().read(config).getString("claim")));check(trust().read(config).getLong("sequence")==123);
        });
        test("same claim new owner intent also fences old callbacks",()->{
            String oldId=begin();ApprovalRecovery.resume(context,config,null);trust().stop();String id=begin();check(!oldId.equals(id));
            ApprovalRecovery.resume(context,config,null);accept(0);closed();check(id.equals(trust().pending(config).getString("approval_id")));accept(1);
            check(id.equals(trust().read(config).getString("approval_id")));
        });
        test("changed enrollment token prevents old callback activation",()->{
            begin();ApprovalRecovery.resume(context,config,null);FakeDisk.config=new EnrollmentStore.Config(config.url,"replacement-token");accept(0);
            closed();check(new TrustedGrant(context).pending(FakeDisk.config)==null);check(!ApprovalRecovery.resume(context,config,null));
        });
        test("changed enrollment address prevents old callback activation",()->{
            begin();ApprovalRecovery.resume(context,config,null);FakeDisk.config=new EnrollmentStore.Config("https://other.invalid/d/22222222222222222222222222222222",config.token);accept(0);
            closed();check(trust().pending(FakeDisk.config)==null);check(!ApprovalRecovery.resume(context,config,null));
        });
        test("direct completion cannot activate mismatched device identity",()->{
            String id=begin();EnrollmentStore.Config other=new EnrollmentStore.Config(config.url,"different");
            check(!trust().completeApproval(other,id));closed();check(trust().pending(other)==null);
        });
        test("duplicate callback and approval preserve consumed sequence",()->{
            String id=begin();ApprovalRecovery.resume(context,config,null);accept(0);trust().consume(config,97);accept(0);
            check(trust().read(config).getLong("sequence")==97);check(trust().completeApproval(config,id));
            check(trust().beginApproval(config,CLAIM_A,"same")==null);check(trust().read(config).getLong("sequence")==97);
            expectFailure(()->trust().consume(config,97));
        });
        test("legacy v4 grant migration is unnecessary and preserves sequence",()->{
            String id=begin();trust().completeApproval(config,id);FakeDisk.record.remove("phase");FakeDisk.record.remove("approval_id");FakeDisk.record.put("sequence",845L);
            FakeDisk.preferences.edit().remove("approval_id").commit();reboot();
            check(trust().read(config).getLong("sequence")==845);check(trust().beginApproval(config,CLAIM_A,"same")==null);
            check(!ApprovalRecovery.resume(context,config,null));trust().consume(config,846);check(trust().read(config).getLong("sequence")==846);
        });
        test("existing active grant refuses replacement without owner stop",()->{
            String id=begin();trust().completeApproval(config,id);trust().consume(config,50);
            expectFailure(()->trust().beginApproval(config,CLAIM_B,"other"));check(trust().read(config).getLong("sequence")==50);
        });
        test("pending duplicate owner confirmation reuses same intent",()->{
            String id=begin();check(id.equals(begin()));check(FakeDisk.saves==1);
        });
        for(int status:new int[]{400,401,403,409,410}) {
            final int s=status;
            test("terminal HTTP "+s+" cancels consent and stops retries",()->{
                begin();final ApprovalRecovery.Outcome[] result={null};ApprovalRecovery.resume(context,config,x->result[0]=x);
                request(0).respond(null,s,"denied");closed();check(trust().pending(config)==null);check(result[0]==ApprovalRecovery.Outcome.STOPPED);
                reboot();check(!ApprovalRecovery.resume(context,config,null));
            });
        }
        test("many refresh callers produce only one in-flight request",()->{
            begin();check(ApprovalRecovery.resume(context,config,null));for(int i=0;i<100;i++)check(!ApprovalRecovery.resume(context,config,null));
            check(RelayApi.requests.size()==1);accept(0);check(trust().read(config)!=null);
        });
        test("unknown outcome retains consent with bounded exponential backoff",()->{
            String id=begin();long[] delay={5000,10000,20000,40000,60000,60000};
            for(int i=0;i<delay.length;i++){
                check(ApprovalRecovery.resume(context,config,null));request(i).respond(null,i%2==0?0:503,"unknown");closed();
                check(id.equals(trust().pending(config).getString("approval_id")));check(!ApprovalRecovery.resume(context,config,null));
                SystemClock.now+=delay[i]-1;check(!ApprovalRecovery.resume(context,config,null));SystemClock.now++;
            }
            ApprovalRecovery.resume(context,config,null);accept(delay.length);check(trust().read(config)!=null);
        });
        test("HTTP success with malformed scope cannot grant and remains retryable",()->{
            begin();ApprovalRecovery.resume(context,config,null);request(0).respond(accepted().put("all_apps","true"),200,null);closed();check(trust().pending(config)!=null);
            SystemClock.now+=5000;ApprovalRecovery.resume(context,config,null);request(1).respond(accepted().put("allow_tap",true),200,null);closed();
            SystemClock.now+=10000;ApprovalRecovery.resume(context,config,null);request(2).respond(accepted().put("allowed_packages",new JSONArray().put("test.package")),200,null);closed();
        });
        test("approval requires present JSON-null expiry and rejects Java-null deleted expiry",()->{
            String id=begin();JSONObject missing=accepted().put("session_expires_at",null);check(!missing.has("session_expires_at"));
            ApprovalRecovery.resume(context,config,null);request(0).respond(missing,200,null);
            closed();check(id.equals(trust().pending(config).getString("approval_id")));SystemClock.now+=5000;
            check(ApprovalRecovery.resume(context,config,null));JSONObject valid=accepted();explicitNull(valid,"session_expires_at");
            request(1).respond(valid,200,null);check(trust().read(config)!=null);
        });
        test("unknown network response survives process restart without new owner consent",()->{
            String id=begin();ApprovalRecovery.resume(context,config,null);request(0).respond(null,0,"timeout");reboot();
            check(id.equals(trust().pending(config).getString("approval_id")));ApprovalRecovery.resume(context,config,null);accept(0);check(trust().read(config)!=null);
        });
        test("invalid claim and sequence fail closed",()->{
            expectFailure(()->trust().beginApproval(config,"invalid","AI"));closed();String id=begin();trust().completeApproval(config,id);
            expectFailure(()->trust().consume(config,0));expectFailure(()->trust().consume(config,9007199254740992L));
        });
        test("session fence reads do not write consent and survive process restart",()->{
            String id=begin();trust().completeApproval(config,id);String fence=trust().sessionFence(config);
            check(fence!=null&&fence.matches("[a-f0-9]{64}"));
            var original=new java.util.LinkedHashMap<>(FakeDisk.record.fields);int saves=FakeDisk.saves,commits=FakeDisk.preferences.calls;
            check(fence.equals(trust().sessionFence(config)));check(trust().isCurrent(config,fence));
            check(FakeDisk.saves==saves&&FakeDisk.preferences.calls==commits);check(original.equals(FakeDisk.record.fields));
            reboot();check(fence.equals(trust().sessionFence(config)));check(trust().isCurrent(config,fence));
            check(id.equals(trust().read(config).getString("approval_id")));
        });
        test("snapshot fence remains tied to the observed consent when current authorization changes",()->{
            check(TrustedGrant.fenceOf(null)==null);
            String id=begin();trust().completeApproval(config,id);JSONObject observed=trust().read(config);
            String fence=TrustedGrant.fenceOf(observed);check(fence.equals(trust().sessionFence(config)));
            trust().stop();String next=begin();trust().completeApproval(config,next);
            String current=trust().sessionFence(config);check(!fence.equals(current));
            check(fence.equals(TrustedGrant.fenceOf(observed)));check(!trust().isCurrent(config,fence));
            check(!trust().stopIfCurrent(config,fence));check(trust().isCurrent(config,current));
        });
        test("consuming replay sequences preserves the same session fence",()->{
            String id=begin();trust().completeApproval(config,id);String fence=trust().sessionFence(config);
            trust().consume(config,71);check(fence.equals(trust().sessionFence(config)));reboot();
            trust().consume(config,72);check(trust().isCurrent(config,fence));check(trust().read(config).getLong("sequence")==72);
        });
        test("session fence includes claim independently of approval ID",()->{
            String id=begin();trust().completeApproval(config,id);String fence=trust().sessionFence(config);
            FakeDisk.record.put("claim",CLAIM_B);check(!fence.equals(trust().sessionFence(config)));
            check(!trust().stopIfCurrent(config,fence));check(CLAIM_B.equals(trust().read(config).getString("claim")));
        });
        test("old STOP cannot delete a new claim or reset its replay sequence",()->{
            String id=begin();trust().completeApproval(config,id);String fence=trust().sessionFence(config);trust().stop();
            String next=trust().beginApproval(config,CLAIM_B,"second AI");trust().completeApproval(config,next);trust().consume(config,123);
            check(!fence.equals(trust().sessionFence(config)));check(!trust().isCurrent(config,fence));check(!trust().stopIfCurrent(config,fence));
            reboot();check(CLAIM_B.equals(trust().read(config).getString("claim")));check(trust().read(config).getLong("sequence")==123);
        });
        test("same claim with new approval ID rejects the old STOP",()->{
            String id=begin();trust().completeApproval(config,id);String fence=trust().sessionFence(config);trust().stop();
            String next=begin();check(!id.equals(next));trust().completeApproval(config,next);String current=trust().sessionFence(config);
            check(!fence.equals(current));check(!trust().stopIfCurrent(config,fence));reboot();
            check(trust().isCurrent(config,current));check(next.equals(trust().read(config).getString("approval_id")));
        });
        test("session fence includes enrollment identity independently of claim and approval ID",()->{
            String id=begin();trust().completeApproval(config,id);String fence=trust().sessionFence(config);trust().stop();
            EnrollmentStore.Config other=new EnrollmentStore.Config(config.url,"replacement-token");FakeDisk.config=other;
            String next=trust().beginApproval(other,CLAIM_A,"same AI");trust().completeApproval(other,next);
            // Hold claim and approval ID constant to exercise only the persisted identity boundary.
            FakeDisk.record.put("approval_id",id);FakeDisk.preferences.edit().putString("approval_id",id).commit();
            String current=trust().sessionFence(other);check(current!=null&&!fence.equals(current));
            check(trust().sessionFence(config)==null);check(!trust().stopIfCurrent(config,fence));check(!trust().stopIfCurrent(other,fence));
            reboot();check(trust().isCurrent(other,current));check(id.equals(trust().read(other).getString("approval_id")));
        });
        test("legacy session fence is stable without migration and differs from modern consent",()->{
            String id=begin();trust().completeApproval(config,id);String modern=trust().sessionFence(config);
            FakeDisk.record.remove("phase");FakeDisk.record.remove("approval_id");FakeDisk.record.put("sequence",845L);
            FakeDisk.preferences.edit().remove("approval_id").commit();
            var original=new java.util.LinkedHashMap<>(FakeDisk.record.fields);int saves=FakeDisk.saves,commits=FakeDisk.preferences.calls;
            String legacy=trust().sessionFence(config);check(legacy!=null&&!legacy.equals(modern));
            check(legacy.equals(TrustedGrant.fenceOf(trust().read(config))));
            check(!trust().stopIfCurrent(config,modern));check(original.equals(FakeDisk.record.fields));
            check(FakeDisk.saves==saves&&FakeDisk.preferences.calls==commits);reboot();check(trust().isCurrent(config,legacy));
            trust().consume(config,846);check(legacy.equals(trust().sessionFence(config)));check(!FakeDisk.record.has("phase")&&!FakeDisk.record.has("approval_id"));
        });
        test("missing and pending consent cannot produce or satisfy a session fence",()->{
            check(trust().sessionFence(config)==null);check(!trust().isCurrent(config,null));check(!trust().stopIfCurrent(config,null));
            String id=begin();int saves=FakeDisk.saves,commits=FakeDisk.preferences.calls;
            check(trust().sessionFence(config)==null);check(!trust().isCurrent(config,id));check(!trust().stopIfCurrent(config,id));
            check(id.equals(trust().pending(config).getString("approval_id")));check(FakeDisk.saves==saves&&FakeDisk.preferences.calls==commits);
        });
        test("invalid STOP fences preserve the current approved record",()->{
            String id=begin();trust().completeApproval(config,id);String fence=trust().sessionFence(config);
            int saves=FakeDisk.saves,commits=FakeDisk.preferences.calls;
            for(String stale:new String[]{null,"",id,"invalid",fence+"x"}){
                check(!trust().isCurrent(config,stale));check(!trust().stopIfCurrent(config,stale));
            }
            check(trust().isCurrent(config,fence));check(FakeDisk.saves==saves&&FakeDisk.preferences.calls==commits);
        });
        test("persisted STOP needs no controller and remains closed after process restart",()->{
            String id=begin();trust().completeApproval(config,id);String fence=trust().sessionFence(config);
            // No PreviewService, in-memory controller, or UI state participates in this operation.
            reboot();check(trust().stopIfCurrent(config,fence));closed();check(trust().sessionFence(config)==null);
            check(!trust().stopIfCurrent(config,fence));reboot();closed();check(trust().pending(config)==null);
            check(!trust().isCurrent(config,fence));check(!ApprovalRecovery.resume(context,config,null));
        });
        test("stopped marker refuses session fence even when encrypted deletion failed",()->{
            String id=begin();trust().completeApproval(config,id);String fence=trust().sessionFence(config);
            FakeDisk.failForget=true;check(trust().stopIfCurrent(config,fence));check(FakeDisk.record!=null);
            check(trust().sessionFence(config)==null);check(!trust().isCurrent(config,fence));check(!trust().stopIfCurrent(config,fence));
            reboot();closed();check(trust().sessionFence(config)==null);check(trust().pending(config)==null);
        });
        test("STOP record deletion stays durable when the stopped marker commit fails",()->{
            String id=begin();trust().completeApproval(config,id);String fence=trust().sessionFence(config);
            FakeDisk.preferences.failAt=FakeDisk.preferences.calls+1;check(trust().stopIfCurrent(config,fence));check(FakeDisk.record==null);
            reboot();closed();check(trust().sessionFence(config)==null);check(!trust().isCurrent(config,fence));
        });
        test("STOP reports failure when neither durable stopped marker nor record deletion succeeds",()->{
            String id=begin();trust().completeApproval(config,id);String fence=trust().sessionFence(config);
            FakeDisk.preferences.failAt=FakeDisk.preferences.calls+1;FakeDisk.failForget=true;
            expectFailure(()->trust().stopIfCurrent(config,fence));closed();check(FakeDisk.record!=null);
            // Both disk writes failed: only this process is closed; never report durable STOP success.
            reboot();check(trust().isCurrent(config,fence));check(trust().stopIfCurrent(config,fence));reboot();closed();
        });
        test("grant read failure refuses fence and STOP without deleting or rewriting consent",()->{
            String id=begin();trust().completeApproval(config,id);String fence=trust().sessionFence(config);
            var original=new java.util.LinkedHashMap<>(FakeDisk.record.fields);int saves=FakeDisk.saves,commits=FakeDisk.preferences.calls;
            FakeDisk.failReadRecord=true;
            expectFailure(()->trust().sessionFence(config));expectFailure(()->trust().isCurrent(config,fence));
            expectFailure(()->trust().stopIfCurrent(config,fence));check(original.equals(FakeDisk.record.fields));
            check(FakeDisk.saves==saves&&FakeDisk.preferences.calls==commits);reboot();check(trust().isCurrent(config,fence));
        });
        test("remote revoke clears success footer tied to old claim",()->{
            ConnectionFeedback feedback=new ConnectionFeedback();feedback.approved(CLAIM_A);
            check(SUCCESS.equals(feedback.reconcile(SUCCESS,CLAIM_A,null)));
            String revoked=feedback.reconcile(SUCCESS,null,null);check(!SUCCESS.equals(revoked));check(revoked.contains("已解除"));
            check(revoked.equals(feedback.reconcile(revoked,null,null)));
        });
        test("service recovery success footer follows the same pending claim",()->{
            ConnectionFeedback feedback=new ConnectionFeedback();check("waiting".equals(feedback.reconcile("waiting",null,CLAIM_A)));
            check(SUCCESS.equals(feedback.reconcile("waiting",CLAIM_A,null)));
            check(!SUCCESS.equals(feedback.reconcile(SUCCESS,CLAIM_B,null)));
        });
        test("owner disconnect message takes precedence after explicit feedback reset",()->{
            ConnectionFeedback feedback=new ConnectionFeedback();feedback.approved(CLAIM_A);feedback.clear();
            check("owner stopped".equals(feedback.reconcile("owner stopped",null,null)));
        });
        test("terminal recovery refusal clears stale connecting footer",()->{
            ConnectionFeedback feedback=new ConnectionFeedback();feedback.reconcile("connecting",null,CLAIM_A);
            check(feedback.reconcile("connecting",null,null).contains("已失效"));
        });
        test("empty multi schema persists required JSON-null fields across process restart",()->{
            trust().enableMulti(config);explicitNull(FakeDisk.record,"pending");explicitNull(FakeDisk.record,"invitation");
            reboot();check(trust().multi(config)&&trust().grants(config).isEmpty()&&trust().pending(config)==null&&trust().invitation(config)==null);
            explicitNull(FakeDisk.record,"pending");explicitNull(FakeDisk.record,"invitation");
        });
        test("approved single migration retains required JSON-null fields and consumed sequence",()->{
            String id=begin();trust().completeApproval(config,id);trust().consume(config,37);trust().enableMulti(config);
            explicitNull(FakeDisk.record,"pending");explicitNull(FakeDisk.record,"invitation");reboot();
            check(trust().multi(config)&&trust().grant(config,CLAIM_A).getLong("sequence")==37);
            check(trust().pending(config)==null&&trust().invitation(config)==null);
        });
        test("approval completion and cancellation retain required container null fields",()->{
            trust().enableMulti(config);String a=begin();trust().completeApproval(config,a);trust().requireLease(config,CLAIM_A);
            JSONObject invite=trust().beginInvitation(config,0);String b=trust().beginApproval(config,CLAIM_B,"B");
            trust().cancelApproval(config,b);explicitNull(FakeDisk.record,"pending");check("canceled".equals(trust().invitation(config).getString("phase")));
            check(trust().finishInvitationCancellation(config,invite.getString("invitation_id"),1));explicitNull(FakeDisk.record,"invitation");
            trust().beginInvitation(config,1);b=trust().beginApproval(config,CLAIM_B,"B");check(trust().completeApproval(config,b));
            explicitNull(FakeDisk.record,"pending");explicitNull(FakeDisk.record,"invitation");reboot();
            check(trust().validClaims(config).equals(java.util.Set.of(CLAIM_A,CLAIM_B))&&FakeDisk.record.getLong("invitation_sequence")==2);
        });
        test("multi stop preserves explicit empty fields and invitation watermark for later approval",()->{
            trust().enableMulti(config);String a=begin();trust().completeApproval(config,a);trust().requireLease(config,CLAIM_A);
            trust().beginInvitation(config,6);trust().beginApproval(config,CLAIM_B,"B");trust().stop();
            explicitNull(FakeDisk.record,"pending");explicitNull(FakeDisk.record,"invitation");reboot();
            check(trust().multi(config)&&trust().grants(config).isEmpty()&&FakeDisk.record.getLong("invitation_sequence")==7);
            String next=begin();trust().completeApproval(config,next);check(trust().validClaims(config).equals(java.util.Set.of(CLAIM_A)));
        });
        test("deleted required null differs from explicit null and cannot silently reopen stored grants",()->{
            String id=begin();trust().completeApproval(config,id);trust().enableMulti(config);JSONObject saved=FakeDisk.record.copy();
            for(String key:java.util.List.of("pending","invitation")){
                FakeDisk.record=saved.copy().put(key,null);check(!FakeDisk.record.has(key));int saves=FakeDisk.saves;
                expectFailure(()->trust().multi(config));expectFailure(()->trust().grant(config,CLAIM_A));check(FakeDisk.saves==saves);
                FakeDisk.record.put(key,JSONObject.NULL);check(trust().grant(config,CLAIM_A)!=null);
            }
        });
        test("explicit multi migration preserves legacy identity claim sequence and individual fence",()->{
            String id=begin();trust().completeApproval(config,id);trust().consume(config,91);
            FakeDisk.record.remove("phase");FakeDisk.record.remove("approval_id");FakeDisk.preferences.edit().remove("approval_id").commit();
            String old=trust().sessionFence(config),identity=FakeDisk.record.getString("identity");
            trust().enableMulti(config);check(trust().multi(config));JSONObject a=trust().grant(config,CLAIM_A);
            check(a.getLong("sequence")==91&&identity.equals(a.getString("identity")));check(old.equals(trust().grantFence(config,CLAIM_A)));
            check(!a.getBoolean("lease_required")&&!a.getBoolean("lease_ready"));reboot();check(trust().grant(config,CLAIM_A).getLong("sequence")==91);
        });
        test("multi enable failure preserves the approved single grant",()->{
            String id=begin();trust().completeApproval(config,id);trust().consume(config,15);FakeDisk.failSaveAt=FakeDisk.saves+1;
            expectFailure(()->trust().enableMulti(config));check(!trust().multi(config));check(trust().read(config).getLong("sequence")==15);
        });
        test("multi enable marker failure leaves encrypted schema readable and cannot invent grants",()->{
            String id=begin();trust().completeApproval(config,id);FakeDisk.preferences.failAt=FakeDisk.preferences.calls+1;
            expectFailure(()->trust().enableMulti(config));reboot();check(trust().multi(config));check(trust().validClaims(config).equals(java.util.Set.of(CLAIM_A)));
            trust().enableMulti(config);check(trust().grant(config,CLAIM_A)!=null);
        });
        test("enabled multi refuses legacy backup and unknown schema instead of downgrading",()->{
            String id=begin();trust().completeApproval(config,id);JSONObject old=FakeDisk.record.copy();trust().enableMulti(config);JSONObject modern=FakeDisk.record.copy();
            FakeDisk.record=old;expectFailure(()->trust().read(config));expectFailure(()->trust().multi(config));
            FakeDisk.record=modern;FakeDisk.record.put("schema_version",3);expectFailure(()->trust().multi(config));
        });
        test("another enrollment identity cannot import the previous grant collection",()->{
            String id=begin();trust().completeApproval(config,id);trust().enableMulti(config);EnrollmentStore.Config other=new EnrollmentStore.Config(config.url,"another-token");
            check(trust().grant(other,CLAIM_A)==null);expectFailure(()->trust().enableMulti(other));check(trust().grant(config,CLAIM_A)!=null);
        });
        test("pending single approval migrates without granting before its same approval completes",()->{
            String id=begin();trust().enableMulti(config);closed();check(id.equals(trust().pending(config).getString("approval_id")));
            check(ApprovalRecovery.resume(context,config,null));check("/v1/device/grants/approve".equals(request(0).path));
            request(0).respond(accepted().put("claim_id",CLAIM_A),200,null);check(trust().grant(config,CLAIM_A)!=null);
            check(!trust().grant(config,CLAIM_A).getBoolean("lease_required"));
        });
        test("second grant approval preserves A and uses independent replay sequences and fences",()->{
            String id=begin();trust().completeApproval(config,id);trust().enableMulti(config);String carrier=trust().sessionFence(config),aFence=trust().grantFence(config,CLAIM_A);
            trust().consume(config,CLAIM_A,48);String b=trust().beginApproval(config,CLAIM_B,"AI B");check(trust().grant(config,CLAIM_B)==null);
            check(trust().isCurrent(config,carrier));trust().completeApproval(config,b);trust().consume(config,CLAIM_B,9);
            check(trust().grant(config,CLAIM_A).getLong("sequence")==48&&trust().grant(config,CLAIM_B).getLong("sequence")==9);
            check(trust().grant(config,CLAIM_B).getBoolean("lease_required"));check(trust().isCurrent(config,carrier)&&aFence.equals(trust().grantFence(config,CLAIM_A)));
            expectFailure(()->trust().consume(config,CLAIM_B,9));expectFailure(()->trust().consume(config,49));
        });
        test("B intent marker or encrypted write failure does not stop A",()->{
            String id=begin();trust().completeApproval(config,id);trust().enableMulti(config);String carrier=trust().sessionFence(config);
            FakeDisk.preferences.failAt=FakeDisk.preferences.calls+1;expectFailure(()->trust().beginApproval(config,CLAIM_B,"B"));check(trust().isCurrent(config,carrier));
            FakeDisk.failSaveAt=FakeDisk.saves+1;expectFailure(()->trust().beginApproval(config,CLAIM_B,"B"));check(trust().isCurrent(config,carrier));
            reboot();check(trust().validClaims(config).equals(java.util.Set.of(CLAIM_A)));
        });
        test("B approved-phase write failure preserves A and pending retry",()->{
            String id=begin();trust().completeApproval(config,id);trust().enableMulti(config);String b=trust().beginApproval(config,CLAIM_B,"B");
            FakeDisk.failSaveAt=FakeDisk.saves+1;expectFailure(()->trust().completeApproval(config,b));check(trust().grant(config,CLAIM_A)!=null&&trust().grant(config,CLAIM_B)==null);
            reboot();check(b.equals(trust().pending(config).getString("approval_id")));check(trust().completeApproval(config,b));
        });
        test("B final write failure survives restart without modifying A sequence",()->{
            String id=begin();trust().completeApproval(config,id);trust().enableMulti(config);trust().consume(config,CLAIM_A,76);
            String b=trust().beginApproval(config,CLAIM_B,"B");FakeDisk.failSaveAt=FakeDisk.saves+2;expectFailure(()->trust().completeApproval(config,b));
            check(trust().grant(config,CLAIM_B)==null&&trust().grant(config,CLAIM_A).getLong("sequence")==76);reboot();check(trust().completeApproval(config,b));
            check(trust().grant(config,CLAIM_A).getLong("sequence")==76&&trust().grant(config,CLAIM_B)!=null);
        });
        test("B terminal HTTP refusal cancels only B and late approval cannot revive it",()->{
            String id=begin();trust().completeApproval(config,id);trust().enableMulti(config);String b=trust().beginApproval(config,CLAIM_B,"B");
            final ApprovalRecovery.Outcome[] result={null};ApprovalRecovery.resume(context,config,v->result[0]=v);request(0).respond(null,410,"expired");
            check(result[0]==ApprovalRecovery.Outcome.STOPPED&&trust().grant(config,CLAIM_A)!=null&&trust().pending(config)==null);
            check(!trust().completeApproval(config,b));request(0).respond(accepted().put("claim_id",CLAIM_B),200,null);check(trust().grant(config,CLAIM_B)==null);
        });
        test("cancel marker fences B even if the shared-container rewrite fails",()->{
            String id=begin();trust().completeApproval(config,id);trust().enableMulti(config);String b=trust().beginApproval(config,CLAIM_B,"B");
            FakeDisk.failSaveAt=FakeDisk.saves+1;expectFailure(()->trust().cancelApproval(config,b));reboot();
            check(trust().pending(config)==null&&!trust().completeApproval(config,b)&&trust().grant(config,CLAIM_A)!=null);
        });
        test("multi duplicate approval callback finds its own grant rather than first owner overview",()->{
            String id=begin();trust().completeApproval(config,id);trust().enableMulti(config);trust().beginApproval(config,CLAIM_B,"B");
            final ApprovalRecovery.Outcome[] outcome={null};ApprovalRecovery.resume(context,config,v->outcome[0]=v);request(0).respond(accepted().put("claim_id",CLAIM_B),200,null);
            trust().consume(config,CLAIM_B,23);request(0).respond(accepted().put("claim_id",CLAIM_B),200,null);
            check(outcome[0]==ApprovalRecovery.Outcome.APPROVED&&trust().grant(config,CLAIM_B).getLong("sequence")==23);
        });
        test("multi approval success for a different claim remains pending and never grants",()->{
            trust().enableMulti(config);String id=begin();ApprovalRecovery.resume(context,config,null);request(0).respond(accepted().put("claim_id",CLAIM_B),200,null);
            check(trust().grant(config,CLAIM_A)==null&&id.equals(trust().pending(config).getString("approval_id")));
        });
        test("single revoke preserves other grants carrier and monotonic lease requirement",()->{
            String id=begin();trust().completeApproval(config,id);trust().enableMulti(config);String b=trust().beginApproval(config,CLAIM_B,"B");trust().completeApproval(config,b);
            String carrier=trust().sessionFence(config);trust().requireLease(config,CLAIM_A);check(trust().grant(config,CLAIM_A).getBoolean("lease_required"));
            reboot();check(trust().grant(config,CLAIM_A).getBoolean("lease_ready"));trust().revoke(config,CLAIM_B);
            check(trust().isCurrent(config,carrier)&&trust().grant(config,CLAIM_B)==null);trust().revoke(config,CLAIM_A);check(trust().sessionFence(config)==null);
            String next=begin();trust().completeApproval(config,next);check(!trust().isCurrent(config,carrier));check(!trust().stopIfCurrent(config,carrier));
        });
        test("remote intersection removes local grants and never adds remote-only consent",()->{
            String id=begin();trust().completeApproval(config,id);trust().enableMulti(config);trust().retain(config,java.util.Set.of(CLAIM_A,CLAIM_B));
            check(trust().validClaims(config).equals(java.util.Set.of(CLAIM_A)));trust().retain(config,java.util.Set.of(CLAIM_B));check(trust().validClaims(config).isEmpty());
        });
        test("four approved grants are the hard bound with one unique pending",()->{
            trust().enableMulti(config);String[] claims={CLAIM_A,CLAIM_B,"cccccccccccccccccccccccccccccccc","dddddddddddddddddddddddddddddddd"};
            for(String claim:claims){String id=trust().beginApproval(config,claim,"AI");trust().completeApproval(config,id);}
            check(trust().grants(config).size()==4);expectFailure(()->trust().beginApproval(config,"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","fifth"));
            expectFailure(()->trust().beginInvitation(config,0));check(trust().grants(config).size()==4);
        });
        test("only one pending approval may exist alongside established A",()->{
            String id=begin();trust().completeApproval(config,id);trust().enableMulti(config);String b=trust().beginApproval(config,CLAIM_B,"B");
            check(b.equals(trust().beginApproval(config,CLAIM_B,"B")));expectFailure(()->trust().beginApproval(config,"cccccccccccccccccccccccccccccccc","C"));
            check(trust().grant(config,CLAIM_A)!=null&&b.equals(trust().pending(config).getString("approval_id")));
        });
        test("invitation intent persists before HTTP and retries identical random identifiers",()->{
            trust().enableMulti(config);JSONObject one=trust().beginInvitation(config,7);check(one.getLong("invitation_sequence")==8&&RelayApi.requests.isEmpty());
            String id=one.getString("invitation_id"),ticket=one.getString("ticket");reboot();JSONObject again=trust().beginInvitation(config,99);
            check(id.equals(again.getString("invitation_id"))&&ticket.equals(again.getString("ticket"))&&again.getLong("invitation_sequence")==8);
            check(trust().completeInvitation(config,id,8,TrustedGrant.DISPLAY_EXPIRY,"1".repeat(64),"2".repeat(64)));
            check(!trust().completeInvitation(config,id,8,TrustedGrant.DISPLAY_EXPIRY+1,"1".repeat(64),"2".repeat(64)));
        });
        test("canceled and expired invitation intent retains watermark until matching HTTP acknowledgment",()->{
            trust().enableMulti(config);JSONObject one=trust().beginInvitation(config,0);String id=one.getString("invitation_id"),ticket=one.getString("ticket");
            check(trust().cancelInvitation(config,id,1));reboot();check("canceled".equals(trust().invitation(config).getString("phase")));
            check(id.equals(trust().beginInvitation(config,9).getString("invitation_id")));check(!trust().finishInvitationCancellation(config,id,2));
            check(trust().finishInvitationCancellation(config,id,1));JSONObject next=trust().beginInvitation(config,0);
            check(next.getLong("invitation_sequence")==2&&!ticket.equals(next.getString("ticket"))&&!id.equals(next.getString("invitation_id")));
            check(!trust().completeInvitation(config,id,1,TrustedGrant.DISPLAY_EXPIRY,"1".repeat(64),"2".repeat(64)));
        });
        test("second invitation waits for existing legacy grant to prove lease readiness",()->{
            String id=begin();trust().completeApproval(config,id);trust().enableMulti(config);expectFailure(()->trust().beginInvitation(config,0));
            trust().requireLease(config,CLAIM_A);JSONObject invite=trust().beginInvitation(config,0);check(invite!=null&&trust().grant(config,CLAIM_A)!=null);
        });
        test("legacy invitation adoption preserves original ticket and yields compatible sole grant",()->{
            trust().enableMulti(config);String ticket="f".repeat(32),invite="e".repeat(32);
            check(trust().adoptLegacyInvitation(config,invite,1,ticket,TrustedGrant.DISPLAY_EXPIRY,"1".repeat(64),"2".repeat(64)));
            check(ticket.equals(trust().invitation(config).getString("ticket")));String id=begin();trust().completeApproval(config,id);
            check(!trust().grant(config,CLAIM_A).getBoolean("lease_required")&&trust().invitation(config)==null);
        });
        test("global STOP clears all consent without erasing multi mode or invitation watermark",()->{
            trust().enableMulti(config);JSONObject invite=trust().beginInvitation(config,10);String id=begin();trust().completeApproval(config,id);String carrier=trust().sessionFence(config);
            trust().stop();reboot();check(trust().multi(config)&&trust().grants(config).isEmpty()&&trust().pending(config)==null&&trust().invitation(config)==null);
            JSONObject next=trust().beginInvitation(config,0);check(next.getLong("invitation_sequence")==12);String renewed=begin();trust().completeApproval(config,renewed);
            check(!trust().isCurrent(config,carrier)&&!trust().stopIfCurrent(config,carrier));
        });
        test("persisted global stop nonce fences leftover grants pending and invitations after write failure",()->{
            String id=begin();trust().completeApproval(config,id);trust().enableMulti(config);String b=trust().beginApproval(config,CLAIM_B,"B");
            FakeDisk.failSaveAt=FakeDisk.saves+1;trust().stop();reboot();check(trust().multi(config)&&trust().grants(config).isEmpty());
            check(trust().pending(config)==null&&!trust().completeApproval(config,b));String next=begin();trust().completeApproval(config,next);
            check(trust().validClaims(config).equals(java.util.Set.of(CLAIM_A))&&trust().grant(config,CLAIM_B)==null);
        });
        test("same claim new approval replaces only its individual fence while other AI stays current",()->{
            String id=begin();trust().completeApproval(config,id);trust().enableMulti(config);String b=trust().beginApproval(config,CLAIM_B,"B");trust().completeApproval(config,b);
            String carrier=trust().sessionFence(config),old=trust().grantFence(config,CLAIM_B);trust().revoke(config,CLAIM_B);
            String next=trust().beginApproval(config,CLAIM_B,"B again");trust().completeApproval(config,next);
            check(!old.equals(trust().grantFence(config,CLAIM_B))&&trust().isCurrent(config,carrier)&&trust().grant(config,CLAIM_A)!=null);
        });
        test("explicit revoke also invalidates that claim's pending approval callback",()->{
            String id=begin();trust().completeApproval(config,id);trust().enableMulti(config);String b=trust().beginApproval(config,CLAIM_B,"B");
            trust().revoke(config,CLAIM_B);check(!trust().completeApproval(config,b)&&trust().grant(config,CLAIM_A)!=null);
        });
        test("crash after invitation intent save restarts with exactly the same durable intent",()->{
            trust().enableMulti(config);FakeDisk.crashAfterSaveAt=FakeDisk.saves+1;expectCrash(()->trust().beginInvitation(config,15));
            JSONObject saved=FakeDisk.record.getJSONObject("invitation");String id=saved.getString("invitation_id"),ticket=saved.getString("ticket");reboot();
            JSONObject recovered=trust().beginInvitation(config,20);check(id.equals(recovered.getString("invitation_id"))&&ticket.equals(recovered.getString("ticket")));
            check(recovered.getLong("invitation_sequence")==16&&trust().grants(config).isEmpty());
        });
        test("invitation write failure neither consumes local watermark nor modifies an existing AI",()->{
            String id=begin();trust().completeApproval(config,id);trust().enableMulti(config);trust().requireLease(config,CLAIM_A);
            String carrier=trust().sessionFence(config);FakeDisk.failSaveAt=FakeDisk.saves+1;expectFailure(()->trust().beginInvitation(config,3));
            check(trust().invitation(config)==null&&trust().isCurrent(config,carrier));reboot();check(trust().beginInvitation(config,3).getLong("invitation_sequence")==4);
        });
        test("cancel invitation invalidates a delayed approval but does not delete an existing AI",()->{
            String id=begin();trust().completeApproval(config,id);trust().enableMulti(config);trust().requireLease(config,CLAIM_A);JSONObject invite=trust().beginInvitation(config,0);
            String b=trust().beginApproval(config,CLAIM_B,"B");check(trust().cancelInvitation(config,invite.getString("invitation_id"),1));
            check(!trust().completeApproval(config,b)&&trust().grant(config,CLAIM_A)!=null&&trust().grant(config,CLAIM_B)==null);
        });
        test("empty multi approval activation fault stays closed and retries only the same persisted consent",()->{
            trust().enableMulti(config);String id=begin();FakeDisk.preferences.failAt=FakeDisk.preferences.calls+1;
            expectFailure(()->trust().completeApproval(config,id));closed();reboot();check(id.equals(trust().pending(config).getString("approval_id")));
            check(trust().completeApproval(config,id)&&trust().grant(config,CLAIM_A)!=null);
        });
        test("multi malformed duplicate grants and missing container fields fail closed",()->{
            String id=begin();trust().completeApproval(config,id);trust().enableMulti(config);JSONObject valid=FakeDisk.record.copy();
            FakeDisk.record.getJSONArray("grants").put(FakeDisk.record.getJSONArray("grants").getJSONObject(0).copy());expectFailure(()->trust().read(config));
            FakeDisk.record=valid;FakeDisk.record.remove("invitation");expectFailure(()->trust().multi(config));
        });
        System.out.println("TOTAL "+passed);
    }
}
