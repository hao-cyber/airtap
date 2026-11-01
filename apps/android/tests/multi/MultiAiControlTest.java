package io.airtap.preview;
import org.json.*;import java.util.*;import android.os.SystemClock;
public final class MultiAiControlTest{
 static final String A="a".repeat(32),B="b".repeat(32),T="c".repeat(32),L="d".repeat(32),ID="00000000-0000-4000-8000-000000000001";static int passed,serial;static EnrollmentStore.Config config;static PreviewService service;static Socket ws;static TrustedGrant trust;
 interface Test{void run()throws Exception;}static void check(boolean v){if(!v)throw new AssertionError();}
 static final class Socket implements okhttp3.WebSocket{List<JSONObject> sent=new ArrayList<>();int close;public boolean send(String s){sent.add(new JSONObject(s));return true;}public boolean close(int c,String why){close=c;return true;}JSONObject last(){return sent.get(sent.size()-1);}}
 static void reset()throws Exception{FakeDisk.reset();RelayApi.requests.clear();OwnerRevocations.all=false;OwnerRevocations.failQueue=false;OwnerRevocations.unknownQueued=0;OwnerRevocations.queuedGeneration=null;OwnerRevocations.revoked=Set.of();SystemClock.now=100000;FileScopes.quiet=true;WorkspaceShares.invalidations=0;config=new EnrollmentStore.Config("https://synthetic.invalid/d/"+A,"e".repeat(64));FakeDisk.config=config;trust=new TrustedGrant(new android.content.Context());String approve=trust.beginApproval(config,A,"synthetic A");trust.completeApproval(config,approve);trust.enableMulti(config);ws=new Socket();service=new PreviewService(config,ws);service.remoteGrants=Set.of(A);hello();}
 static void test(String name,Test test)throws Exception{reset();test.run();passed++;System.out.println("PASS "+name);}
 static void hello(){service.receive(RelayApi.json("type","hello_ack","protocol_version",4,"mode","multi","connection_id",T));}
 static JSONObject fence(long epoch,String claim){return RelayApi.json("type","task_fence","operation_id",String.format("%032x",++serial),"lease_epoch",epoch,"holder",claim==null?JSONObject.NULL:RelayApi.json("claim_id",claim,"task_id",T,"lease_id",L),"ttl_ms",claim==null?0:120000,"renewal_sequence",0);}
 static JSONObject command(String claim,Long epoch,long sequence){return RelayApi.json("type","command","claim_id",claim,"lease_id",epoch==null?JSONObject.NULL:L,"lease_epoch",epoch==null?JSONObject.NULL:epoch,"command",RelayApi.json("id",ID,"action","file_roots","sequence",sequence));}
 static void addB()throws Exception{trust.requireLease(config,A);JSONObject invite=trust.beginInvitation(config,0);trust.completeInvitation(config,invite.getString("invitation_id"),invite.getLong("invitation_sequence"),System.currentTimeMillis()/1000+300,"1".repeat(64),"2".repeat(64));String approval=trust.beginApproval(config,B,"synthetic B");trust.completeApproval(config,approval);service.remoteGrants=Set.of(A,B);}
 static OwnerIdentityHarness singleOwner()throws Exception{FakeDisk.reset();FakeDisk.config=config;RelayApi.requests.clear();RelayApi.throwNext=false;trust=new TrustedGrant(new android.content.Context());String consent=trust.beginApproval(config,A,"original AI");trust.completeApproval(config,consent);trust.consume(config,7);service.ownerModeChanges=0;service.restores=0;OwnerIdentityHarness owner=new OwnerIdentityHarness();owner.bindIdentity(config);return owner;}
 static JSONObject discovery(){return RelayApi.json("protocol_version",4,"enrollment_mode","public","enrollment_expires_at",JSONObject.NULL,"pairing_ttl_seconds",300,"session_ttl_seconds",JSONObject.NULL,"command_budget",JSONObject.NULL,"service_id",T,"label","Synthetic relay","registration_open",true,"control_open",true,"capabilities",new JSONArray().put("companion_v1").put("multi_ai_v1").put("task_lease_v1"));}
 static JSONObject multiState(String claim){JSONArray grants=new JSONArray();if(claim!=null)grants.put(RelayApi.json("claim_id",claim,"name","Synthetic old AI","state","approved","command_sequence",7,"lease_required",false,"lease_ready",false,"persistent",true,"all_apps",true,"allow_control",true,"allowed_packages",new JSONArray()));return RelayApi.json("protocol_version",4,"mode","multi","generation",5,"max_grants",4,"invitation_sequence",0,"grants",grants,"invitation",JSONObject.NULL);}
 static void unchanged(OwnerIdentityHarness owner)throws Exception{check(!trust.multi(config)&&trust.grant(config,A).getLong("sequence")==7&&service.ownerModeChanges==0&&owner.polls==0);}
 static OwnerIdentityHarness invitationOwner()throws Exception{trust.requireLease(config,A);OwnerIdentityHarness owner=new OwnerIdentityHarness();owner.bindIdentity(config);return owner;}
 static long invitationPosts(){return RelayApi.requests.stream().filter(r->r.path.equals("/v1/device/invitations")&&r.method.equals("POST")).count();}
 static JSONObject invitationReply(JSONObject request){return RelayApi.json("protocol_version",4,"invitation_id",request.getString("invitation_id"),"invitation_sequence",request.getLong("invitation_sequence"),"pairing_expires_at",System.currentTimeMillis()/1000+300,"expires_at",JSONObject.NULL,"cli_sha256","1".repeat(64),"agent_sha256","2".repeat(64));}
 public static void main(String[]args)throws Exception{
  test("structured hello and permanent first fence disable the legacy envelope",()->{service.receive(command(A,null,1));check(service.ui.executions==1&&A.equals(service.ui.owner));service.ui.reply.success(new JSONObject());service.receive(fence(1,A));check(ws.last().getBoolean("ok")&&trust.grant(config,A).getBoolean("lease_required"));service.receive(command(A,null,2));check(!ws.last().getBoolean("ok")&&service.ui.executions==1);});
  test("multi refuses raw single commands while unchanged single dispatch remains usable",()->{service.receive(RelayApi.json("id",ID,"action","file_roots","sequence",1));check(ws.close==1008&&service.ui.executions==0);FakeDisk.reset();FakeDisk.config=config;trust=new TrustedGrant(new android.content.Context());String consent=trust.beginApproval(config,A,"single AI");trust.completeApproval(config,consent);service.multiMode=false;service.raw(RelayApi.json("id",ID,"action","file_roots","sequence",1));check(service.ui.executions==1);});
  test("holder identity gates commands and each grant keeps independent replay sequence",()->{addB();service.receive(fence(1,A));service.receive(command(B,1L,1));check(!ws.last().getBoolean("ok")&&service.ui.executions==0);service.receive(command(A,1L,1));check(service.ui.executions==1);service.ui.reply.success(new JSONObject());service.receive(fence(2,B));service.receive(command(B,2L,1));check(service.ui.executions==2&&B.equals(service.ui.owner));check(trust.grant(config,A).getLong("sequence")==1&&trust.grant(config,B).getLong("sequence")==1);});
  test("handoff invalidates old command and share gates before waiting for real gesture drain",()->{addB();service.receive(fence(1,A));service.receive(command(A,1L,1));var oldCommand=service.ui.commandGate;var oldShare=service.ui.shareGate;var oldReply=service.ui.reply;service.ui.quiet=false;JSONObject next=fence(2,B);service.receive(next);check(!oldCommand.getAsBoolean()&&!oldShare.getAsBoolean()&&!ws.last().getBoolean("ok")&&"command_in_progress".equals(ws.last().getString("error")));int sent=ws.sent.size();oldReply.success(new JSONObject());check(sent==ws.sent.size());service.ui.quiet=true;service.receive(next);check(ws.last().getBoolean("ok"));});
  test("provider still running after response timeout blocks ACK until actual completion",()->{addB();service.receive(fence(1,A));FileScopes.quiet=false;JSONObject next=fence(2,B);service.receive(next);check("command_in_progress".equals(ws.last().getString("error")));service.receive(next);check(!ws.last().getBoolean("ok"));FileScopes.quiet=true;service.receive(next);check(ws.last().getBoolean("ok"));});
  test("storage failure sends negative ACK then retires transport for fresh hello",()->{JSONObject next=fence(1,A);FakeDisk.failSaveAt=FakeDisk.saves+1;service.receive(next);check("storage_failure".equals(ws.last().getString("error"))&&ws.close==1011&&service.disconnects==1);FakeDisk.failSaveAt=0;service.reconnect(ws);hello();service.receive(next);check("stale_fence".equals(ws.last().getString("error")));service.receive(fence(2,A));check(ws.last().getBoolean("ok"));});
  test("unavailable grant negative ACK also retires transport without deleting other consent",()->{service.receive(fence(1,B));check("grant_unavailable".equals(ws.last().getString("error"))&&ws.close==1011);check(trust.grant(config,A)!=null);});
  test("disconnect and reconnect cannot revive old command snapshot or share authority",()->{service.receive(fence(1,A));service.receive(command(A,1L,1));var share=service.ui.shareGate;service.disconnect();check(!share.getAsBoolean());service.reconnect(ws);hello();service.receive(command(A,1L,2));check(service.ui.executions==1&&!ws.last().getBoolean("ok"));});
  test("lease expiry denies command and share before any new fence",()->{service.receive(fence(1,A));service.receive(command(A,1L,1));var share=service.ui.shareGate;service.ui.reply.success(new JSONObject());SystemClock.now+=120000;check(!share.getAsBoolean());service.receive(command(A,1L,2));check(service.ui.executions==1&&!ws.last().getBoolean("ok"));});
  test("grant removal immediately invalidates captured file identity without PreviewState claim switching",()->{service.receive(fence(1,A));service.receive(command(A,1L,1));var gate=service.ui.shareGate;PreviewState.claim=B;check(gate.getAsBoolean());trust.revoke(config,A);check(!gate.getAsBoolean());});
  test("stale remote list cannot remove a newly approved grant absent from request snapshot",()->{var snapshot=service.snapshot();addB();service.reconcile(Set.of(A),snapshot);check(trust.grant(config,B)!=null&&FileScopes.retained.equals(Set.of(A,B)));service.reconcile(Set.of(A),service.snapshot());check(trust.grant(config,B)==null);});
  test("remote listing cannot create owner consent",()->{service.reconcile(Set.of(A,B),service.snapshot());check(trust.grant(config,B)==null);});
  test("unknown envelope fields and mixed null lease identities fail closed",()->{service.receive(command(A,null,1).put("extra",true));check(ws.close==1008||service.ui.executions==0);reset();JSONObject cmd=command(A,null,1).put("lease_epoch",1);service.receive(cmd);check(service.ui.executions==0);});
  test("full local stop fences captured command even if old socket remains",()->{service.receive(fence(1,A));service.receive(command(A,1L,1));var gate=service.ui.commandGate;trust.stop();check(!gate.getAsBoolean());service.receive(command(A,1L,2));check(service.ui.executions==1);});
  test("durable revoke intent fences current authority even if encrypted grant removal fails",()->{service.receive(fence(1,A));service.receive(command(A,1L,1));var gate=service.ui.commandGate;OwnerRevocations.revoked=Set.of(A);check(!gate.getAsBoolean()&&trust.grant(config,A)!=null);service.receive(fence(2,A));check("grant_unavailable".equals(ws.last().getString("error")));});
  test("remote grant list validates invitation identity and candidate shape without creating consent",()->{
    JSONObject list=RelayApi.json("protocol_version",4,"mode","multi","generation",0,"max_grants",4,"invitation_sequence",1,"grants",new JSONArray(),"invitation",JSONObject.NULL);
    check(MultiAiProtocol.claims(list).isEmpty());
    list.put("invitation",RelayApi.json("invitation_id",L,"invitation_sequence",1,"state","pending","pairing_expires_at",1000,"candidate",RelayApi.json("claim_id",B,"name","synthetic AI","verification_code","123456")));
    check(MultiAiProtocol.claims(list).isEmpty());
    list.getJSONObject("invitation").put("candidate",null);boolean refused=false;try{MultiAiProtocol.claims(list);}catch(IllegalArgumentException e){refused=true;}check(refused);
  });
  test("first multi approval retries after network failure without a visible owner Activity or other grant",()->{
    trust.stop();JSONObject invite=trust.beginInvitation(config,0);trust.completeInvitation(config,invite.getString("invitation_id"),invite.getLong("invitation_sequence"),System.currentTimeMillis()/1000+300,"1".repeat(64),"2".repeat(64));trust.beginApproval(config,B,"synthetic B");service.authorized=false;
    service.resumePending();check(RelayApi.requests.size()==1&&service.approvalInFlight);
    RelayApi.requests.get(0).respond(null,0,"network");check(!service.approvalInFlight&&service.retries.size()==1);
    service.retries.remove(0).run();check(RelayApi.requests.size()==2);
    RelayApi.requests.get(1).respond(RelayApi.json("protocol_version",4,"ok",true,"claim_id",B,"session_expires_at",JSONObject.NULL,"allow_tap",false,"allow_control",true,"allowed_packages",new JSONArray(),"persistent",true,"all_apps",true),200,null);
    check(trust.grant(config,B)!=null&&service.grantChanges==1);
  });
  test("owner identity rebinding cancels old poll and clears busy before a new device can enable multi",()->{
    OwnerIdentityHarness owner=new OwnerIdentityHarness();owner.bindIdentity(config);int oldRun=owner.generation;boolean[] cancelled={false};owner.call=()->cancelled[0]=true;owner.busy=true;owner.remote=new JSONObject();owner.nextPoll=999999;
    EnrollmentStore.Config next=new EnrollmentStore.Config("https://other.invalid/d/"+B,"f".repeat(64));owner.bindIdentity(next);
    check(cancelled[0]&&!owner.busy&&owner.call==null&&owner.remote==null&&owner.nextPoll==0&&!owner.accepts(oldRun,config));
    int newRun=owner.generation;owner.busy=true;owner.bindIdentity(next);check(owner.generation==newRun&&owner.busy&&owner.accepts(newRun,next)&&!owner.accepts(oldRun,config));
  });
  test("offline cold owner full-stop persists unknown-generation intent and stops local grants before any GET",()->{
    PreviewService.instance=null;OwnerIdentityHarness owner=new OwnerIdentityHarness();owner.bindIdentity(config);owner.stopAll();check(trust.grant(config,A)!=null&&RelayApi.requests.isEmpty());
    android.app.AlertDialog.positive.click(null,0);check(trust.grants(config).isEmpty()&&OwnerRevocations.all&&OwnerRevocations.unknownQueued==1&&owner.flushes==1&&RelayApi.requests.isEmpty());
    owner.stopAll();check(OwnerRevocations.unknownQueued==1&&owner.flushes==2);
  });
  test("remote-stop metadata write failure still attempts the independent durable local stop",()->{
    PreviewService.instance=null;OwnerIdentityHarness owner=new OwnerIdentityHarness();owner.bindIdentity(config);OwnerRevocations.failQueue=true;owner.stopAll();android.app.AlertDialog.positive.click(null,0);
    check(trust.grants(config).isEmpty()&&!OwnerRevocations.all&&owner.flushes==0&&owner.message.contains("未能保存服务端解除请求"));
  });
  test("migration preflight is read-only and confirmation preserves commands admitted while checking",()->{
    OwnerIdentityHarness owner=singleOwner();int saves=FakeDisk.saves;owner.enable(config);check(owner.busy&&RelayApi.requests.size()==1&&owner.lastEnableStage.equals("checking_service"));var request=RelayApi.requests.get(0);
    check(request.url.equals(config.origin())&&request.path.equals("/v1/service")&&request.method.equals("GET")&&request.token==null&&request.body==null);unchanged(owner);check(FakeDisk.saves==saves);
    owner.enable(config);check(RelayApi.requests.size()==1&&owner.lastEnableStage.equals("guard_blocked"));request.respond(discovery(),200,null);unchanged(owner);check(!owner.busy&&FakeDisk.saves==saves&&owner.lastEnableStage.equals("awaiting_owner_confirmation"));
    trust.consume(config,8);var confirm=android.app.AlertDialog.positive;check(confirm!=null);confirm.click(null,0);check(owner.lastEnableStage.equals("migration_requested"));
    check(!trust.multi(config)&&trust.migration(config)!=null&&trust.grant(config,A).getLong("sequence")==8&&owner.polls==0&&service.ownerModeChanges==0&&service.restores==1&&FakeDisk.config.token.equals(config.token));confirm.click(null,0);check(owner.polls==0&&service.ownerModeChanges==0&&service.restores==1);
  });
  test("old service network failure and malformed capabilities preserve original single state",()->{
    List<JSONObject> invalid=new ArrayList<>();invalid.add(null);invalid.add(discovery().put("capabilities",new JSONArray().put("companion_v1")));invalid.add(discovery().put("capabilities",new JSONArray().put("multi_ai_v1")));invalid.add(discovery().put("capabilities",new JSONArray().put("task_lease_v1")));invalid.add(discovery().put("capabilities",new JSONArray().put("multi_ai_v1").put(1).put("task_lease_v1")));invalid.add(discovery().put("capabilities","multi_ai_v1 task_lease_v1"));invalid.add(discovery().put("protocol_version",3));invalid.add(discovery().put("capabilities",null));invalid.add(discovery().put("enrollment_expires_at",null));invalid.add(discovery().put("capabilities",new JSONArray().put("multi_ai_v1").put("task_lease_v1").put("multi_ai_v1")));
    for(JSONObject response:invalid){OwnerIdentityHarness owner=singleOwner();int saves=FakeDisk.saves;owner.enable(config);RelayApi.requests.get(0).respond(response,response==null?404:200,response==null?"not_found":null);unchanged(owner);check(!owner.busy&&FakeDisk.saves==saves&&android.app.AlertDialog.positive==null);}
    for(int status:new int[]{0,503}){OwnerIdentityHarness owner=singleOwner();int saves=FakeDisk.saves;owner.enable(config);RelayApi.requests.get(0).respond(null,status,"network");unchanged(owner);check(FakeDisk.saves==saves&&!owner.busy);}
  });
  test("canceling migration preflight invalidates a late supported-service reply",()->{
    OwnerIdentityHarness owner=singleOwner();owner.enable(config);var request=RelayApi.requests.get(0);android.app.AlertDialog.negative.click(null,0);request.respond(discovery(),200,null);unchanged(owner);check(request.cancelled&&!owner.busy&&owner.lastEnableStage.equals("canceled"));
  });
  test("Back cancellation also invalidates migration preflight",()->{
    OwnerIdentityHarness owner=singleOwner();owner.enable(config);var request=RelayApi.requests.get(0);android.app.AlertDialog.cancelled.cancel(null);request.respond(discovery(),200,null);unchanged(owner);check(request.cancelled&&!owner.busy);
  });
  test("paused owner cannot migrate from a late discovery reply",()->{
    OwnerIdentityHarness owner=singleOwner();owner.enable(config);var request=RelayApi.requests.get(0);owner.pause();request.respond(discovery(),200,null);unchanged(owner);check(request.cancelled&&!owner.busy);
  });
  test("identity replacement rejects old migration discovery",()->{
    OwnerIdentityHarness owner=singleOwner();owner.enable(config);var request=RelayApi.requests.get(0);EnrollmentStore.Config next=new EnrollmentStore.Config("https://other.invalid/d/"+B,"f".repeat(64));owner.bindIdentity(next);request.respond(discovery(),200,null);unchanged(owner);check(request.cancelled&&owner.identity==next);
  });
  test("persisted identity change is checked even before owner binding catches up",()->{
    OwnerIdentityHarness owner=singleOwner();owner.enable(config);FakeDisk.config=new EnrollmentStore.Config(config.url,"f".repeat(64));RelayApi.requests.get(0).respond(discovery(),200,null);unchanged(owner);check(!owner.busy);
  });
  test("canceling or pausing the final confirmation prevents its old positive callback",()->{
    for(boolean pause:new boolean[]{false,true}){OwnerIdentityHarness owner=singleOwner();owner.enable(config);RelayApi.requests.get(0).respond(discovery(),200,null);var confirm=android.app.AlertDialog.positive;if(pause)owner.pause();else android.app.AlertDialog.negative.click(null,0);confirm.click(null,0);unchanged(owner);}
  });
  test("changed grant or saved identity prevents an earlier migration confirmation",()->{
    OwnerIdentityHarness owner=singleOwner();owner.enable(config);RelayApi.requests.get(0).respond(discovery(),200,null);var confirm=android.app.AlertDialog.positive;trust.stop();String consent=trust.beginApproval(config,B,"replacement");trust.completeApproval(config,consent);confirm.click(null,0);check(!trust.multi(config)&&trust.grant(config,B)!=null&&service.ownerModeChanges==0&&owner.polls==0);
    owner=singleOwner();owner.enable(config);RelayApi.requests.get(0).respond(discovery(),200,null);confirm=android.app.AlertDialog.positive;FakeDisk.config=new EnrollmentStore.Config(config.url,"f".repeat(64));confirm.click(null,0);unchanged(owner);
  });
  test("synchronous discovery request failure preserves the single connection",()->{
    OwnerIdentityHarness owner=singleOwner();int saves=FakeDisk.saves;RelayApi.throwNext=true;owner.enable(config);unchanged(owner);check(!owner.busy&&RelayApi.requests.isEmpty()&&FakeDisk.saves==saves);
  });
  test("old preflight cannot clear a newer request or migrate after cancellation",()->{
    OwnerIdentityHarness owner=singleOwner();owner.enable(config);var old=RelayApi.requests.get(0);android.app.AlertDialog.negative.click(null,0);owner.enable(config);var next=RelayApi.requests.get(1);int run=owner.generation;
    old.respond(discovery(),200,null);unchanged(owner);check(owner.busy&&owner.generation==run&&owner.call==next);next.respond(discovery(),200,null);android.app.AlertDialog.positive.click(null,0);check(!trust.multi(config)&&trust.migration(config)!=null&&owner.polls==0&&service.restores==1);
  });
  test("same claim reapproval stop or unreadable trust invalidates old confirmation",()->{
    for(int action=0;action<3;action++){OwnerIdentityHarness owner=singleOwner();owner.enable(config);RelayApi.requests.get(0).respond(discovery(),200,null);var confirm=android.app.AlertDialog.positive;
      if(action==2)FakeDisk.failReadRecord=true;else{trust.stop();if(action==0){String consent=trust.beginApproval(config,A,"new consent same claim");trust.completeApproval(config,consent);}}
      int saves=FakeDisk.saves;confirm.click(null,0);FakeDisk.failReadRecord=false;check(!trust.multi(config)&&service.ownerModeChanges==0&&owner.polls==0&&FakeDisk.saves==saves);
    }
  });
  test("saved enrollment removal or deregistration blocks late preflight and confirmation",()->{
    for(boolean confirmPhase:new boolean[]{false,true})for(boolean removed:new boolean[]{false,true}){OwnerIdentityHarness owner=singleOwner();owner.enable(config);var request=RelayApi.requests.get(0);android.app.AlertDialog.Click confirm=null;if(confirmPhase){request.respond(discovery(),200,null);confirm=android.app.AlertDialog.positive;}
      FakeDisk.config=removed?null:new EnrollmentStore.Config(config.url,config.token,false);int saves=FakeDisk.saves;if(confirmPhase)confirm.click(null,0);else request.respond(discovery(),200,null);unchanged(owner);check(FakeDisk.saves==saves);
    }
  });
  test("stopping an unknown migration persists only the original claim revoke and stops locally",()->{
    OwnerIdentityHarness owner=singleOwner();String id=trust.beginMigration(config,trust.sessionFence(config));trust.dispatchMigration(config,id);PreviewService.instance=null;
    check(owner.stopPendingMigration(config)&&trust.read(config)==null&&trust.migration(config)==null&&OwnerRevocations.revoked.equals(Set.of(A))&&owner.flushes==1);
  });
  test("stopped migration retry after owner recreation retains the original claim target",()->{
    OwnerIdentityHarness owner=singleOwner();trust.beginMigration(config,trust.sessionFence(config));PreviewService.instance=null;
    check(owner.stopPendingMigration(config)&&trust.read(config)==null);OwnerIdentityHarness reopened=new OwnerIdentityHarness();
    reopened.retryRevocations(config);check(OwnerRevocations.revoked.equals(Set.of(A))&&reopened.flushes==1&&trust.read(config)==null);
  });
  test("old migration revoke retry does not stop or target a later approved single grant",()->{
    OwnerIdentityHarness owner=singleOwner();trust.beginMigration(config,trust.sessionFence(config));PreviewService.instance=null;check(owner.stopPendingMigration(config));
    String consent=trust.beginApproval(config,B,"later AI");trust.completeApproval(config,consent);trust.consume(config,9);String fence=trust.sessionFence(config);
    OwnerIdentityHarness reopened=new OwnerIdentityHarness();reopened.retryRevocations(config);check(reopened.flushes==1&&OwnerRevocations.revoked.equals(Set.of(A))&&trust.isCurrent(config,fence)&&trust.grant(config,B).getLong("sequence")==9);
    check(reopened.stopPendingMigration(config)&&OwnerRevocations.revoked.equals(Set.of(A,B))&&trust.read(config)==null&&reopened.flushes==2);
  });
  test("new stop with an old revoke queue also cancels a later pending approval",()->{
    OwnerIdentityHarness owner=singleOwner();trust.beginMigration(config,trust.sessionFence(config));PreviewService.instance=null;check(owner.stopPendingMigration(config));
    trust.beginApproval(config,B,"later pending AI");check(owner.stopPendingMigration(config)&&trust.pending(config)==null&&trust.read(config)==null&&OwnerRevocations.revoked.equals(Set.of(A,B)));
  });
  test("migration revoke metadata failure still stops local authority",()->{
    OwnerIdentityHarness owner=singleOwner();trust.beginMigration(config,trust.sessionFence(config));PreviewService.instance=null;OwnerRevocations.failQueue=true;
    check(owner.stopPendingMigration(config)&&trust.read(config)==null&&owner.flushes==0&&owner.message.contains("未保存"));
    OwnerRevocations.failQueue=false;String consent=trust.beginApproval(config,B,"later AI");trust.completeApproval(config,consent);String fence=trust.sessionFence(config);
    OwnerIdentityHarness reopened=new OwnerIdentityHarness();reopened.retryRevocations(config);
    check(reopened.flushes==0&&OwnerRevocations.revoked.isEmpty()&&trust.isCurrent(config,fence)&&reopened.message.contains("没有可核实"));
  });
  test("single first stop retains its original claim for safe targeted retry only",()->{
    OwnerIdentityHarness owner=singleOwner();check(A.equals(owner.rememberSingleStop(config)));trust.stop();String consent=trust.beginApproval(config,B,"later AI");trust.completeApproval(config,consent);
    String fence=trust.sessionFence(config);owner.retryRevocations(config);check(OwnerRevocations.revoked.equals(Set.of(A))&&trust.isCurrent(config,fence));owner.confirmSingleStop(config,A);check(OwnerRevocations.revoked.isEmpty()&&trust.isCurrent(config,fence));
  });
  test("unknown migration STOP discovers empty mode and requires clearing remote old grants before new invitation",()->{
    OwnerIdentityHarness owner=singleOwner();String id=trust.beginMigration(config,trust.sessionFence(config));trust.dispatchMigration(config,id);trust.stop();PreviewService.instance=null;owner.emptyCheck();
    check(RelayApi.requests.size()==1&&RelayApi.requests.get(0).method.equals("GET"));RelayApi.requests.get(0).respond(multiState(A),200,null);
    check(trust.multi(config)&&trust.grants(config).isEmpty()&&owner.cleanup());owner.stopAll();android.app.AlertDialog.positive.click(null,0);check(OwnerRevocations.queuedGeneration==5&&trust.grants(config).isEmpty());
    owner.remote=multiState(null);check(!owner.cleanup());
  });
  test("late empty-mode read cannot replace a later consent or change after UI pause",()->{
    for(boolean pause:new boolean[]{true,false}){OwnerIdentityHarness owner=singleOwner();trust.stop();owner.emptyCheck();var req=RelayApi.requests.get(0);if(pause)owner.pause();else{String c=trust.beginApproval(config,B,"later AI");trust.completeApproval(config,c);}req.respond(multiState(A),200,null);check(!trust.multi(config));if(!pause)check(trust.grant(config,B)!=null);}
  });
  test("invalid or unavailable remote mode read leaves stopped single state intact",()->{
    for(int response=0;response<3;response++){OwnerIdentityHarness owner=singleOwner();trust.stop();owner.emptyCheck();var req=RelayApi.requests.get(0);if(response==0)req.respond(null,0,"offline");else if(response==1)req.respond(multiState(A).put("invitation",null),200,null);else req.respond(RelayApi.json("protocol_version",4,"state","revoked"),200,null);check(!trust.multi(config)&&trust.read(config)==null);}
  });
  test("one explicit add reads state then creates one durable invitation without a second click",()->{
    OwnerIdentityHarness owner=invitationOwner();owner.add();check(owner.busy&&RelayApi.requests.size()==1&&trust.invitation(config)==null);
    var read=RelayApi.requests.get(0);check(read.method.equals("GET")&&read.path.equals("/v1/device/grants"));trust.consume(config,A,8);read.respond(multiState(A),200,null);
    check(invitationPosts()==1&&owner.busy);var post=RelayApi.requests.get(1);JSONObject invite=trust.invitation(config);
    check(post.body.getString("invitation_id").equals(invite.getString("invitation_id"))&&post.body.getString("ticket").equals(invite.getString("ticket"))&&trust.grant(config,A).getLong("sequence")==8);
    read.respond(multiState(A),200,null);check(invitationPosts()==1&&owner.busy&&owner.call==post);
    post.respond(invitationReply(post.body),200,null);check("ready".equals(trust.invitation(config).getString("phase"))&&invitationPosts()==1);
    read.respond(multiState(A),200,null);check(invitationPosts()==1);
  });
  test("failed malformed and synchronous state reads release busy and require an explicit retry",()->{
    for(int mode=0;mode<4;mode++){reset();OwnerIdentityHarness owner=invitationOwner();if(mode==3)RelayApi.throwNext=true;owner.add();
      if(mode<3)RelayApi.requests.get(0).respond(mode==1?multiState(A).put("protocol_version",3):mode==2?multiState(A):null,mode==2?503:mode==0?0:200,mode==0?"offline":null);
      check(!owner.busy&&trust.invitation(config)==null&&invitationPosts()==0);owner.add();RelayApi.requests.get(RelayApi.requests.size()-1).respond(multiState(A),200,null);check(invitationPosts()==1);
    }
  });
  test("a background state refresh never creates an invitation",()->{
    OwnerIdentityHarness owner=invitationOwner();owner.backgroundPoll();RelayApi.requests.get(0).respond(multiState(A),200,null);check(invitationPosts()==0&&trust.invitation(config)==null&&!owner.busy);
  });
  test("pause and identity change invalidate late add reads without blocking a later request",()->{
    for(boolean pause:new boolean[]{true,false}){reset();OwnerIdentityHarness owner=invitationOwner();owner.add();var old=RelayApi.requests.get(0);
      if(pause){owner.pause();owner.visible=true;}else owner.bindIdentity(new EnrollmentStore.Config("https://other.invalid/d/"+B,"f".repeat(64)));
      check(old.cancelled&&!owner.busy);old.respond(multiState(A),200,null);check(invitationPosts()==0&&trust.invitation(config)==null);
      owner.bindIdentity(config);owner.add();var next=RelayApi.requests.get(1);old.respond(multiState(A),200,null);check(owner.busy&&owner.call==next);next.respond(multiState(A),200,null);check(invitationPosts()==1);
    }
  });
  test("STOP dialog cancels the add read and returning permits a new explicit add",()->{
    OwnerIdentityHarness owner=invitationOwner();owner.add();var old=RelayApi.requests.get(0);owner.stopAll();check(old.cancelled&&!owner.busy);old.respond(multiState(A),200,null);
    check(invitationPosts()==0&&trust.grant(config,A)!=null&&trust.invitation(config)==null);owner.add();RelayApi.requests.get(1).respond(multiState(A),200,null);check(invitationPosts()==1);
  });
  test("confirmed STOP and a failed remote-stop write cannot revive an invitation from old reads",()->{
    for(boolean failedQueue:new boolean[]{true,false}){reset();OwnerIdentityHarness owner=invitationOwner();owner.add();var old=RelayApi.requests.get(0);PreviewService.instance=null;OwnerRevocations.failQueue=failedQueue;owner.stopAll();android.app.AlertDialog.positive.click(null,0);old.respond(multiState(A),200,null);
      check(invitationPosts()==0&&trust.grants(config).isEmpty()&&trust.invitation(config)==null&&!owner.busy);
    }
  });
  test("revocation dialog and cancel action abandon old add reads but do not stick busy",()->{
    for(boolean revoke:new boolean[]{true,false}){reset();OwnerIdentityHarness owner=invitationOwner();owner.add();var old=RelayApi.requests.get(0);if(revoke)owner.remove(A);else owner.cancelAdd();
      check(old.cancelled&&!owner.busy);old.respond(multiState(A),200,null);check(invitationPosts()==0&&trust.invitation(config)==null&&trust.grant(config,A)!=null);
      owner.add();RelayApi.requests.get(1).respond(multiState(A),200,null);check(invitationPosts()==1);
    }
  });
  test("notification STOP independent grant revocation and persisted identity change invalidate add context",()->{
    for(int action=0;action<3;action++){reset();OwnerIdentityHarness owner=invitationOwner();owner.add();var read=RelayApi.requests.get(0);
      if(action==0)trust.stop();else if(action==1)trust.revoke(config,A);else FakeDisk.config=new EnrollmentStore.Config(config.url,"f".repeat(64));
      read.respond(multiState(A),200,null);check(invitationPosts()==0&&trust.invitation(config)==null&&!owner.busy);
    }
  });
  test("independently canceled original invitation is not replaced by a delayed add read",()->{
    OwnerIdentityHarness owner=invitationOwner();JSONObject invite=trust.beginInvitation(config,0);owner.add();var read=RelayApi.requests.get(0);
    trust.cancelInvitation(config,invite.getString("invitation_id"),invite.getLong("invitation_sequence"));trust.finishInvitationCancellation(config,invite.getString("invitation_id"),invite.getLong("invitation_sequence"));
    read.respond(multiState(A),200,null);check(invitationPosts()==0&&trust.invitation(config)==null&&!owner.busy);
  });
  test("unknown invitation POST retains the same ID sequence and ticket for explicit retry",()->{
    OwnerIdentityHarness owner=invitationOwner();owner.add();RelayApi.requests.get(0).respond(multiState(A),200,null);var first=RelayApi.requests.get(1);first.respond(null,0,"lost_response");
    check(!owner.busy&&trust.invitation(config)!=null);owner.add();var retry=RelayApi.requests.get(2);check(invitationPosts()==2);
    for(String key:List.of("invitation_id","invitation_sequence","ticket"))check(first.body.get(key).equals(retry.body.get(key)));
  });
  test("empty multi can continue an explicit add after read while remote orphan grants still block it",()->{
    for(boolean orphan:new boolean[]{false,true}){reset();trust.stop();OwnerIdentityHarness owner=new OwnerIdentityHarness();owner.bindIdentity(config);owner.add();RelayApi.requests.get(0).respond(multiState(orphan?A:null),200,null);
      check(invitationPosts()==(orphan?0:1));if(orphan)check(trust.invitation(config)==null);
    }
  });
  test("a later STOP invalidates an add read even when both grant snapshots are empty",()->{
    trust.stop();OwnerIdentityHarness owner=new OwnerIdentityHarness();owner.bindIdentity(config);owner.add();var read=RelayApi.requests.get(0);trust.stop();read.respond(multiState(null),200,null);
    check(invitationPosts()==0&&trust.invitation(config)==null&&!owner.busy);
  });
  System.out.println("TOTAL "+passed);
 }
}
