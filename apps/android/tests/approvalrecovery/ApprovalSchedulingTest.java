package io.airtap.preview;

import android.content.Context;
import org.json.JSONObject;
import org.json.JSONArray;

/** Production method scheduling: no Activity refresh after the owner immediately leaves. */
public final class ApprovalSchedulingTest {
    static final String A="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",B="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    static EnrollmentStore.Config config;static PreviewService service;static MainActivityHarness activity;static int count;
    interface Check {void run() throws Exception;}
    static void require(boolean value){if(!value)throw new AssertionError();}
    static void test(String name,Check body)throws Exception{reset();body.run();count++;System.out.println("PASS "+name);}
    static void reset()throws Exception{
        FakeDisk.reset();RelayApi.requests.clear();WorkspaceShares.revocations=0;android.os.SystemClock.now=100000;
        var attempt=ApprovalRecovery.class.getDeclaredField("attempt");attempt.setAccessible(true);attempt.set(null,null);
        SessionService.permitted=true;SessionService.ready=SessionService.deferBegin=SessionService.startFails=false;SessionService.begun=SessionService.ended=0;
        config=new EnrollmentStore.Config("https://relay.invalid/d/11111111111111111111111111111111","synthetic-secret-A");FakeDisk.config=config;
        service=new PreviewService();activity=new MainActivityHarness(config,A);
    }
    static TrustedGrant trust(){return new TrustedGrant(new Context());}
    static RelayApi.Request request(int index){return RelayApi.requests.get(index);}
    static JSONObject approved(){return RelayApi.json("protocol_version",4,"ok",true,"persistent",true,"all_apps",true,"allow_control",true,"allow_tap",false,"session_expires_at",JSONObject.NULL,"allowed_packages",new JSONArray());}
    static JSONObject pairing(String claim){return approved().put("state","approved").put("candidate",RelayApi.json("claim_id",claim));}
    static void leaveImmediately(){activity.pressApprove();activity.onDestroy();require(activity.isDestroyed());}
    static void approvedReply(int index){require("POST".equals(request(index).method));request(index).respond(approved(),200,null);}
    static void connectedReply(int index,String claim){require("GET".equals(request(index).method));request(index).respond(pairing(claim),200,null);}
    static void ready(){require(service.authorized&&service.connects==1&&service.networkStarts==1&&SessionService.begun==1);}
    static void closed()throws Exception{require(!service.authorized&&service.connects==0&&trust().read(config)==null);}
    static void active()throws Exception{String id=trust().beginApproval(config,A,"synthetic");trust().completeApproval(config,id);trust().consume(config,87);service.restore();connectedReply(0,A);ready();require(service.executable());}
    public static void main(String[] args)throws Exception{
        test("approve immediately hands pending intent to service before Activity Back",()->{
            activity.pressApprove();require(service.restoring&&RelayApi.requests.size()==1);require(trust().pending(config)!=null);
            int uiChanges=activity.changes;activity.onDestroy();approvedReply(0);
            require(RelayApi.requests.size()==2&&service.restoring);connectedReply(1,A);ready();require(activity.changes==uiChanges);
        });
        test("approval success after immediate Back dispatches connection without reopening Activity",()->{
            leaveImmediately();approvedReply(0);connectedReply(1,A);ready();require(service.main.pending()==0);
        });
        test("unknown approval after immediate Back retries in service and then connects",()->{
            leaveImmediately();request(0).respond(null,0,"timeout");closed();require(service.restoring&&service.main.pending()==1);
            service.main.advance(9999);require(RelayApi.requests.size()==1);service.main.advance(1);
            require(RelayApi.requests.size()==2&&service.restoring);approvedReply(1);connectedReply(2,A);ready();
        });
        test("Activity refresh bursts cannot start duplicate HTTP or stack retry timers",()->{
            leaveImmediately();for(int i=0;i<100;i++)service.restore();require(RelayApi.requests.size()==1&&service.main.pending()==0);
            request(0).respond(null,503,"unavailable");for(int i=0;i<100;i++)service.restore();require(service.main.pending()==1);
            service.main.advance(10000);require(RelayApi.requests.size()==2);approvedReply(1);connectedReply(2,A);ready();
        });
        test("service survives consecutive unknown outcomes with one bounded retry gate",()->{
            leaveImmediately();request(0).respond(null,0,"timeout");service.main.advance(10000);
            request(1).respond(null,0,"timeout");service.main.advance(10000);
            request(2).respond(null,0,"timeout");service.main.advance(10000);
            require(RelayApi.requests.size()==3&&service.restoring&&service.main.pending()==1);
            service.main.advance(10000);require(RelayApi.requests.size()==4);approvedReply(3);connectedReply(4,A);ready();
        });
        test("owner stop fences late approval and leaves no background retry",()->{
            leaveImmediately();int generation=service.generation;service.stop("owner stop");require(service.generation==generation+1);require(WorkspaceShares.revocations==1);
            approvedReply(0);service.main.advance(60000);closed();require(RelayApi.requests.size()==1&&service.main.pending()==0&&trust().pending(config)==null);
        });
        test("owner stop cancels scheduled retry before it can send HTTP",()->{
            leaveImmediately();request(0).respond(null,0,"timeout");require(service.main.pending()==1);service.stop("owner stop");
            service.main.advance(120000);closed();require(RelayApi.requests.size()==1&&service.main.pending()==0);
        });
        test("owner stop between approval and pairing GET fences connection startup",()->{
            leaveImmediately();approvedReply(0);require(trust().read(config)!=null);service.stop("owner stop");connectedReply(1,A);
            service.main.advance(60000);closed();require(SessionService.begun==0&&service.main.pending()==0);
        });
        test("old success cannot clear new claim service restore ownership",()->{
            leaveImmediately();service.stop("new invitation");activity=new MainActivityHarness(config,B);leaveImmediately();
            require(RelayApi.requests.size()==2&&service.restoring);approvedReply(0);require(service.restoring&&RelayApi.requests.size()==2);
            approvedReply(1);connectedReply(2,B);ready();require(B.equals(trust().read(config).getString("claim")));
        });
        test("relay revoke refusal after Back clears intent without periodic polling",()->{
            leaveImmediately();request(0).respond(null,409,"claim expired or revoked");service.main.advance(120000);
            closed();require(trust().pending(config)==null&&service.main.pending()==0&&RelayApi.requests.size()==1);
        });
        test("pairing verification network failure after Back resumes without Activity",()->{
            leaveImmediately();approvedReply(0);request(1).respond(null,0,"timeout");require(!service.authorized&&service.main.pending()==1);
            service.main.advance(10000);require(RelayApi.requests.size()==3);connectedReply(2,A);ready();
        });
        test("restored service resumes durable pending approval without any Activity",()->{
            trust().beginApproval(config,A,"synthetic");activity.onDestroy();service.restore();approvedReply(0);connectedReply(1,A);ready();
        });
        test("same already-active grant is not reapproved and retains sequence",()->{
            String id=trust().beginApproval(config,A,"synthetic");trust().completeApproval(config,id);trust().consume(config,87);
            leaveImmediately();require(RelayApi.requests.size()==1&&"GET".equals(request(0).method));connectedReply(0,A);ready();
            require(trust().read(config).getLong("sequence")==87);
        });
        test("notifications absent does not start or persist owner approval",()->{
            SessionService.permitted=false;leaveImmediately();require(RelayApi.requests.isEmpty()&&trust().pending(config)==null);closed();
        });
        test("runtime notification loss suspends execution and retains exact trust and sequence",()->{
            active();int run=service.generation;service.activeCommand=77;SessionService.permitted=false;
            require(!service.executable());require(service.recoveryStatus()==RecoveryStatus.NOTIFICATIONS_REQUIRED);
            SessionService.permitted=true;require(!service.executable());SessionService.permitted=false;
            service.notificationUnavailable(run,false);require(!service.authorized&&!service.connected&&service.activeCommand==0);
            require(service.generation==run+1&&service.ui.cancellations==1&&service.recovery.cancellations==1&&WorkspaceShares.revocations==1);
            require(!service.transportOpen.get()&&trust().read(config).getLong("sequence")==87&&service.main.pending()==1);
            service.main.advance(20000);require(RelayApi.requests.size()==1&&service.main.pending()==1&&!service.executable());
            SessionService.permitted=true;service.main.advance(2000);require(RelayApi.requests.size()==2&&!service.executable());
            connectedReply(1,A);require(service.executable()&&service.connects==2&&trust().read(config).getLong("sequence")==87);
        });
        test("notification restore cannot open execution until matching relay and foreground success",()->{
            active();service.notificationUnavailable(service.generation,true);SessionService.deferBegin=true;
            service.main.advance(5000);require(!service.executable());connectedReply(1,A);
            require(service.authorized&&!service.executable()&&service.connects==1);
            SessionService.ready=true;service.notificationReady(service.generation);require(service.executable()&&service.connects==2);
        });
        test("missing notification in flight cannot activate from successful verification",()->{
            String id=trust().beginApproval(config,A,"synthetic");trust().completeApproval(config,id);service.restore();
            SessionService.permitted=false;connectedReply(0,A);
            require(!service.authorized&&SessionService.begun==0&&service.main.pending()==1&&trust().read(config)!=null);
        });
        test("notification retry gate prevents Activity refresh bursts and repeated start failures",()->{
            active();SessionService.startFails=true;service.notificationUnavailable(service.generation,true);
            for(int i=0;i<100;i++)service.restore();require(service.main.pending()==1&&RelayApi.requests.size()==1);
            service.main.advance(4999);require(RelayApi.requests.size()==1);service.main.advance(1);connectedReply(1,A);
            require(!service.executable()&&trust().read(config)!=null&&service.main.pending()==1);
            service.main.advance(9999);require(RelayApi.requests.size()==2);service.main.advance(1);connectedReply(2,A);
            service.main.advance(19999);require(RelayApi.requests.size()==3);service.main.advance(1);connectedReply(3,A);
            service.main.advance(29999);require(RelayApi.requests.size()==4);service.main.advance(1);connectedReply(4,A);
            require(service.connects==1&&service.main.pending()==1);
        });
        test("owner stop cancels notification polling and stale foreground callbacks",()->{
            active();int old=service.generation;service.notificationUnavailable(old,false);service.stop("owner stop");
            service.notificationReady(old);service.notificationUnavailable(old,true);service.main.advance(60000);
            require(trust().read(config)==null&&!service.executable()&&service.main.pending()==0&&RelayApi.requests.size()==1);
        });
        test("stale notification callbacks cannot suspend or reconnect the recovered generation",()->{
            active();int old=service.generation;service.notificationUnavailable(old,true);service.main.advance(5000);connectedReply(1,A);
            service.notificationUnavailable(old,false);service.notificationReady(old);
            require(service.executable()&&service.connects==2&&service.main.pending()==0&&trust().read(config)!=null);
        });
        test("owner stop fences in-flight notification recovery response",()->{
            active();service.notificationUnavailable(service.generation,true);service.main.advance(5000);service.stop("owner stop");
            connectedReply(1,A);require(!service.executable()&&trust().read(config)==null&&service.main.pending()==0&&service.connects==1);
        });
        test("revoked or changed remote claim never resumes retained notification pair",()->{
            active();service.notificationUnavailable(service.generation,true);service.main.advance(5000);connectedReply(1,B);
            require(!service.executable()&&trust().read(config)==null&&service.main.pending()==0);
        });
        test("notification retry after server410 forgets revoked approval",()->{
            active();service.notificationUnavailable(service.generation,true);service.main.advance(5000);request(1).respond(null,410,"revoked");
            require(trust().read(config)==null&&!service.executable()&&service.main.pending()==0);
        });
        test("repeated network failures after notification loss use one recovery timer",()->{
            active();service.notificationUnavailable(service.generation,true);service.main.advance(5000);request(1).respond(null,0,"timeout");
            for(int i=0;i<100;i++)service.restore();require(service.main.pending()==1&&RelayApi.requests.size()==2);
            service.main.advance(10000);connectedReply(2,A);require(service.executable()&&service.connects==2);
        });
        test("typed homepage states are read-only and reflect readiness without waking",()->{
            active();service.connected=true;require(service.recoveryStatus()==RecoveryStatus.CONNECTED);
            service.ui.screen=ScreenPreparation.State.SCREEN_OFF;require(service.recoveryStatus()==RecoveryStatus.SCREEN_PREPARABLE);
            service.ui.screen=ScreenPreparation.State.DISMISSIBLE_KEYGUARD;require(service.recoveryStatus()==RecoveryStatus.SCREEN_PREPARABLE);
            service.ui.screen=ScreenPreparation.State.AUTHENTICATION_REQUIRED;require(service.recoveryStatus()==RecoveryStatus.SYSTEM_AUTH_REQUIRED);
            service.ui.screen=ScreenPreparation.State.UNAVAILABLE;require(service.recoveryStatus()==RecoveryStatus.SCREEN_UNAVAILABLE);
            require(RelayApi.requests.size()==1&&service.ui.clears==0&&service.recovery.cancellations==0);
        });
        System.out.println("TOTAL "+count);
    }
}
