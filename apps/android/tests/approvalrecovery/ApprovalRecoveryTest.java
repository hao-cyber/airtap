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

    public static void main(String[] args) throws Exception {
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
        test("unknown network response survives process restart without new owner consent",()->{
            String id=begin();ApprovalRecovery.resume(context,config,null);request(0).respond(null,0,"timeout");reboot();
            check(id.equals(trust().pending(config).getString("approval_id")));ApprovalRecovery.resume(context,config,null);accept(0);check(trust().read(config)!=null);
        });
        test("invalid claim and sequence fail closed",()->{
            expectFailure(()->trust().beginApproval(config,"invalid","AI"));closed();String id=begin();trust().completeApproval(config,id);
            expectFailure(()->trust().consume(config,0));expectFailure(()->trust().consume(config,9007199254740992L));
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
        System.out.println("TOTAL "+passed);
    }
}
