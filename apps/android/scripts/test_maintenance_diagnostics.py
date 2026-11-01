#!/usr/bin/env python3
"""Run the opt-in Activity dump and pure trust/service snapshots against synthetic state."""
import argparse
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/io/airtap/preview'
FAKES = ROOT / 'tests/approvalrecovery/platform_fakes.py'
spec = importlib.util.spec_from_file_location('diagnostic_fakes', FAKES)
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
fixtures = dict(base.SOURCES)

def method(path, signature):
    source = path.read_text()
    start = source.index(signature)
    return source[start:source.index('\n    }', start) + 6]

dump = method(JAVA / 'MainActivity.java', '    @Override public void dump(')
service = method(JAVA / 'PreviewService.java', '    JSONObject diagnostics(')
owner = method(JAVA / 'MultiAiOwner.java', '    JSONObject diagnostics(')
button = method(JAVA / 'MainActivity.java', '    private Button button(')
fixtures['android/app/Activity.java'] = '''package android.app;
public class Activity extends android.content.Context {public int defaultDumps;
 public void dump(String prefix,java.io.FileDescriptor fd,java.io.PrintWriter writer,String[] args){defaultDumps++;writer.println("DEFAULT_SYSTEM_DUMP");}}
'''
fixtures['io/airtap/preview/MainActivity.java'] = '''package io.airtap.preview;
import org.json.JSONObject;import android.view.View;import android.widget.Button;public final class MainActivity extends android.app.Activity {
 EnrollmentStore.Config config;boolean visible,busy,storageError;final MultiAiOwner multiOwner=new MultiAiOwner();
 long buttonTouchEvents,buttonTouchRejected;int buttonTouchLastFlags;
 Button testButton(){return button("synthetic-private-control-name",v->{});}
''' + dump + '\n' + button + '\n}'
fixtures['android/view/View.java'] = 'package android.view;public class View {public interface OnClickListener {void onClick(View view);}}'
fixtures['android/view/MotionEvent.java'] = 'package android.view;public final class MotionEvent {public static final int FLAG_WINDOW_IS_OBSCURED=1,FLAG_WINDOW_IS_PARTIALLY_OBSCURED=2;private final int flags;public MotionEvent(int flags){this.flags=flags;}public int getFlags(){return flags;}}'
fixtures['android/widget/Button.java'] = '''package android.widget;public class Button extends android.view.View {
 public boolean parentAllows=true,filterWhenObscured;public int parentChecks;public Button(android.content.Context c){}
 public boolean onFilterTouchEventForSecurity(android.view.MotionEvent event){parentChecks++;return parentAllows;}
 public void setText(String text){}public void setFilterTouchesWhenObscured(boolean value){filterWhenObscured=value;}public void setOnClickListener(OnClickListener listener){}
}'''
fixtures['io/airtap/preview/AppUi.java'] = 'package io.airtap.preview;final class AppUi {static final int SECONDARY=1;static void button(android.widget.Button button,int style){}}'
fixtures['android/app/AlertDialog.java'] = 'package android.app;public final class AlertDialog {public boolean showing;public boolean isShowing(){return showing;}}'
fixtures['io/airtap/preview/MultiAiOwner.java'] = '''package io.airtap.preview;
import org.json.JSONObject;final class MultiAiOwner {EnrollmentStore.Config identity;boolean visible,busy;
 android.app.AlertDialog enableCheck;String lastEnableStage="not_requested";
''' + owner + '\n}'
fixtures['io/airtap/preview/PreviewService.java'] = '''package io.airtap.preview;
import org.json.JSONObject;final class PreviewService {static PreviewService instance;
 EnrollmentStore.Config config;boolean authorized,connected,multiMode,multiHello,restoring,notificationBlocked;
''' + service + '\n}'
# The fake serializer emits JSON bytes so the Python side can parse the actual dump output.
# Parsing encrypted records / Android JSON is not claimed by this host test.
serializer = r'''
public String toString(){StringBuilder s=new StringBuilder("{");boolean first=true;
 for(var e:fields.entrySet()){if(!first)s.append(',');first=false;s.append(quoted(e.getKey())).append(':').append(encoded(e.getValue()));}return s.append('}').toString();}
private static String quoted(String s){StringBuilder b=new StringBuilder("\"");for(char c:s.toCharArray()){
 if(c=='"'||c=='\\')b.append('\\').append(c);else if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}return b.append('"').toString();}
public static String encoded(Object v){if(v==NULL)return "null";if(v instanceof String)return quoted((String)v);
 if(v instanceof Number||v instanceof Boolean||v instanceof JSONObject||v instanceof JSONArray)return v.toString();throw new AssertionError("unexpected diagnostic type");}
'''
fixtures['org/json/JSONObject.java'] = fixtures['org/json/JSONObject.java'].replace('public JSONObject copy()', serializer + '\npublic JSONObject copy()')
fixtures['org/json/JSONArray.java'] = fixtures['org/json/JSONArray.java'].replace('public JSONArray copy()', 'public String toString(){java.util.List<String> rows=new java.util.ArrayList<>();for(Object value:v)rows.add(JSONObject.encoded(value));return "["+String.join(",",rows)+"]";}public JSONArray copy()')
fixtures['io/airtap/preview/EnrollmentStore.java'] = fixtures['io/airtap/preview/EnrollmentStore.java'].replace(
    'Config readForDiagnostics(){return read();}',
    'static int diagnosticReads;Config readForDiagnostics(){diagnosticReads++;return read();}')

test = r'''package io.airtap.preview;
import java.util.*;import org.json.JSONObject;
public final class MaintenanceDiagnosticsTest {
 static final String CLAIM="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", SECRET="synthetic-private-token", NAME="synthetic-private-agent-name";
 static final EnrollmentStore.Config CONFIG=new EnrollmentStore.Config("https://diagnostics.invalid/d/11111111111111111111111111111111",SECRET);
 static MainActivity activity;static int passed;
 interface Checked {void run()throws Exception;}
 static void check(boolean condition){if(!condition)throw new AssertionError();}
 static void test(String name,Checked body)throws Exception{FakeDisk.reset();RelayApi.requests.clear();FakeDisk.config=CONFIG;
  PreviewService.instance=null;activity=new MainActivity();EnrollmentStore.diagnosticReads=0;body.run();passed++;System.out.println("PASS "+name);}
 static TrustedGrant trust(){return new TrustedGrant(activity);}
 static void approved()throws Exception{String id=trust().beginApproval(CONFIG,CLAIM,NAME);check(trust().completeApproval(CONFIG,id));}
 static String print(String[] args){var buffer=new java.io.StringWriter();activity.dump("",null,new java.io.PrintWriter(buffer),args);return buffer.toString();}
 static String dump()throws Exception{
  String record=FakeDisk.record==null?null:FakeDisk.record.toString();int saves=FakeDisk.saves,commits=FakeDisk.preferences.calls;
  var memory=new HashMap<>(FakeDisk.preferences.memory);var disk=new HashMap<>(FakeDisk.preferences.disk);
  String result=print(new String[]{"--airtap-diagnostics"});
  check(Objects.equals(record,FakeDisk.record==null?null:FakeDisk.record.toString()));
  check(saves==FakeDisk.saves&&commits==FakeDisk.preferences.calls&&memory.equals(FakeDisk.preferences.memory)&&disk.equals(FakeDisk.preferences.disk));
  check(RelayApi.requests.isEmpty()&&activity.defaultDumps==0);
  for(String forbidden:List.of(CONFIG.url,SECRET,CLAIM,NAME,"synthetic-private-error","synthetic-private-control-name"))check(!result.contains(forbidden));
  System.out.print(result);return result;
 }
 public static void main(String[] args)throws Exception{
  test("default, unknown and mixed dump arguments leave diagnostic storage unread",()->{
   activity.buttonTouchEvents=9;activity.buttonTouchRejected=3;activity.buttonTouchLastFlags=2;
   for(String[] flags:new String[][]{null,new String[0],new String[]{"other"},new String[]{"--airtap-diagnostics","extra"}}){
    check(print(flags).equals("DEFAULT_SYSTEM_DUMP\n"));check(EnrollmentStore.diagnosticReads==0);}
   check(activity.defaultDumps==4&&FakeDisk.saves==0&&FakeDisk.preferences.calls==0);
  });
  test("absent enrollment is unknown and does not create trust",()->{FakeDisk.config=null;
   String result=dump();check(result.contains("\"mode\":\"unknown\"")&&result.contains("\"storage_status\":\"no_public_enrollment\""));check(FakeDisk.record==null);
  });
  test("approved single reports one grant and only a canonical device hash",()->{approved();
   JSONObject d=trust().diagnostics(CONFIG);check(d.getString("mode").equals("single")&&d.getLong("grant_count")==1&&!d.getBoolean("migration_pending"));dump();
  });
  test("pending approval is not reported as active consent",()->{trust().beginApproval(CONFIG,CLAIM,NAME);
   JSONObject d=trust().diagnostics(CONFIG);check(d.getLong("grant_count")==0&&!d.getBoolean("active_grant_present"));dump();
  });
  test("prepared and unknown migration remain single and pending without dispatch",()->{approved();String id=trust().beginMigration(CONFIG,trust().sessionFence(CONFIG));
   check(trust().diagnostics(CONFIG).getBoolean("migration_pending"));dump();trust().dispatchMigration(CONFIG,id);
   check(trust().diagnostics(CONFIG).getString("mode").equals("single")&&trust().diagnostics(CONFIG).getBoolean("migration_pending"));dump();
  });
  test("multi snapshot does not repair a missing secondary marker",()->{approved();String id=trust().beginMigration(CONFIG,trust().sessionFence(CONFIG));
   check(trust().completeMigration(CONFIG,id,Set.of(CLAIM)));FakeDisk.preferences.memory.remove("multi_identity");FakeDisk.preferences.disk.remove("multi_identity");
   JSONObject d=trust().diagnostics(CONFIG);check(d.getString("mode").equals("multi")&&d.getLong("grant_count")==1&&d.getLong("lease_ready_grant_count")==0);dump();
   check(!FakeDisk.preferences.memory.containsKey("multi_identity"));
  });
  test("owner stop remains stopped and zero grants under diagnostics",()->{approved();trust().enableMulti(CONFIG);trust().stop();
   JSONObject d=trust().diagnostics(CONFIG);check(d.getString("mode").equals("multi")&&d.getLong("grant_count")==0&&d.getBoolean("owner_stopped"));dump();
  });
  test("identity mismatch is fixed unknown, without raw identity or error",()->{approved();FakeDisk.record.put("identity","synthetic-private-error");
   String result=dump();check(result.contains("\"mode\":\"unknown\"")&&result.contains("\"storage_status\":\"unavailable\""));
  });
  test("unreadable or malformed trust is unknown and never replaced",()->{approved();FakeDisk.failReadRecord=true;
   check(dump().contains("\"mode\":\"unknown\""));FakeDisk.failReadRecord=false;FakeDisk.record.put("schema_version",9000);check(dump().contains("\"mode\":\"unknown\""));
  });
  test("live service flags distinguish connected from multi hello readiness",()->{approved();PreviewService s=new PreviewService();s.config=CONFIG;s.authorized=true;s.connected=true;s.multiMode=true;PreviewService.instance=s;
   String result=dump();check(result.contains("\"connected\":true")&&result.contains("\"multi_hello_acknowledged\":false")&&result.contains("\"service_identity_matches\":true"));
   s.multiHello=true;check(dump().contains("\"multi_hello_acknowledged\":true"));check(s.authorized&&s.connected&&s.multiMode&&s.multiHello);
  });
  test("service from another identity is flagged without disclosing its identity",()->{approved();PreviewService s=new PreviewService();s.config=new EnrollmentStore.Config("https://other.invalid/d/22222222222222222222222222222222","synthetic-other-secret");PreviewService.instance=s;
   String result=dump();check(result.contains("\"service_identity_matches\":false")&&!result.contains(s.config.url)&&!result.contains(s.config.token));
  });
  test("activity and owner flags expose UI readiness without private UI text",()->{approved();activity.config=CONFIG;activity.visible=true;activity.busy=true;activity.storageError=true;
   activity.multiOwner.identity=CONFIG;activity.multiOwner.visible=true;activity.multiOwner.busy=true;activity.multiOwner.lastEnableStage="checking_service";
   activity.multiOwner.enableCheck=new android.app.AlertDialog();activity.multiOwner.enableCheck.showing=true;
   String result=dump();check(result.contains("\"storage_error\":true")&&result.contains("\"enable_check_visible\":true")&&result.contains("\"last_enable_stage\":\"checking_service\""));
   check(activity.visible&&activity.busy&&activity.storageError&&activity.multiOwner.enableCheck.showing);
  });
  test("button diagnostics preserve both obscured guards and parent short circuit",()->{
   var b=activity.testButton();check(b.filterWhenObscured);
   check(b.onFilterTouchEventForSecurity(new android.view.MotionEvent(0))&&b.parentChecks==1);
   for(int flags:new int[]{1,2,3})check(!b.onFilterTouchEventForSecurity(new android.view.MotionEvent(flags)));
   check(b.parentChecks==1&&activity.buttonTouchEvents==4&&activity.buttonTouchRejected==3&&activity.buttonTouchLastFlags==3);
   b.parentAllows=false;check(!b.onFilterTouchEventForSecurity(new android.view.MotionEvent(0))&&b.parentChecks==2);
   b.parentAllows=true;check(b.onFilterTouchEventForSecurity(new android.view.MotionEvent(32))&&b.parentChecks==3);
   check(activity.buttonTouchEvents==6&&activity.buttonTouchRejected==4&&activity.buttonTouchLastFlags==32);
   String result=dump();check(result.contains("\"button_touch_events\":6")&&result.contains("\"button_touch_rejected\":4")&&result.contains("\"button_touch_last_flags\":32"));
   check(activity.buttonTouchEvents==6&&activity.buttonTouchRejected==4&&b.parentChecks==3);
  });
  test("touch counters saturate without changing the original rejection",()->{
   activity.buttonTouchEvents=Long.MAX_VALUE;activity.buttonTouchRejected=Long.MAX_VALUE;var b=activity.testButton();
   check(!b.onFilterTouchEventForSecurity(new android.view.MotionEvent(1))&&b.parentChecks==0);
   check(activity.buttonTouchEvents==Long.MAX_VALUE&&activity.buttonTouchRejected==Long.MAX_VALUE);dump();
  });
  System.out.println("TOTAL "+passed);
 }
}
'''

p = argparse.ArgumentParser()
p.add_argument('--output', type=Path)
args = p.parse_args()
sources = [JAVA / (name + '.java') for name in ['TrustedGrant', 'ServiceProtocol', 'RelayAddress']]
jdk = Path(os.environ.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home')) / 'bin'
with tempfile.TemporaryDirectory(prefix='airtap-diagnostic-') as directory:
    temp = Path(directory)
    generated = []
    fixtures['io/airtap/preview/MaintenanceDiagnosticsTest.java'] = test
    for name, contents in fixtures.items():
        path = temp / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents)
        generated.append(path)
    subprocess.run([str(jdk / 'javac'), '--release', '17', '-d', str(temp), *map(str, sources), *map(str, generated)], check=True, timeout=30)
    run = subprocess.run([str(jdk / 'java'), '-cp', str(temp), 'io.airtap.preview.MaintenanceDiagnosticsTest'], text=True, capture_output=True, timeout=30)
if run.returncode:
    print(run.stdout, end='')
    print(run.stderr, end='')
    raise SystemExit(run.returncode)
rows = [json.loads(line.removeprefix('AIRTAP_DIAGNOSTICS ')) for line in run.stdout.splitlines() if line.startswith('AIRTAP_DIAGNOSTICS ')]
expected_hash = hashlib.sha256(b'https://diagnostics.invalid/d/11111111111111111111111111111111').hexdigest()
keys = {'diagnostics_version', 'mode', 'migration_pending', 'active_grant_present', 'grant_count', 'lease_ready_grant_count', 'owner_stopped', 'device_base_sha256', 'storage_status', 'service', 'activity', 'owner'}
service_keys = {'service_present', 'service_identity_matches', 'authorized', 'connected', 'service_multi_mode', 'multi_hello_acknowledged', 'restoring', 'notification_blocked'}
for row in rows:
    assert set(row) == keys
    assert row['device_base_sha256'] in (None, expected_hash)
    assert set(row['service']) <= service_keys
    assert set(row['activity']) == {'visible', 'busy', 'storage_error', 'identity_matches', 'button_touch_events', 'button_touch_rejected', 'button_touch_last_flags'}
    assert set(row['owner']) == {'visible', 'busy', 'enable_check_visible', 'identity_matches', 'last_enable_stage'}
checks = [line for line in run.stdout.splitlines() if line.startswith('PASS ')]
checks.append('PASS emitted diagnostic JSON has only the reviewed fixed keys and exact canonical hash')
print('\n'.join(checks))
if args.output:
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({
        'status': 'passed', 'passed': len(checks), 'checks': checks,
        'recorded_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'source_sha256': {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in [*sources, JAVA/'MainActivity.java', JAVA/'PreviewService.java', JAVA/'MultiAiOwner.java', FAKES, Path(__file__)]},
        'extracted_sha256': {'MainActivity.dump': hashlib.sha256(dump.encode()).hexdigest(), 'MainActivity.button': hashlib.sha256(button.encode()).hexdigest(), 'PreviewService.diagnostics': hashlib.sha256(service.encode()).hexdigest(), 'MultiAiOwner.diagnostics': hashlib.sha256(owner.encode()).hexdigest()},
        'scope': 'Production trust logic and verbatim dump/service methods; synthetic Android preferences, encrypted-store adapter and JSON serializer. No phone, Android dumpsys dispatch, actual UI, network or lifecycle validation. Real bounded encrypted diagnostic file reads are checked separately by test_enrollment_record_limits.py.'
    }, indent=2) + '\n')
