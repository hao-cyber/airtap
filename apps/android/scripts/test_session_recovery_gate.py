#!/usr/bin/env python3
"""Execute the real platform recovery gate with explicit exit-history/accessibility fakes."""
import argparse, datetime, hashlib, json, os, subprocess, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/'app/src/main/java/io/airtap/preview/SessionRecoveryGate.java'
TEST=ROOT/'tests/sessionrecovery/SessionRecoveryGateTest.java'
FAKES={
'android/content/Context.java':'''package android.content;public class Context {public static final java.util.Map<Class<?>,Object> services=new java.util.HashMap<>();public static SharedPreferences prefs;public <T>T getSystemService(Class<T> c){return c.cast(services.get(c));}public SharedPreferences getSharedPreferences(String n,int m){return prefs;}public String getPackageName(){return "fixture";}}''',
'android/content/SharedPreferences.java':'''package android.content;public interface SharedPreferences {long getLong(String n,long d);Editor edit();interface Editor{Editor putLong(String n,long v);boolean commit();}}''',
'android/content/ComponentName.java':'''package android.content;public final class ComponentName {private final String n;public ComponentName(Context c,Class<?> t){n=c.getPackageName()+"/"+t.getName();}private ComponentName(String s){n=s;}public static ComponentName unflattenFromString(String s){return new ComponentName(s);}public boolean equals(Object o){return o instanceof ComponentName&&n.equals(((ComponentName)o).n);}public int hashCode(){return n.hashCode();}}''',
'android/os/UserManager.java':'''package android.os;public class UserManager {public boolean unlocked=true;public boolean isUserUnlocked(){return unlocked;}}''',
'android/app/ApplicationExitInfo.java':'''package android.app;public class ApplicationExitInfo {public static final int REASON_USER_REQUESTED=10;private final int reason;private final long time;public ApplicationExitInfo(int r,long t){reason=r;time=t;}public int getReason(){return reason;}public long getTimestamp(){return time;}}''',
'android/app/ActivityManager.java':'''package android.app;public class ActivityManager {public final java.util.List<ApplicationExitInfo> exits=new java.util.ArrayList<>();public boolean fail;public java.util.List<ApplicationExitInfo> getHistoricalProcessExitReasons(String p,int pid,int max){if(!p.equals("fixture")||pid!=0||max!=16)throw new AssertionError();if(fail)throw new SecurityException();return exits;}}''',
'android/accessibilityservice/AccessibilityServiceInfo.java':'''package android.accessibilityservice;public class AccessibilityServiceInfo {public static final int FEEDBACK_ALL_MASK=-1;private final String id;public AccessibilityServiceInfo(String id){this.id=id;}public String getId(){return id;}}''',
'android/view/accessibility/AccessibilityManager.java':'''package android.view.accessibility;public class AccessibilityManager {public final java.util.List<android.accessibilityservice.AccessibilityServiceInfo> enabled=new java.util.ArrayList<>();public java.util.List<android.accessibilityservice.AccessibilityServiceInfo> getEnabledAccessibilityServiceList(int mask){return enabled;}}''',
'io/airtap/preview/PreviewService.java':'package io.airtap.preview;final class PreviewService {}',
}
p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);args=p.parse_args()
jdk=Path(os.environ.get('JAVA_HOME','/Applications/Android Studio.app/Contents/jbr/Contents/Home'))/'bin'
with tempfile.TemporaryDirectory(prefix='airtap-recovery-gate-') as directory:
    root=Path(directory);files=[]
    for name,body in FAKES.items():
        path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(body);files.append(path)
    subprocess.run([str(jdk/'javac'),'--release','17','-d',str(root),str(SOURCE),str(TEST),*map(str,files)],check=True)
    run=subprocess.run([str(jdk/'java'),'-cp',str(root),'io.airtap.preview.SessionRecoveryGateTest'],capture_output=True,text=True,check=True)
print(run.stdout,end='')
report={'status':'passed','recorded_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'checks':run.stdout.splitlines(),'source_sha256':{str(x.relative_to(ROOT)):hashlib.sha256(x.read_bytes()).hexdigest() for x in [SOURCE,TEST,Path(__file__)]},'scope':'Complete production gate, synthetic SharedPreferences commit faults, platform exit records and enabled-service lists; no actual Android service rebinding or OEM guarantees.'}
args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
