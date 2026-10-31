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
        attempt=MigrationRecovery.class.getDeclaredField("attempt");attempt.setAccessible(true);attempt.set(null,null);
        attempt=OwnerRevocations.class.getDeclaredField("inFlight");attempt.setAccessible(true);attempt.set(null,null);
        SessionService.permitted=true;SessionService.ready=SessionService.deferBegin=SessionService.startFails=false;SessionService.begun=SessionService.ended=SessionService.restores=0;SessionService.carrier=SessionService.restoreStartFails=false;
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
    static String migration()throws Exception{return trust().beginMigration(config,trust().sessionFence(config));}
    static void seedSingle()throws Exception{String id=trust().beginApproval(config,A,"synthetic");trust().completeApproval(config,id);trust().consume(config,87);}
    static JSONObject multi(String... claims){
        JSONArray grants=new JSONArray();for(String claim:claims)grants.put(RelayApi.json("claim_id",claim,"name","Synthetic AI","state","approved","command_sequence",87,
                "lease_required",false,"lease_ready",false,"persistent",true,"all_apps",true,"allow_control",true,"allowed_packages",new JSONArray()));
        return RelayApi.json("protocol_version",4,"mode","multi","generation",0,"max_grants",4,"invitation_sequence",0,"grants",grants,"invitation",JSONObject.NULL);
    }
    static final class TrackedSocket implements okhttp3.WebSocket {int cancellations;public void cancel(){cancellations++;}}
    static TrackedSocket attachSocket(){TrackedSocket socket=new TrackedSocket();service.socket=socket;service.connected=true;return socket;}
    static void migrationPost(int readIndex){connectedReply(readIndex,A);require("POST".equals(request(readIndex+1).method)&&"/v1/device/multi/enable".equals(request(readIndex+1).path));}
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
        test("approved recovery enters visible carrier before HTTP without enabling execution",()->{
            String id=trust().beginApproval(config,A,"synthetic");trust().completeApproval(config,id);service.restore();
            require(SessionService.carrier&&SessionService.restores==1&&RelayApi.requests.size()==1&&!service.authorized&&!service.executable()&&service.connects==0);
            request(0).respond(null,0,"timeout");require(SessionService.carrier&&service.main.pending()==1&&trust().read(config)!=null);
        });
        test("storage read failure keeps original trust and resumes after readable retry",()->{
            String id=trust().beginApproval(config,A,"synthetic");trust().completeApproval(config,id);String fence=trust().sessionFence(config);
            FakeDisk.failReadRecord=true;service.restore();require(FakeDisk.record!=null&&!service.authorized&&service.recoveryStatus()==RecoveryStatus.STORAGE_UNAVAILABLE&&service.main.pending()==1);
            FakeDisk.failReadRecord=false;service.main.advance(10000);require(fence.equals(trust().sessionFence(config)));connectedReply(0,A);ready();
        });
        test("storage failure after pairing GET keeps trust and closes carrier",()->{
            String id=trust().beginApproval(config,A,"synthetic");trust().completeApproval(config,id);service.restore();FakeDisk.failReadRecord=true;
            connectedReply(0,A);require(FakeDisk.record!=null&&!service.authorized&&!SessionService.carrier&&service.recoveryStatus()==RecoveryStatus.STORAGE_UNAVAILABLE);
        });
        test("old revoke reply cannot stop replacement consent without a generation change",()->{
            String id=trust().beginApproval(config,A,"synthetic");trust().completeApproval(config,id);service.restore();
            trust().stop();id=trust().beginApproval(config,A,"synthetic new consent");trust().completeApproval(config,id);String next=trust().sessionFence(config);
            request(0).respond(null,410,"revoked");require(next.equals(trust().sessionFence(config))&&!service.authorized);service.restore();connectedReply(1,A);ready();
        });
        test("carrier loss closes commands but retains trust and revalidates",()->{
            active();String fence=trust().sessionFence(config);service.activeCommand=9;service.sessionCarrierUnavailable();
            require(!service.authorized&&!service.executable()&&service.activeCommand==0&&fence.equals(trust().sessionFence(config))&&service.main.pending()==1);
            service.main.advance(2000);connectedReply(1,A);require(service.executable()&&service.connects==2);
        });
        test("STOP from restoring notification closes all retries after durable fence",()->{
            String id=trust().beginApproval(config,A,"synthetic");trust().completeApproval(config,id);service.restore();String fence=trust().sessionFence(config);
            require(trust().stopIfCurrent(config,fence));service.stoppedLocally(fence);connectedReply(0,A);service.main.advance(60000);
            require(!service.authorized&&service.main.pending()==0&&trust().read(config)==null);
        });
        test("pending migration preserves the single socket reference and execution gate through unknown HTTP",()->{
            active();TrackedSocket socket=attachSocket();String fence=service.sessionFence,id=migration();int generation=service.generation;
            service.restore();for(int i=0;i<100;i++)service.restore();require(RelayApi.requests.size()==2&&service.executable());
            require(service.socket==socket&&socket.cancellations==0&&service.connected&&service.generation==generation);
            require(service.connects==1&&service.networkStarts==1&&SessionService.begun==1&&SessionService.restores==1&&WorkspaceShares.revocations==0);
            migrationPost(1);trust().consume(config,88);request(2).respond(null,0,"lost enable response");
            require(service.authorized&&service.executable()&&!service.multiMode&&service.multiRestores==0&&service.sessionFence.equals(fence));
            require(socket.cancellations==0&&service.socket==socket&&service.connects==1&&id.equals(trust().migration(config).getString("id")));
            require("unknown".equals(trust().migration(config).getString("phase"))&&trust().read(config).getLong("sequence")==88);
        });
        test("only verified remote commit replaces the live single carrier with multi restore",()->{
            active();TrackedSocket socket=attachSocket();String oldFence=service.sessionFence;migration();int generation=service.generation;
            service.restore();migrationPost(1);require(!trust().multi(config)&&service.executable()&&socket.cancellations==0);
            trust().consume(config,94);request(2).respond(multi(A),200,null);
            require(trust().multi(config)&&trust().migration(config)==null&&trust().grant(config,A).getLong("sequence")==94);
            require(service.multiMode&&service.multiRestores==1&&service.generation==generation+1&&socket.cancellations==1&&service.socket==null);
            require(!service.authorized&&!service.executable()&&service.connects==1&&!SessionService.ready&&WorkspaceShares.revocations==1);
            require(!oldFence.equals(trust().sessionFence(config))&&oldFence.equals(trust().grantFence(config,A)));
            request(2).respond(multi(A),200,null);require(service.multiRestores==1&&service.generation==generation+1);
        });
        test("first definitive migration rejection keeps the live single carrier without reconnecting",()->{
            active();TrackedSocket socket=attachSocket();String fence=service.sessionFence;migration();int generation=service.generation;
            service.restore();migrationPost(1);request(2).respond(null,404,"unsupported");require(RelayApi.requests.size()==4);
            connectedReply(3,A);require(!trust().multi(config)&&trust().migration(config)==null&&trust().isCurrent(config,fence));
            require(service.executable()&&service.connected&&service.socket==socket&&socket.cancellations==0&&service.generation==generation);
            require(service.connects==1&&SessionService.begun==1&&service.multiRestores==0&&WorkspaceShares.revocations==0);
        });
        test("cold pending migration verifies remote multi without the old single pairing stop path",()->{
            seedSingle();String id=migration();activity.onDestroy();service.restore();
            require(SessionService.carrier&&SessionService.restores==1&&!service.authorized&&!service.executable()&&service.connects==0);
            require(service.recoveryStatus()==RecoveryStatus.VERIFYING_CONNECTION&&RelayApi.requests.size()==1&&id.equals(trust().migration(config).getString("id")));
            request(0).respond(multi(A),200,null);
            require(trust().multi(config)&&trust().grant(config,A).getLong("sequence")==87&&service.multiRestores==1&&service.multiMode);
            require(RelayApi.requests.size()==1&&service.connects==0&&SessionService.begun==0&&SessionService.ended==0);
        });
        test("cold unknown migration waits through carrier ticks and reconciles without duplicate enable",()->{
            seedSingle();String id=migration();trust().dispatchMigration(config,id);activity.onDestroy();service.restore();
            request(0).respond(null,0,"offline");require(SessionService.carrier&&!service.authorized&&trust().read(config)!=null);
            for(int i=0;i<100;i++)service.restore();require(RelayApi.requests.size()==1);
            service.main.advance(4999);service.restore();require(RelayApi.requests.size()==1);
            service.main.advance(1);service.restore();require(RelayApi.requests.size()==2&&!service.executable()&&service.connects==0);
            request(1).respond(multi(A),200,null);require(trust().multi(config)&&service.multiRestores==1&&RelayApi.requests.size()==2);
            require(activity.isDestroyed()&&SessionService.begun==0&&trust().grant(config,A).getLong("sequence")==87);
        });
        test("cold first rejection returns to ordinary single verification only after reconciliation",()->{
            seedSingle();migration();activity.onDestroy();service.restore();migrationPost(0);request(1).respond(null,409,"rejected");
            require(RelayApi.requests.size()==3&&trust().migration(config)!=null&&!service.authorized);
            connectedReply(2,A);require(trust().migration(config)==null&&RelayApi.requests.size()==4&&!service.authorized&&service.multiRestores==0);
            connectedReply(3,A);ready();require(service.executable()&&!service.multiMode&&!trust().multi(config));
        });
        test("old single pairing refusal cannot stop a newly pending migration",()->{
            seedSingle();service.restore();String fence=trust().sessionFence(config);migration();service.restore();require(RelayApi.requests.size()==2);
            request(0).respond(null,410,"stale single refusal");
            require(trust().isCurrent(config,fence)&&trust().migration(config)!=null&&RelayApi.requests.size()==2&&!service.authorized);
            request(1).respond(multi(A),200,null);require(trust().multi(config)&&service.multiRestores==1&&SessionService.ended==0);
        });
        test("old single success after committed migration cannot replace the new multi carrier",()->{
            seedSingle();service.restore();migration();service.restore();request(1).respond(multi(A),200,null);
            int generation=service.generation;connectedReply(0,A);
            require(trust().multi(config)&&service.multiRestores==1&&service.generation==generation&&SessionService.begun==0&&service.connects==0);
        });
        test("owner STOP fences a delayed migration enable success and leaves no reconnect",()->{
            active();TrackedSocket socket=attachSocket();migration();service.restore();migrationPost(1);service.stop("owner stop");int generation=service.generation;
            request(2).respond(multi(A),200,null);service.main.advance(60000);service.restore();
            require(trust().read(config)==null&&trust().migration(config)==null&&!service.authorized&&!service.executable());
            require(service.multiRestores==0&&service.generation==generation&&service.connects==1&&socket.cancellations==1&&service.main.pending()==0);
            require(RelayApi.requests.size()==3&&SessionService.begun==1);
        });
        test("cold restoring notification STOP fences delayed migration reconciliation",()->{
            seedSingle();migration();service.restore();String fence=service.restoringFence;require(fence!=null&&SessionService.carrier);
            require(trust().stopIfCurrent(config,fence));service.stoppedLocally(fence);int generation=service.generation;
            request(0).respond(multi(A),200,null);service.main.advance(60000);service.restore();
            require(trust().read(config)==null&&service.multiRestores==0&&service.generation==generation&&!service.executable()&&service.connects==0);
        });
        test("late old migration cannot change a same-claim replacement service generation",()->{
            active();migration();service.restore();migrationPost(1);service.stop("replace consent");
            String consent=trust().beginApproval(config,A,"new owner consent");trust().completeApproval(config,consent);trust().consume(config,113);service.restore();connectedReply(3,A);
            int generation=service.generation;String fence=service.sessionFence;request(2).respond(multi(A),200,null);
            require(service.generation==generation&&service.executable()&&fence.equals(service.sessionFence)&&!service.multiMode&&service.multiRestores==0);
            require(trust().read(config).getLong("sequence")==113&&!trust().multi(config)&&service.connects==2);
        });
        test("late callback to a replaced service leaves the current instance to restore committed multi",()->{
            seedSingle();migration();PreviewService old=service;old.restore();service=new PreviewService();request(0).respond(multi(A),200,null);
            require(trust().multi(config)&&old.multiRestores==0&&service.multiRestores==0&&!service.authorized);
            service.restore();require(service.multiRestores==1&&service.multiMode&&service.connects==0);
        });
        test("remote multi missing the original claim commits empty multi without grants or execution",()->{
            active();TrackedSocket socket=attachSocket();int generation=service.generation;String id=migration();service.restore();request(1).respond(multi(B),200,null);
            require(trust().multi(config)&&trust().grants(config).isEmpty()&&trust().read(config)==null&&trust().grant(config,B)==null);
            require(trust().migration(config)==null&&trust().sessionFence(config)==null&&!service.authorized&&!service.executable());
            require(service.multiMode&&service.multiRestores==1&&service.generation==generation+1&&socket.cancellations==1&&service.socket==null);
            require(service.connects==1&&SessionService.begun==1&&!SessionService.ready);
            require(!trust().completeMigration(config,id,java.util.Set.of(A)));request(1).respond(multi(A),200,null);
            require(trust().grants(config).isEmpty()&&service.multiRestores==1&&!service.executable()&&service.connects==1);
        });
        test("failed encrypted migration commit keeps the live single while a later carrier tick recovers",()->{
            active();TrackedSocket socket=attachSocket();String fence=service.sessionFence;migration();service.restore();migrationPost(1);
            FakeDisk.failSaveAt=FakeDisk.saves+1;request(2).respond(multi(A),200,null);
            require(!trust().multi(config)&&trust().isCurrent(config,fence)&&service.executable()&&socket.cancellations==0&&service.multiRestores==0);
            service.main.advance(5000);service.restore();request(3).respond(multi(A),200,null);
            require(trust().multi(config)&&service.multiRestores==1&&socket.cancellations==1&&!service.executable());
        });
        test("secondary marker failure still switches to multi and replaces notification execution authority",()->{
            active();TrackedSocket socket=attachSocket();migration();service.restore();migrationPost(1);int old=service.generation;
            FakeDisk.preferences.failAt=FakeDisk.preferences.calls+1;request(2).respond(multi(A),200,null);
            require(trust().multi(config)&&service.multiMode&&service.multiRestores==1&&service.generation==old+1&&socket.cancellations==1);
            require(!FakeDisk.preferences.disk.containsKey("multi_identity")&&!SessionService.ready&&!service.executable()&&SessionService.begun==1);
            SessionService.deferBegin=true;service.finishMultiRestore();require(service.authorized&&!service.executable()&&SessionService.begun==2&&service.connects==1);
            require(trust().sessionFence(config).equals(service.sessionFence));service.notificationReady(old);require(service.connects==1);
            SessionService.ready=true;service.notificationReady(service.generation);require(service.executable()&&service.multiMode&&service.connects==2&&service.networkStarts==2);
        });
        test("cold migration without notification permission dispatches no HTTP until permission returns",()->{
            seedSingle();String id=migration();SessionService.permitted=false;service.restore();
            require(RelayApi.requests.isEmpty()&&id.equals(trust().migration(config).getString("id"))&&!service.executable());
            require(service.recoveryStatus()==RecoveryStatus.NOTIFICATIONS_REQUIRED&&service.main.pending()==1);
            SessionService.permitted=true;service.main.advance(2000);require(RelayApi.requests.size()==1&&SessionService.carrier&&!service.authorized);
            request(0).respond(multi(A),200,null);require(service.multiRestores==1&&trust().multi(config));
        });
        for(boolean all:java.util.List.of(false,true)){
            test("durable "+(all?"all":"original claim")+" revoke barrier blocks a single grant after both STOP writes fail and process restarts",()->{
                seedSingle();String fence=trust().sessionFence(config);OwnerRevocations.queue(new Context(),config,all?null:A);
                FakeDisk.preferences.failAt=FakeDisk.preferences.calls+1;FakeDisk.failForget=true;
                try{trust().stop();throw new AssertionError("both STOP writes must report failure");}catch(IllegalStateException expected){}
                FakeDisk.reboot();service=new PreviewService();activity.onDestroy();require(trust().isCurrent(config,fence));
                service.restore();require(!service.authorized&&!service.executable()&&service.connects==0&&SessionService.begun==0&&SessionService.restores==0);
                require(RelayApi.requests.size()==1&&"POST".equals(request(0).method)&&request(0).token.equals(config.token));
                require((all?"/v1/device/revoke":"/v1/device/grants/revoke").equals(request(0).path));
                if(all)require(request(0).body==null&&OwnerRevocations.allPending(new Context(),config));
                else require(A.equals(request(0).body.getString("claim_id"))&&OwnerRevocations.claimsPending(new Context(),config).contains(A));
                require(trust().isCurrent(config,fence)&&trust().read(config).getLong("sequence")==87);
                request(0).respond(null,0,"offline");for(int i=0;i<100;i++)service.restore();require(RelayApi.requests.size()==1);
                service.main.advance(4999);service.restore();require(RelayApi.requests.size()==1);service.main.advance(1);service.restore();
                require(RelayApi.requests.size()==2&&request(1).path.equals(request(0).path)&&!service.executable()&&service.connects==0);
                if(!all)require(A.equals(request(1).body.getString("claim_id")));
            });
            test("queued "+(all?"all":"original claim")+" revoke prevents a late migration POST from committing the retained grant",()->{
                active();TrackedSocket socket=attachSocket();String fence=service.sessionFence,id=migration();service.restore();migrationPost(1);
                OwnerRevocations.queue(new Context(),config,all?null:A);service.restore();
                require(!service.authorized&&!service.executable()&&socket.cancellations==1&&service.connects==1&&SessionService.begun==1);
                require(RelayApi.requests.size()==4&&(all?"/v1/device/revoke":"/v1/device/grants/revoke").equals(request(3).path));
                request(2).respond(multi(A),200,null);
                require(!trust().multi(config)&&trust().isCurrent(config,fence)&&id.equals(trust().migration(config).getString("id")));
                require(service.multiRestores==0&&!service.executable()&&service.connects==1&&trust().read(config).getLong("sequence")==87);
                require(all?OwnerRevocations.allPending(new Context(),config):OwnerRevocations.claimsPending(new Context(),config).contains(A));
            });
        }
        test("queued original claim revoke fences the first migration read before any enable POST",()->{
            seedSingle();String id=migration();service.restore();OwnerRevocations.queue(new Context(),config,A);
            connectedReply(0,A);require(RelayApi.requests.size()==1&&!trust().multi(config)&&id.equals(trust().migration(config).getString("id")));
            service.restore();require(RelayApi.requests.size()==2&&"/v1/device/grants/revoke".equals(request(1).path));
            require(!service.authorized&&!service.executable()&&service.connects==0&&service.multiRestores==0);
        });
        test("confirmed revoke does not activate a retained single grant before fresh relay verification",()->{
            seedSingle();OwnerRevocations.queue(new Context(),config,A);service.restore();require(RelayApi.requests.size()==1);
            request(0).respond(RelayApi.json("protocol_version",4,"ok",true),200,null);
            require(OwnerRevocations.claimsPending(new Context(),config).isEmpty()&&trust().read(config)!=null&&!service.executable());
            service.restore();require(RelayApi.requests.size()==2&&"/v1/device/pairing".equals(request(1).path)&&!service.authorized&&service.connects==0);
            request(1).respond(null,410,"revoked");require(trust().read(config)==null&&!service.executable()&&SessionService.begun==0&&service.connects==0);
        });
        test("another claim or enrollment revoke cannot block the original single grant",()->{
            seedSingle();OwnerRevocations.queue(new Context(),config,B);
            EnrollmentStore.Config other=new EnrollmentStore.Config(config.url,"other synthetic identity");OwnerRevocations.queue(new Context(),other,null);
            service.restore();require(RelayApi.requests.size()==1&&"/v1/device/pairing".equals(request(0).path));connectedReply(0,A);ready();
            require(service.executable()&&OwnerRevocations.claimsPending(new Context(),config).equals(java.util.Set.of(B)));
            require(!OwnerRevocations.allPending(new Context(),config)&&OwnerRevocations.allPending(new Context(),other));
        });
        System.out.println("TOTAL "+count);
    }
}
