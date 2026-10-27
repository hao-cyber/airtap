#!/usr/bin/env python3
"""Run the complete production SessionService with deterministic Android lifecycle/time fakes."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
FAKES = {
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
'android/content/Intent.java': '''package android.content;import java.util.*;public final class Intent {private String action;private final Map<String,Integer> extras=new HashMap<>();public Intent(Context c,Class<?> type){}public Intent putExtra(String name,int value){extras.put(name,value);return this;}public int getIntExtra(String name,int fallback){return extras.getOrDefault(name,fallback);}public Intent setAction(String value){action=value;return this;}public String getAction(){return action;}}''',
'android/content/Context.java': '''package android.content;public class Context {public static android.os.PowerManager power;public static android.app.NotificationManager notifications;public static int stops,foregroundStarts;public static boolean startFails,permission=true;public <T>T getSystemService(Class<T> type){return type.cast(type==android.os.PowerManager.class?power:notifications);}public int checkSelfPermission(String p){return permission?0:-1;}public void startForegroundService(Intent i){foregroundStarts++;if(startFails)throw new IllegalStateException("synthetic startup refusal");}public void stopService(Intent i){stops++;}}''',
'android/app/Service.java': '''package android.app;public class Service extends android.content.Context {public static final int START_NOT_STICKY=2;public int stopped;public static boolean foregroundFails;public static int foregroundCalls;public void onCreate(){}public void onDestroy(){}public int onStartCommand(android.content.Intent i,int flags,int id){return 2;}public android.os.IBinder onBind(android.content.Intent i){return null;}public void stopSelf(){stopped++;}public void stopSelf(int id){stopped++;}public void startForeground(int id,Notification n,int type){foregroundCalls++;if(foregroundFails)throw new IllegalStateException("synthetic notification refusal");}}''',
'android/app/NotificationChannel.java': '''package android.app;public final class NotificationChannel {private final int importance;public NotificationChannel(String id,String name,int importance){this.importance=importance;}public int getImportance(){return importance;}}''',
'android/app/NotificationManager.java': '''package android.app;public final class NotificationManager {public static final int IMPORTANCE_NONE=0,IMPORTANCE_LOW=2;public boolean enabled=true;private NotificationChannel channel;public NotificationChannel getNotificationChannel(String id){return channel;}public boolean areNotificationsEnabled(){return enabled;}public void createNotificationChannel(NotificationChannel c){if(channel==null)channel=c;}}''',
'android/app/PendingIntent.java': '''package android.app;public final class PendingIntent {public static final int FLAG_UPDATE_CURRENT=1,FLAG_IMMUTABLE=2;public static PendingIntent getActivity(android.content.Context c,int code,android.content.Intent i,int flags){return new PendingIntent();}public static PendingIntent getService(android.content.Context c,int code,android.content.Intent i,int flags){return new PendingIntent();}}''',
'android/app/Notification.java': '''package android.app;public final class Notification {public static final class Builder {public Builder(android.content.Context c,String id){}public Builder setSmallIcon(int i){return this;}public Builder setContentTitle(String s){return this;}public Builder setContentText(String s){return this;}public Builder setContentIntent(PendingIntent p){return this;}public Builder setOngoing(boolean v){return this;}public Builder setOnlyAlertOnce(boolean v){return this;}public Builder addAction(Action a){return this;}public Notification build(){return new Notification();}}public static final class Action {public static final class Builder {public Builder(Object icon,String title,PendingIntent p){}public Action build(){return new Action();}}}}''',
'io/airtap/preview/MainActivity.java': '''package io.airtap.preview;public final class MainActivity {}''',
'io/airtap/preview/PreviewService.java': '''package io.airtap.preview;public final class PreviewService extends android.content.Context {public static PreviewService instance;public boolean authorized=true,locked;public int generation=1,stopCalls,suspends,readyCalls;public boolean consent=true,lastStartFailure,blocked;public boolean notificationBlocked(){return blocked;}public boolean isAuthorized(){return authorized;}public int sessionGeneration(){return generation;}public boolean unlocked(){return power.interactive&&!locked;}public void stop(String message){consent=false;authorized=false;generation++;stopCalls++;SessionService.end(this);}public void notificationUnavailable(int run,boolean failed){if(run!=generation)return;authorized=false;generation++;suspends++;lastStartFailure=failed;SessionService.end(this);}public void notificationReady(int run){if(run==generation&&SessionService.readyFor(this,run))readyCalls++;}public void onInterrupt(){authorized=false;}}''',
}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path)
    parser.add_argument('--source', type=Path, default=ROOT/'app/src/main/java/io/airtap/preview/SessionService.java')
    args = parser.parse_args()
    jdk = Path(os.environ.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home'))/'bin'
    with tempfile.TemporaryDirectory(prefix='airtap-session-screen-') as directory:
        temp = Path(directory)
        sources = [args.source, ROOT/'tests/SessionServiceTest.java']
        for name, text in FAKES.items():
            path = temp/name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(text); sources.append(path)
        subprocess.run([str(jdk/'javac'), '--release', '17', '-d', str(temp), *map(str, sources)], check=True, timeout=30)
        result = subprocess.run([str(jdk/'java'), '-cp', str(temp), 'io.airtap.preview.SessionServiceTest'], text=True, capture_output=True, timeout=30)
    print(result.stdout, end='')
    if result.returncode:
        print(result.stderr, end=''); raise SystemExit(result.returncode)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps({'passed': result.stdout.count('PASS '), 'checks': result.stdout.splitlines(),
            'scope': 'Complete production SessionService; deterministic clock, wake-lock timeout and Android lifecycle fakes. Timeout resets model AOSP non-reference-counted acquire. No physical OEM claim.'}, indent=2)+'\n')

if __name__ == '__main__': main()
