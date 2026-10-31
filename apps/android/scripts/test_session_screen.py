#!/usr/bin/env python3
"""Run the complete production SessionService with deterministic Android lifecycle/time fakes."""
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
PRODUCTION = ROOT/'app/src/main/java/io/airtap/preview'
STORAGE_FAKES = ROOT/'tests/approvalrecovery/platform_fakes.py'
spec = importlib.util.spec_from_file_location('approval_storage_fakes', STORAGE_FAKES)
storage_fakes = importlib.util.module_from_spec(spec); spec.loader.exec_module(storage_fakes)
FAKES = {
**storage_fakes.SOURCES,
'android/Manifest.java': '''package android; public final class Manifest {public static final class permission {public static final String POST_NOTIFICATIONS="notifications";}}''',
'android/R.java': '''package android; public final class R {public static final class drawable {public static final int ic_menu_view=1;}}''',
'android/content/pm/PackageManager.java': '''package android.content.pm; public final class PackageManager {public static final int PERMISSION_GRANTED=0;}''',
'android/content/pm/ServiceInfo.java': '''package android.content.pm; public final class ServiceInfo {public static final int FOREGROUND_SERVICE_TYPE_SPECIAL_USE=1;}''',
'android/os/IBinder.java': '''package android.os; public interface IBinder {}''',
'android/os/Looper.java': '''package android.os; public final class Looper {public static Looper getMainLooper(){return new Looper();}}''',
'android/os/Handler.java': '''package android.os;
import java.util.*;
public final class Handler {
 public static long now,serial;private record Event(long at,long order,Handler owner,Runnable run){}
 private static final PriorityQueue<Event> q=new PriorityQueue<>(Comparator.comparingLong(Event::at).thenComparingLong(Event::order));
 public Handler(){}public Handler(Looper ignored){}public void post(Runnable r){postDelayed(r,0);}public void postDelayed(Runnable r,long delay){q.add(new Event(now+delay,serial++,this,r));}
 public void removeCallbacks(Runnable r){q.removeIf(e->e.owner==this&&e.run==r);}public void removeCallbacksAndMessages(Object unused){q.removeIf(e->e.owner==this);}
 public static void reset(){q.clear();now=serial=0;}public static void advance(long target){while(!q.isEmpty()&&q.peek().at<=target){Event e=q.remove();now=e.at;e.run.run();}now=target;}
 public static int pending(){return q.size();}
}''',
'android/os/PowerManager.java': '''package android.os;
public final class PowerManager {
 public static final int SCREEN_DIM_WAKE_LOCK=6;public boolean interactive=true,supported=true;public WakeLock lock;
 public boolean isWakeLockLevelSupported(int level){return supported;}public WakeLock newWakeLock(int flags,String tag){if(flags!=SCREEN_DIM_WAKE_LOCK)throw new AssertionError("must not add wakeup flags");return lock=new WakeLock();}
 public final class WakeLock {
  private final Handler timer=new Handler();private boolean held;public boolean referenceCounted=true;public int acquires,releases,expirations;
  private final Runnable expiry=()->{held=false;expirations++;interactive=false;};
  public void setReferenceCounted(boolean value){referenceCounted=value;}
  public void acquire(long timeout){if(referenceCounted||timeout!=60000)throw new AssertionError("bounded non-counted lease");acquires++;timer.removeCallbacks(expiry);held=true;timer.postDelayed(expiry,timeout);}
  public boolean isHeld(){return held;}public void release(){releases++;held=false;timer.removeCallbacks(expiry);}
 }
}''',
'android/net/Uri.java': '''package android.net;public final class Uri {private final String value;private Uri(String s){value=s;}public static Uri parse(String s){return new Uri(s);}public String toString(){return value;}}''',
'android/content/Intent.java': '''package android.content;import java.util.*;public final class Intent {private String action;private android.net.Uri data;public final Class<?> component;private final Map<String,Object> extras=new HashMap<>();public Intent(Context c,Class<?> type){component=type;}public Intent(Intent other){component=other.component;action=other.action;data=other.data;extras.putAll(other.extras);}public Intent putExtra(String name,int value){extras.put(name,value);return this;}public Intent putExtra(String name,String value){extras.put(name,value);return this;}public int getIntExtra(String name,int fallback){Object v=extras.get(name);return v instanceof Integer?(Integer)v:fallback;}public String getStringExtra(String name){Object v=extras.get(name);return v instanceof String?(String)v:null;}public Intent setAction(String value){action=value;return this;}public String getAction(){return action;}public Intent setData(android.net.Uri value){data=value;return this;}public android.net.Uri getData(){return data;}}''',
'android/content/Context.java': '''package android.content;public class Context {public static android.os.PowerManager power;public static android.app.NotificationManager notifications;public static int stops,foregroundStarts;public static boolean startFails,permission=true;public static Intent started;public Context getApplicationContext(){return this;}public SharedPreferences getSharedPreferences(String k,int mode){return io.airtap.preview.FakeDisk.preferences;}public <T>T getSystemService(Class<T> type){return type.cast(type==android.os.PowerManager.class?power:type==android.app.NotificationManager.class?notifications:null);}public int checkSelfPermission(String p){return permission?0:-1;}public void startForegroundService(Intent i){foregroundStarts++;if(startFails)throw new android.app.ForegroundServiceStartNotAllowedException();started=new Intent(i);}public void stopService(Intent i){stops++;}}''',
'android/app/ForegroundServiceStartNotAllowedException.java': '''package android.app;public final class ForegroundServiceStartNotAllowedException extends RuntimeException {}''',
'android/util/Log.java': '''package android.util;public final class Log {public static final java.util.List<String> warnings=new java.util.ArrayList<>();public static int i(String tag,String text){return 0;}public static int w(String tag,String text){warnings.add(tag+":"+text);return 0;}}''',
'android/app/Service.java': '''package android.app;public class Service extends android.content.Context {public static final int START_STICKY=1,START_NOT_STICKY=2;public int stopped,lastStoppedId=-1;public Notification notice;public static boolean foregroundFails;public static int foregroundCalls;public static Runnable foregroundHook;public void onCreate(){}public void onDestroy(){}public int onStartCommand(android.content.Intent i,int flags,int id){return 2;}public android.os.IBinder onBind(android.content.Intent i){return null;}public void stopSelf(){stopped++;}public void stopSelf(int id){stopped++;lastStoppedId=id;}public void startForeground(int id,Notification n,int type){foregroundCalls++;if(foregroundFails)throw new IllegalStateException("synthetic notification refusal");notice=n;if(foregroundHook!=null)foregroundHook.run();}}''',
'android/app/NotificationChannel.java': '''package android.app;public final class NotificationChannel {private final int importance;public NotificationChannel(String id,String name,int importance){this.importance=importance;}public int getImportance(){return importance;}}''',
'android/app/NotificationManager.java': '''package android.app;public final class NotificationManager {public static final int IMPORTANCE_NONE=0,IMPORTANCE_LOW=2;public boolean enabled=true;private NotificationChannel channel;public NotificationChannel getNotificationChannel(String id){return channel;}public boolean areNotificationsEnabled(){return enabled;}public void createNotificationChannel(NotificationChannel c){if(channel==null)channel=c;}}''',
'android/app/PendingIntent.java': '''package android.app;import android.content.*;import java.util.*;public final class PendingIntent {public static final int FLAG_UPDATE_CURRENT=1,FLAG_IMMUTABLE=2;private static final Map<String,PendingIntent> registry=new HashMap<>();private Intent intent;public Intent intent(){return new Intent(intent);}public static void reset(){registry.clear();}private static PendingIntent get(String kind,int code,Intent i,int flags){String key=kind+"|"+code+"|"+i.component.getName()+"|"+i.getAction()+"|"+i.getData();PendingIntent p=registry.get(key);if(p==null){p=new PendingIntent();registry.put(key,p);p.intent=new Intent(i);}else if((flags&FLAG_UPDATE_CURRENT)!=0)p.intent=new Intent(i);return p;}public static PendingIntent getActivity(Context c,int code,Intent i,int flags){return get("activity",code,i,flags);}public static PendingIntent getService(Context c,int code,Intent i,int flags){return get("service",code,i,flags);}}''',
'android/app/Notification.java': '''package android.app;public final class Notification {public String title,text;public boolean ongoing;public final java.util.List<Action> actions=new java.util.ArrayList<>();public static final class Builder {private final Notification n=new Notification();public Builder(android.content.Context c,String id){}public Builder setSmallIcon(int i){return this;}public Builder setContentTitle(String s){n.title=s;return this;}public Builder setContentText(String s){n.text=s;return this;}public Builder setContentIntent(PendingIntent p){return this;}public Builder setOngoing(boolean v){n.ongoing=v;return this;}public Builder setOnlyAlertOnce(boolean v){return this;}public Builder addAction(Action a){n.actions.add(a);return this;}public Notification build(){return n;}}public static final class Action {public PendingIntent intent;public static final class Builder {private final Action a=new Action();public Builder(Object icon,String title,PendingIntent p){a.intent=p;}public Action build(){return a;}}}}''',
'io/airtap/preview/MainActivity.java': '''package io.airtap.preview;public final class MainActivity {}''',
'io/airtap/preview/SessionRecoveryGate.java': '''package io.airtap.preview;final class SessionRecoveryGate {static boolean operation=true,stopped;static boolean operationConfigured(android.content.Context c){return operation;}static boolean userStopped(android.content.Context c){return stopped;}}''',
'io/airtap/preview/PreviewService.java': '''package io.airtap.preview;public final class PreviewService extends android.content.Context {public static PreviewService instance;public boolean authorized,locked,lastStartFailure,blocked;public String fence;public int generation=1,stopCalls,suspends,readyCalls,carrierLosses,storageLosses,restores,interrupts,commands;public boolean notificationBlocked(){return blocked;}public boolean isAuthorized(){return authorized;}public int sessionGeneration(){return generation;}public String sessionFence(){return fence;}public boolean unlocked(){return power.interactive&&!locked;}private void suspend(){authorized=false;fence=null;generation++;SessionService.paused(this);}public void stoppedLocally(String expected){if(expected!=null&&expected.equals(fence)){stopCalls++;suspend();}}public void sessionCarrierUnavailable(){carrierLosses++;suspend();}public void storageUnavailable(){storageLosses++;suspend();SessionService.end(this);}public void restore(){restores++;}public void notificationUnavailable(int run,boolean failed){if(run!=generation||instance!=this)return;suspends++;lastStartFailure=failed;suspend();SessionService.end(this);}public void notificationReady(int run){if(run==generation&&authorized&&!blocked&&SessionService.readyFor(this,run))readyCalls++;}public void onInterrupt(){interrupts++;suspend();}public boolean tryCommand(){if(!authorized||blocked||!SessionService.readyFor(this,generation))return false;commands++;return true;}}''',
}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path)
    parser.add_argument('--source', type=Path, default=ROOT/'app/src/main/java/io/airtap/preview/SessionService.java')
    args = parser.parse_args()
    jdk = Path(os.environ.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home'))/'bin'
    with tempfile.TemporaryDirectory(prefix='airtap-session-screen-') as directory:
        temp = Path(directory)
        production = [args.source, PRODUCTION/'TrustedGrant.java', PRODUCTION/'ServiceProtocol.java']
        test = ROOT/'tests/SessionServiceTest.java'
        sources = [*production, test]
        for name, text in FAKES.items():
            path = temp/name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(text); sources.append(path)
        subprocess.run([str(jdk/'javac'), '--release', '17', '-d', str(temp), *map(str, sources)], check=True, timeout=30)
        result = subprocess.run([str(jdk/'java'), '-cp', str(temp), 'io.airtap.preview.SessionServiceTest'], text=True, capture_output=True, timeout=30)
    print(result.stdout, end='')
    if result.returncode: print(result.stderr, end='')
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps({'status':'passed' if result.returncode==0 else 'failed','recorded_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'passed': result.stdout.count('PASS '), 'failed':result.stderr.count('FAIL '), 'checks': result.stdout.splitlines(), 'errors':result.stderr.splitlines(),
            'source_sha256':{str(x.relative_to(ROOT)) if x.is_relative_to(ROOT) else str(x):hashlib.sha256(x.read_bytes()).hexdigest() for x in [*production,test,STORAGE_FAKES,Path(__file__)]},
            'scope': 'Complete production SessionService, TrustedGrant and ServiceProtocol; deterministic clock, wake-lock timeout, persisted synthetic grant and Android lifecycle fakes. Timeout resets model AOSP non-reference-counted acquire.',
            'limits':['SessionRecoveryGate prerequisites and PreviewService controller callbacks are explicit fakes; this does not validate system exit history, accessibility binding, relay checks or real device commands.',
                      'PendingIntent identity models component, action, data, kind and request code. Actual Android PendingIntent semantics require the separate native test.',
                      'stopSelf only records the request; tests invoke onDestroy explicitly. No claim about Android/OEM sticky restart timing or overnight recovery.']}, indent=2)+'\n')
    if result.returncode: raise SystemExit(result.returncode)

if __name__ == '__main__': main()
