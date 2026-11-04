#!/usr/bin/env python3
"""Execute production Activity/service recovery methods with deterministic owner-specific timers."""
import argparse, datetime, hashlib, importlib.util, json, os, subprocess, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
PRODUCTION=ROOT/'app/src/main/java/io/airtap/preview'
FAKES=ROOT/'tests/approvalrecovery/platform_fakes.py'
TEST=ROOT/'tests/approvalrecovery/ApprovalSchedulingTest.java'
p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);args=p.parse_args()
spec=importlib.util.spec_from_file_location('approval_fakes',FAKES)
fakes=importlib.util.module_from_spec(spec);spec.loader.exec_module(fakes)
jdk=Path(os.environ.get('JAVA_HOME','/Applications/Android Studio.app/Contents/jbr/Contents/Home'))/'bin'
extracted={}
def method(filename,signature):
    source=(PRODUCTION/filename).read_text()
    if source.count(signature)!=1: raise SystemExit('Expected one method: '+signature)
    start=source.index(signature)
    if '\n' not in source[start:source.find('}',start)]:
        end=source.index('\n',start)
    else:
        end=source.index('\n    }',start)+len('\n    }')
    result=source[start:end];extracted[filename+':'+signature.strip()]=result
    return result
activity='''package io.airtap.preview;
import org.json.JSONObject;
final class MainActivityHarness extends android.app.Activity {
 final SingleOwner multiOwner=new SingleOwner();
 static final class SingleOwner {boolean enabled(EnrollmentStore.Config config){return false;}}
 final ConnectionFeedback connectionFeedback=new ConnectionFeedback();final android.os.Handler main=new android.os.Handler();
 EnrollmentStore.Config config;Object invitationArtifacts;boolean busy,storageError;int epoch,changes;String claim,ticket,code,candidate,message="";long invitationExpiry;
 MainActivityHarness(EnrollmentStore.Config c,String claim){config=c;this.claim=claim;candidate="synthetic owner-confirmed AI";}
 void pressApprove(){approve();}void closeInvitation(){}void change(String text){if(isDestroyed())throw new AssertionError("UI touched after destruction");message=text;changes++;}
'''+method('MainActivity.java','    private void approve() {')+'\n'+method('MainActivity.java','    private JSONObject pendingApproval() {')+'\n'+method('MainActivity.java','    @Override public void onDestroy() {')+'\n}\n'
service='''package io.airtap.preview;
import org.json.JSONObject;import android.net.ConnectivityManager;import android.os.SystemClock;import okhttp3.WebSocket;
final class PreviewService extends android.content.Context {
 static PreviewService instance;final android.os.Handler main=new android.os.Handler();
 boolean authorized,restoring,connected,notificationBlocked,multiMode,multiHello,grantRefreshInFlight;long activeCommand,nextOwnerRetry;int generation,retry,connects,connections,networkStarts,notificationFailures,multiRestores,taskDisconnects;String state,url,token,sessionFence,restoringFence;
 RecoveryStatus recoveryStatus=RecoveryStatus.WAITING_FOR_AI;java.util.concurrent.atomic.AtomicBoolean transportOpen=new java.util.concurrent.atomic.AtomicBoolean(true);
 Object networkCallback,currentNetwork,currentNetworkPath;Runnable reconnectTask,handshakeTask;WebSocket socket;EnrollmentStore.Config config;
 static final class Ui {int clears,cancellations;ScreenPreparation.State screen=ScreenPreparation.State.READY;ScreenPreparation.State screenState(){return screen;}void clear(){clears++;}void cancelGestures(){cancellations++;}}final Ui ui=new Ui();
 static final class Recovery {int cancellations;void cancel(){cancellations++;}}final Recovery recovery=new Recovery();
 boolean realMultiRecovery;long nextGrantRefresh;java.util.Set<String> remoteGrants=java.util.Set.of();
 static final class Lease {String claim(){return null;}void disconnect(){}}final Lease tasks=new Lease();
 void resumeMultiApproval()throws Exception{if(new TrustedGrant(this).pending(config)!=null)throw new AssertionError("HTTP recovery cases exclude pending approvals");}
 PreviewService(){instance=this;}
 // Migration cases retain a dispatch counter; focused recovery cases execute the real HTTP methods.
 void restoreMulti()throws Exception{if(realMultiRecovery){restoreMultiActual();return;}config=new EnrollmentStore(this).read();if(!new TrustedGrant(this).multi(config))throw new AssertionError("multi restore before durable commit");multiMode=true;multiRestores++;}
 // An explicit test effect for the later verified multi restore, then real notification gating runs.
 void finishMultiRestore()throws Exception{TrustedGrant trust=new TrustedGrant(this);if(!multiMode||!trust.multi(config)||trust.read(config)==null)throw new AssertionError();restoring=false;notificationBlocked=false;sessionFence=trust.sessionFence(config);restoringFence=null;authorized=true;url=RelayAddress.webSocket(config.url);token=config.token;SessionService.begin(this);}
 void disconnectTask(){taskDisconnects++;}
 boolean executable(){return executionAllowed();}
 void observeNetwork(int run){if(run!=generation)throw new AssertionError();networkStarts++;}
 void connect(int run){if(run!=generation||!authorized)throw new AssertionError();connects++;}
'''+ method('PreviewService.java','    private void restoreMulti() throws Exception {').replace('private void restoreMulti()', 'private void restoreMultiActual()')+'\n'+ '\n'.join(method('PreviewService.java',s) for s in ['    private java.util.Map<String,String> grantSnapshot() throws Exception {','    private void retainRemote(java.util.Set<String> remote,java.util.Map<String,String> snapshot) throws Exception {','    private void refreshMultiGrants() {','    void ownerModeChanged() {','    private boolean resumeMigration(EnrollmentStore.Config saved) throws Exception {','    void restore() {','    private void stopRestoring(String expectedFence, String message) {','    void stoppedLocally(String expectedFence) {','    void sessionCarrierUnavailable() {','    void storageUnavailable() {','    private void retryApproval(int run) {','    private void scheduleRestore(int run, long delay) {','    private void waitForNotifications() {','    void notificationUnavailable(int run, boolean startFailed) {','    void notificationReady(int run) {','    private boolean executionAllowed() {','    RecoveryStatus recoveryStatus() {','    void stop(String message) {','    private void suspend(String message) {'])+'\n}\n'
sources=[PRODUCTION/(name+'.java') for name in ['TrustedGrant','ApprovalRecovery','MigrationRecovery','OwnerRevocations','MultiAiProtocol','TaskLease','ConnectionFeedback','ServiceProtocol','RelayFailure','RecoveryStatus','ScreenPreparation']]
with tempfile.TemporaryDirectory(prefix='airtap-approval-scheduling-') as directory:
    temp=Path(directory);generated=[]
    fixtures=dict(fakes.SOURCES);fixtures.update(fakes.SCHEDULING_SOURCES)
    fixtures['org/json/JSONArray.java']=fixtures['org/json/JSONArray.java'].replace('public JSONObject getJSONObject(int i)','public Object get(int i){return v.get(i);}public JSONObject getJSONObject(int i)')
    fixtures['org/json/JSONObject.java']=fixtures['org/json/JSONObject.java'].replace('public JSONObject put(', 'public int length(){return fields.size();}public JSONObject put(')
    fixtures['io/airtap/preview/RelayApi.java']=fixtures['io/airtap/preview/RelayApi.java'].replace('"/v1/device/approve".equals(path)||','"/v1/device/multi/enable".equals(path)||"/v1/device/revoke".equals(path)||"/v1/device/grants/revoke".equals(path)||"/v1/device/approve".equals(path)||').replace('"/v1/device/pairing".equals(path)','("/v1/device/pairing".equals(path)||"/v1/device/grants".equals(path))')
    fixtures['io/airtap/preview/MainActivityHarness.java']=activity
    fixtures['io/airtap/preview/PreviewService.java']=service
    fixtures['io/airtap/preview/TlsPolicy.java']='package io.airtap.preview; final class TlsPolicy { static final String USER_MESSAGE="synthetic security error"; }'
    fixtures['io/airtap/preview/FileScopes.java']='package io.airtap.preview; final class FileScopes {static void retain(android.content.Context c,java.util.Set<String> claims){}}'
    for name,value in fixtures.items():
        path=temp/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(value);generated.append(path)
    subprocess.run([str(jdk/'javac'),'--release','17','-d',str(temp),*map(str,sources),str(TEST),*map(str,generated)],check=True,timeout=30)
    run=subprocess.run([str(jdk/'java'),'-cp',str(temp),'io.airtap.preview.ApprovalSchedulingTest'],capture_output=True,text=True,timeout=30)
    print(run.stdout,end='')
    if run.returncode:print(run.stderr,end='');raise SystemExit(run.returncode)
    tests=[line[5:] for line in run.stdout.splitlines() if line.startswith('PASS ')]
    assert run.stdout.splitlines()[-1]=='TOTAL '+str(len(tests))
inputs=[*sources,PRODUCTION/'MainActivity.java',PRODUCTION/'PreviewService.java',TEST,FAKES,Path(__file__)]
record={'status':'passed','recorded_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'passed':len(tests),'tests':tests,
        'extracted_method_sha256':{k:hashlib.sha256(v.encode()).hexdigest() for k,v in extracted.items()},
        'source_sha256':{str(path.relative_to(ROOT)):hashlib.sha256(path.read_bytes()).hexdigest() for path in inputs},
        'scope':'Production approve/pendingApproval/onDestroy and resumeMigration/ownerModeChanged/restore, notification gating/recovery, typed status and stop/suspend methods are extracted verbatim and executed with complete production TrustedGrant, ApprovalRecovery, MigrationRecovery, OwnerRevocations, MultiAiProtocol, TaskLease, ConnectionFeedback and ServiceProtocol.',
        'limits':['Deterministic synthetic Android Activity, per-owner Handler, persistence and HTTP. No Activity refresh is injected after destruction. The service connect() endpoint is a counter: these tests prove dispatch into connection startup, not actual Android binding, sockets, notifications or OEM background execution.',
                  'Migration cases use a checked multi dispatch counter. Focused multi HTTP recovery cases execute production restoreMulti (renamed only at its declaration), grantSnapshot/retainRemote/refreshMultiGrants with synthetic HTTP and no pending approval or live task. finishMultiRestore simulates completion in migration cases. No multi handshake/task execution is claimed; sockets count cancellation only, and carrier ticks are explicit restore calls. Real Android binding, storage and network require device/emulator checks.']}
args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
