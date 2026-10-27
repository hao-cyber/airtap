#!/usr/bin/env python3
"""Execute real first-setup recovery methods with deterministic storage, lifecycle and HTTP faults."""
import argparse, datetime, hashlib, importlib.util, json, os, subprocess, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
PRODUCTION=ROOT/'app/src/main/java/io/airtap/preview'
p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);args=p.parse_args()
def module(path,name):
    spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
json_path=ROOT/'tests/approvalrecovery/platform_fakes.py'
fake_path=ROOT/'tests/setuprecovery/platform_fakes.py'
json_fakes=module(json_path,'json_fakes');fakes=module(fake_path,'setup_fakes')
source=(PRODUCTION/'MainActivity.java').read_text();extracted={}
def method(signature):
    assert source.count(signature)==1,signature
    start=source.index(signature);end=source.index('\n    }',start)+len('\n    }')
    text=source[start:end];extracted[signature.strip()]=text;return text
signatures=['private JSONObject trust()', 'private JSONObject pendingApproval()', 'private boolean currentSetup(', 'private void fenceSetup()', 'private boolean invitationOutstanding()', 'private void continueSetup()', 'private void retrySetup(', 'private void enroll()', 'private void discoverEnrollment(', 'private void checkEnrollment(', 'private void postEnrollment(', 'private void finishEnrollment(', 'private void invite()', 'private void checkInvitation(', 'private void queryInvitation(', 'private void poll()', 'private void disconnect()', 'private void switchService(', '@Override public void onPause()']
signatures += ['private String prompt()', '@Override protected void onSaveInstanceState(']
# setupFailed is a one-line method; include the exact line separately.
failed=next(line for line in source.splitlines() if 'private void setupFailed(' in line);extracted['setupFailed']=failed
upgrade=next(line for line in source.splitlines() if 'upgrade = config != null' in line);extracted['onCreate upgrade branch']=upgrade
restore=source[source.index('        if (saved != null && !upgrade'):source.index('        LinearLayout root = AppUi.root(this);')]
extracted['onCreate saved invitation restore']=restore
harness='''package io.airtap.preview;import org.json.JSONObject;import android.os.Bundle;
final class MainActivityHarness extends android.app.Activity {
 final android.os.Handler main=new android.os.Handler();final Prefs prefs=new Prefs();final ConnectionFeedback connectionFeedback=new ConnectionFeedback();
 final SetupRecovery.Backoff setupBackoff=new SetupRecovery.Backoff(),pollBackoff=new SetupRecovery.Backoff();
 EnrollmentStore.Config config;InvitationArtifacts invitationArtifacts;boolean visible=true,busy,polling,storageError,upgrade,setupPaused,setupExhausted,invitationUncertain;int epoch;long invitationExpiry,nextPoll;String ticket,claim,code,candidate,message="";okhttp3.Call setupCall,pollCall;Runnable setupRetry,refresh=()->{};
 Prefs getPreferences(int mode){return prefs;}void change(String text){if(isDestroyed())throw new AssertionError("late UI");message=text;}
 void enrollAction(){enroll();}void inviteAction(){invite();}void continueAction(){continueSetup();}void pollAction(){poll();}void disconnectAction(){disconnect();}void switchAction(String address){switchService(address);}
 String promptAction(){return prompt();}Bundle saveAction(){Bundle b=new Bundle();onSaveInstanceState(b);return b;}
'''+ 'void restoreAction(Bundle saved){'+restore+'}\n'+'void loadProtocol(int protocol){prefs.edit().putInt("protocol",protocol).commit();'+upgrade+'}\n'+failed+'\n'+'\n'.join(method('    '+signature) for signature in signatures)+'\n}\n'
sources=[PRODUCTION/(name+'.java') for name in ['SetupRecovery','RemoteReadiness','ServiceProtocol','InvitationFeedback','InvitationArtifacts','RelayAddress']]
test=ROOT/'tests/setuprecovery/SetupRecoveryTest.java';jdk=Path(os.environ.get('JAVA_HOME','/Applications/Android Studio.app/Contents/jbr/Contents/Home'))/'bin'
with tempfile.TemporaryDirectory(prefix='airtap-setup-recovery-') as temp:
    temp=Path(temp);fixtures=dict(fakes.SOURCES)
    for name in ['org/json/JSONObject.java','org/json/JSONArray.java']:fixtures[name]=json_fakes.SOURCES[name]
    fixtures['org/json/JSONObject.java']=fixtures['org/json/JSONObject.java'].replace('public JSONObject getJSONObject', 'public JSONObject optJSONObject(String k){Object v=opt(k);return v instanceof JSONObject?(JSONObject)v:null;}public JSONObject getJSONObject')
    fixtures['io/airtap/preview/MainActivityHarness.java']=harness
    generated=[]
    for name,value in fixtures.items():
        path=temp/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(value);generated.append(path)
    subprocess.run([str(jdk/'javac'),'--release','17','-d',str(temp),*map(str,sources),str(test),*map(str,generated)],check=True,timeout=30)
    run=subprocess.run([str(jdk/'java'),'-cp',str(temp),'io.airtap.preview.SetupRecoveryTest'],capture_output=True,text=True,timeout=30)
    print(run.stdout,end='')
    if run.returncode:print(run.stderr,end='');raise SystemExit(run.returncode)
    tests=[line[5:] for line in run.stdout.splitlines() if line.startswith('PASS ')];assert run.stdout.splitlines()[-1]=='TOTAL '+str(len(tests))
inputs=[*sources,PRODUCTION/'MainActivity.java',test,json_path,fake_path,Path(__file__)]
record={'status':'passed','recorded_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'passed':len(tests),'tests':tests,
 'source_sha256':{str(path.relative_to(ROOT)):hashlib.sha256(path.read_bytes()).hexdigest() for path in inputs},
 'extracted_method_sha256':{key:hashlib.sha256(value.encode()).hexdigest() for key,value in extracted.items()},
 'scope':'Production MainActivity first-setup/discovery/enrollment/invitation/prompt/saved-state restore/polling/stop/relay-switch/onPause methods extracted verbatim, plus complete SetupRecovery, RemoteReadiness, ServiceProtocol, InvitationFeedback, InvitationArtifacts and RelayAddress.',
 'limits':['Synthetic Activity, storage, Handler and HTTP. Does not prove Android system permission screens, actual connectivity, OEM background behavior, deployed relay or personal-AI tasks.']}
args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
