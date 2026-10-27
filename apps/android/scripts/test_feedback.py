#!/usr/bin/env python3
"""Exercise production report parsing/text plus extracted Activity request/lifecycle methods."""
import argparse, datetime, hashlib, importlib.util, json, os, subprocess, tempfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT/'app/src/main/java/io/airtap/preview'
TEST = ROOT/'tests/feedback/FeedbackTest.java'
p = argparse.ArgumentParser()
p.add_argument('--output', type=Path, default=ROOT/'results/feedback-ui.json')
args = p.parse_args()
spec = importlib.util.spec_from_file_location('approval_fakes', ROOT/'tests/approvalrecovery/platform_fakes.py')
fakes = importlib.util.module_from_spec(spec); spec.loader.exec_module(fakes)
activity = (JAVA/'FeedbackActivity.java').read_text()
def method(name):
    import re
    match = re.search(r'^    (?:@Override )?(?:public|private) (?:static )?[^\n]+\b'+re.escape(name)+r'\([^\n]*\) \{', activity, re.M)
    assert match, name
    start = activity.index('{', match.start()); depth = 1; end = start + 1
    while depth:
        if activity[end] == '{': depth += 1
        elif activity[end] == '}': depth -= 1
        end += 1
    return activity[match.start():end]
methods = ['onResume','onPause','onDestroy','back','readIdentity','usable','same','unavailableIdentity','begin','current','cancel','loadPage','loadDetail','delete']
fields = activity[activity.index('    private final FeedbackRequestGate'):activity.index('    @Override public void onCreate')]
harness = '''package io.airtap.preview;
import android.app.Activity; import android.widget.LinearLayout; import android.widget.ScrollView;
import java.util.*; import okhttp3.Call; import org.json.*;
public final class FeedbackHarness extends Activity {
''' + fields + '\n'.join(method(n) for n in methods) + '''
 int renders; String renderedTitle;
 private void render(){renders++;renderedTitle=detail==null?null:detail.summary.title;}
''' + TEST.read_text() + '\n}\n'
sources = {
 'org/json/JSONObject.java': fakes.SOURCES['org/json/JSONObject.java'],
 'org/json/JSONArray.java': '''package org.json;public class JSONArray {private final java.util.List<Object> v=new java.util.ArrayList<>();public JSONArray put(Object x){v.add(x);return this;}public int length(){return v.size();}public JSONObject getJSONObject(int n){return (JSONObject)v.get(n);}}''',
 'android/app/Activity.java': '''package android.app;public class Activity {boolean destroyed,finishing;public void onResume(){}public void onPause(){}public void onDestroy(){destroyed=true;}public boolean isDestroyed(){return destroyed;}public boolean isFinishing(){return finishing;}public void finish(){finishing=true;}}''',
 'android/widget/LinearLayout.java': '''package android.widget;public class LinearLayout {public int cleared;public void removeAllViews(){cleared++;}}''',
 'android/widget/ScrollView.java': '''package android.widget;public class ScrollView {public void smoothScrollTo(int x,int y){}}''',
 'okhttp3/Call.java': '''package okhttp3;public interface Call {void cancel();}''',
 'io/airtap/preview/EnrollmentStore.java': '''package io.airtap.preview;final class EnrollmentStore {static Config value;static boolean fail;EnrollmentStore(Object c){}Config read(){if(fail)throw new IllegalStateException();return value;}static final class Config {final String url,token;boolean registered=true;Config(String u,String t){url=u;token=t;}boolean enrolled(){return registered;}boolean publicService(){return true;}}}''',
 'io/airtap/preview/FeedbackApi.java': '''package io.airtap.preview;import java.util.*;import org.json.JSONObject;import okhttp3.Call;
final class FeedbackApi {interface Result {void complete(JSONObject b,int s,String c);}static final List<Pending> calls=new ArrayList<>();
static final class Pending implements Call {final String method,id,cursor;final FeedbackRequestGate.Ticket ticket;final Result callback;boolean cancelled;Pending(String m,String i,String c,FeedbackRequestGate.Ticket t,Result cb){method=m;id=i;cursor=c;ticket=t;callback=cb;}public void cancel(){cancelled=true;}void reply(JSONObject b,int s,String c){callback.complete(b,s,c);}}
static Call add(String m,String id,String c,FeedbackRequestGate.Ticket t,Result cb){Pending p=new Pending(m,id,c,t,cb);calls.add(p);return p;}
static Call list(FeedbackRequestGate.Ticket t,String c,Result cb){return add("GET",null,c,t,cb);}static Call detail(FeedbackRequestGate.Ticket t,String id,Result cb){return add("GET",id,null,t,cb);}static Call delete(FeedbackRequestGate.Ticket t,String id,Result cb){return add("DELETE",id,null,t,cb);}}
''',
 'io/airtap/preview/FeedbackHarness.java': harness,
}
jdk = Path(os.environ.get('JAVA_HOME','/Applications/Android Studio.app/Contents/jbr/Contents/Home'))/'bin'
production = [JAVA/(n+'.java') for n in ['FeedbackRequestGate','FeedbackText','FeedbackReport']]
with tempfile.TemporaryDirectory(prefix='airtap-feedback-') as directory:
    temp = Path(directory); generated=[]
    for path,value in sources.items():
        target=temp/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(value);generated.append(target)
    subprocess.run([str(jdk/'javac'),'--release','17','-d',str(temp),*map(str,production),*map(str,generated)],check=True,timeout=30)
    run=subprocess.run([str(jdk/'java'),'-cp',str(temp),'io.airtap.preview.FeedbackHarness'],text=True,capture_output=True,timeout=30)
    print(run.stdout,end='')
    if run.returncode:print(run.stderr,end='');raise SystemExit(run.returncode)
    tests=[line[5:] for line in run.stdout.splitlines() if line.startswith('PASS ')]
    assert run.stdout.splitlines()[-1]=='TOTAL '+str(len(tests))
record={'status':'passed','recorded_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'passed':len(tests),'tests':tests,
 'source_sha256':{str(path.relative_to(ROOT)):hashlib.sha256(path.read_bytes()).hexdigest() for path in [*production,JAVA/'FeedbackActivity.java',TEST,Path(__file__)]},
 'limits':['Production parser, text, request gate compiled verbatim; Activity lifecycle/read/delete callback methods extracted verbatim with explicit view/network/enrollment fixtures.','Does not replace device UI, actual TLS/API integration, Android FLAG_SECURE or real encrypted enrollment tests.']}
args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
