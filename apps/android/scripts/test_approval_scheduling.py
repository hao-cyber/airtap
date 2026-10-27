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
 final ConnectionFeedback connectionFeedback=new ConnectionFeedback();final android.os.Handler main=new android.os.Handler();
 EnrollmentStore.Config config;Object invitationArtifacts;boolean busy,storageError;int epoch,changes;String claim,ticket,code,candidate,message="";long invitationExpiry;
 MainActivityHarness(EnrollmentStore.Config c,String claim){config=c;this.claim=claim;candidate="synthetic owner-confirmed AI";}
 void pressApprove(){approve();}void closeInvitation(){}void change(String text){if(isDestroyed())throw new AssertionError("UI touched after destruction");message=text;changes++;}
'''+method('MainActivity.java','    private void approve() {')+'\n'+method('MainActivity.java','    private JSONObject pendingApproval() {')+'\n'+method('MainActivity.java','    @Override public void onDestroy() {')+'\n}\n'
service='''package io.airtap.preview;
import org.json.JSONObject;import android.net.ConnectivityManager;import okhttp3.WebSocket;
final class PreviewService extends android.content.Context {
 static PreviewService instance;final android.os.Handler main=new android.os.Handler();
 boolean authorized,restoring,connected,notificationBlocked;long activeCommand;int generation,retry,connects,networkStarts,notificationFailures;String state,url,token;
 RecoveryStatus recoveryStatus=RecoveryStatus.WAITING_FOR_AI;java.util.concurrent.atomic.AtomicBoolean transportOpen=new java.util.concurrent.atomic.AtomicBoolean(true);
 Object networkCallback,currentNetwork;Runnable reconnectTask;WebSocket socket;EnrollmentStore.Config config;
 static final class Ui {int clears,cancellations;ScreenPreparation.State screen=ScreenPreparation.State.READY;ScreenPreparation.State screenState(){return screen;}void clear(){clears++;}void cancelGestures(){cancellations++;}}final Ui ui=new Ui();
 static final class Recovery {int cancellations;void cancel(){cancellations++;}}final Recovery recovery=new Recovery();
 PreviewService(){instance=this;}
 boolean executable(){return executionAllowed();}
 void observeNetwork(int run){if(run!=generation)throw new AssertionError();networkStarts++;}
 void connect(int run){if(run!=generation||!authorized)throw new AssertionError();connects++;}
'''+ '\n'.join(method('PreviewService.java',s) for s in ['    void restore() {','    private void retryApproval(int run) {','    private void scheduleRestore(int run, long delay) {','    private void waitForNotifications() {','    void notificationUnavailable(int run, boolean startFailed) {','    void notificationReady(int run) {','    private boolean executionAllowed() {','    RecoveryStatus recoveryStatus() {','    void stop(String message) {','    private void suspend(String message) {'])+'\n}\n'
sources=[PRODUCTION/(name+'.java') for name in ['TrustedGrant','ApprovalRecovery','ConnectionFeedback','ServiceProtocol','RecoveryStatus','ScreenPreparation']]
with tempfile.TemporaryDirectory(prefix='airtap-approval-scheduling-') as directory:
    temp=Path(directory);generated=[]
    fixtures=dict(fakes.SOURCES);fixtures.update(fakes.SCHEDULING_SOURCES)
    fixtures['io/airtap/preview/MainActivityHarness.java']=activity
    fixtures['io/airtap/preview/PreviewService.java']=service
    fixtures['io/airtap/preview/TlsPolicy.java']='package io.airtap.preview; final class TlsPolicy { static final String USER_MESSAGE="synthetic security error"; }'
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
        'scope':'Production approve/pendingApproval/onDestroy and restore, notification gating/recovery, typed status and stop/suspend methods are extracted verbatim and executed with complete production TrustedGrant, ApprovalRecovery, ConnectionFeedback and ServiceProtocol.',
        'limits':['Deterministic synthetic Android Activity, per-owner Handler, persistence and HTTP. No Activity refresh is injected after destruction. The service connect() endpoint is a counter: these tests prove dispatch into connection startup, not actual Android binding, sockets, notifications or OEM background execution.',
                  'Real Android Back/process recreation, notifications, storage and network must still be exercised on device/emulator.']}
args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
