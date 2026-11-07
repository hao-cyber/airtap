#!/usr/bin/env python3
"""Run the production recovery Activity and coordinator with synthetic Android windows/time."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/io/airtap/preview'
FAKES = {
'android/content/Context.java': '''package android.content;public class Context {
 public static final java.util.Map<Class<?>,Object> services=new java.util.HashMap<>();public static Intent launched;public static int launches;
 public <T>T getSystemService(Class<T> type){return type.cast(services.get(type));}public void startActivity(Intent i){launched=i;launches++;}}''',
'android/content/Intent.java': '''package android.content;public class Intent {
 public static final int FLAG_ACTIVITY_NEW_TASK=1,FLAG_ACTIVITY_MULTIPLE_TASK=2,FLAG_ACTIVITY_NO_HISTORY=4,FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS=8,FLAG_ACTIVITY_NO_ANIMATION=16;
 private final java.util.Map<String,String> extras=new java.util.HashMap<>();public Intent(Context c,Class<?> target){}
 public Intent putExtra(String name,String value){extras.put(name,value);return this;}public String getStringExtra(String name){return extras.get(name);}public Intent addFlags(int flags){return this;}}''',
'android/os/Bundle.java': 'package android.os;public class Bundle {}',
'android/os/Looper.java': 'package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java': '''package android.os;public class Handler {
 public static long now,serial;private record Work(long at,long order,Runnable call){}
 private static final java.util.PriorityQueue<Work> queue=new java.util.PriorityQueue<>(java.util.Comparator.comparingLong(Work::at).thenComparingLong(Work::order));
 public Handler(){}public Handler(Looper l){}public void post(Runnable r){postDelayed(r,0);}public void postDelayed(Runnable r,long delay){queue.add(new Work(now+delay,serial++,r));}
 public void removeCallbacks(Runnable r){queue.removeIf(w->w.call==r);}public static void reset(){now=serial=0;queue.clear();}
 public static void advance(long delta){long end=now+delta;int count=0;while(!queue.isEmpty()&&queue.peek().at<=end){if(++count>10000)throw new AssertionError("unbounded callbacks");Work w=queue.remove();now=w.at;w.call.run();}now=end;}
 public static int pending(){return queue.size();}}''',
'android/os/SystemClock.java': 'package android.os;public class SystemClock {public static long elapsedRealtime(){return Handler.now;}}',
'android/os/PowerManager.java': '''package android.os;public class PowerManager {
 public static final int SCREEN_BRIGHT_WAKE_LOCK=10,ACQUIRE_CAUSES_WAKEUP=0x10000000,ON_AFTER_RELEASE=0x20000000;
 public boolean interactive=true,rejectWake;public int wakes,releases;public long timeout;public WakeLock last;
 public boolean isInteractive(){return interactive;}
 public WakeLock newWakeLock(int flags,String tag){if(rejectWake)throw new SecurityException("synthetic-private-platform-detail");return last=new WakeLock();}
 public final class WakeLock {boolean held;public void setReferenceCounted(boolean value){}
  public void acquire(long millis){wakes++;timeout=millis;held=true;interactive=true;new Handler().postDelayed(()->held=false,millis);}
  public boolean isHeld(){return held;}public void release(){held=false;releases++;}}
}''',
'android/view/ViewTreeObserver.java': '''package android.view;public class ViewTreeObserver {
 public interface OnPreDrawListener {boolean onPreDraw();}private final java.util.List<OnPreDrawListener> listeners=new java.util.ArrayList<>();
 public boolean isAlive(){return true;}public void addOnPreDrawListener(OnPreDrawListener l){listeners.add(l);}public void removeOnPreDrawListener(OnPreDrawListener l){listeners.remove(l);}
 public void draw(){for(var l:java.util.List.copyOf(listeners))l.onPreDraw();}}''',
'android/view/View.java': '''package android.view;public class View {
 public static final int VISIBLE=0,INVISIBLE=4;public boolean attached,shown=true;public int windowVisibility=VISIBLE;
 private final ViewTreeObserver observer=new ViewTreeObserver();private final android.os.Handler handler=new android.os.Handler();
 public boolean isAttachedToWindow(){return attached;}public boolean isShown(){return shown;}public int getWindowVisibility(){return windowVisibility;}
 public ViewTreeObserver getViewTreeObserver(){return observer;}public boolean post(Runnable r){handler.post(r);return true;}public boolean removeCallbacks(Runnable r){handler.removeCallbacks(r);return true;}}''',
'android/view/Window.java': 'package android.view;public class Window {public final View decor=new View();public View getDecorView(){return decor;}}',
'android/app/Activity.java': '''package android.app;public class Activity extends android.content.Context {
 public android.content.Intent intent;public final android.view.Window window=new android.view.Window();public boolean finishing,destroyed,showLocked,turnOn;
 protected void onCreate(android.os.Bundle saved){}protected void onStart(){}protected void onResume(){}protected void onPause(){}protected void onStop(){}protected void onDestroy(){destroyed=true;}
 public void onWindowFocusChanged(boolean focus){}public android.content.Intent getIntent(){return intent;}public android.view.Window getWindow(){return window;}
 public void setTitle(String title){}public void setShowWhenLocked(boolean value){showLocked=value;}public void setTurnScreenOn(boolean value){turnOn=value;}
 public boolean isFinishing(){return finishing;}public boolean isDestroyed(){return destroyed;}
 public void finishAndRemoveTask(){finishing=true;new android.os.Handler().post(()->{onPause();onStop();onDestroy();});}}''',
'android/app/KeyguardManager.java': '''package android.app;public class KeyguardManager {
 public boolean locked=true,deviceLocked,throwsOnRequest,visibleIgnoringKeyguard=true;public int calls;public final java.util.List<KeyguardDismissCallback> callbacks=new java.util.ArrayList<>();
 public boolean isKeyguardLocked(){return locked;}public boolean isDeviceLocked(){return deviceLocked;}
 public static class KeyguardDismissCallback {public void onDismissError(){}public void onDismissCancelled(){}public void onDismissSucceeded(){}}
 public void requestDismissKeyguard(Activity activity,KeyguardDismissCallback callback){calls++;callbacks.add(callback);
  if(throwsOnRequest)throw new IllegalStateException("synthetic-private-platform-detail");
  if(!visibleIgnoringKeyguard)callback.onDismissError();}}''',
'android/util/Log.java': '''package android.util;public class Log {public static final java.util.List<String> lines=new java.util.ArrayList<>();
 public static int i(String tag,String line){lines.add(tag+" "+line);return 0;}}''',
'io/airtap/preview/CompanionUi.java': '''package io.airtap.preview;final class CompanionUi {int clears;boolean windowPresent;
 void clear(){clears++;}boolean hasRecoveryWindow(){return windowPresent;}}''',
'io/airtap/preview/PreviewService.java': '''package io.airtap.preview;final class PreviewService extends android.content.Context {
 static PreviewService instance;ScreenRecovery recovery;ScreenRecovery screenRecovery(){return recovery;}}''',
}
TEST = r'''package io.airtap.preview;
import android.app.KeyguardManager;import android.content.Context;import android.os.Handler;import android.os.PowerManager;import android.view.View;
public final class ScreenRecoveryActivityTest {
 static int passed;static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static final class Reply implements ScreenPreparation.Completion {int ready,errors;String error,detail;
  public void ready(boolean recovered){check(recovered,"recovery must be reported");ready++;}public void error(String code){errors++;error=code;}
  public void error(String code,String metadata){error(code);detail=metadata;}}
 static final class Host {
  final KeyguardManager keyguard=new KeyguardManager();final PowerManager power=new PowerManager();final CompanionUi ui=new CompanionUi();final Reply reply=new Reply();
  final PreviewService service=new PreviewService();ScreenRecoveryActivity activity;boolean current=true;
  Host(){Handler.reset();Context.services.clear();Context.launched=null;Context.launches=0;android.util.Log.lines.clear();Context.services.put(KeyguardManager.class,keyguard);Context.services.put(PowerManager.class,power);
   PreviewService.instance=service;service.recovery=new ScreenRecovery(service,ui);}
  void begin(){service.recovery.prepare(()->current,reply);check(Context.launched!=null,"recovery must launch");}
  void resume(){activity=new ScreenRecoveryActivity();activity.intent=Context.launched;activity.onCreate(null);activity.onStart();activity.onResume();}
  void draw(){activity.window.decor.attached=true;activity.window.decor.getViewTreeObserver().draw();Handler.advance(0);}
  void newWindow(){activity.onWindowFocusChanged(false);activity.onWindowFocusChanged(true);draw();}
  void success(){keyguard.callbacks.get(keyguard.callbacks.size()-1).onDismissSucceeded();keyguard.locked=false;Handler.advance(1000);}
 }
 interface Scenario {void run();}static void test(String name,Scenario scenario){scenario.run();passed++;System.out.println("PASS "+name);}
 public static void main(String[] args){
  test("a stopped rejected recovery window is replaced after verified wake",()->{
   Host h=new Host();h.power.interactive=false;h.begin();h.resume();h.draw();
   var old=h.activity;var oldCallback=h.keyguard.callbacks.get(0);old.onPause();old.onStop();oldCallback.onDismissError();
   Handler.advance(1500);check(Context.launches==1,"allow the awake lifecycle to resume before replacing it");
   Handler.advance(200);check(old.destroyed&&Context.launches==2,"replace stopped window once after observed wake");
   h.keyguard.visibleIgnoringKeyguard=true;h.resume();check(h.keyguard.calls==2,"new visible Activity gets the remaining shared dismissal");
   oldCallback.onDismissError();check(h.reply.errors==0,"old Activity cannot end the replacement attempt");
   h.success();check(h.reply.ready==1&&h.reply.errors==0&&h.power.releases==1,"actual ready state and removal finish recovery");
  });
  test("a recovered awake lifecycle keeps its original window",()->{
   Host h=new Host();h.power.interactive=false;h.begin();h.resume();h.draw();h.activity.onPause();h.activity.onStop();
   Handler.advance(1400);h.activity.onStart();h.activity.onResume();Handler.advance(300);
   check(Context.launches==1&&h.keyguard.calls==2,"normal resume must not create another Activity");h.success();
  });
  test("platform destruction of the no-history window is recoverable after native wake",()->{
   Host h=new Host();h.power.interactive=false;h.begin();h.resume();h.draw();var old=h.activity;
   old.onPause();old.onStop();old.onDestroy();Handler.advance(1700);
   check(Context.launches==2,"an attached then destroyed window must not strand a live attempt");
   h.resume();check(h.keyguard.calls==2,"replacement uses remaining shared dismissal");h.success();
   check(h.reply.ready==1&&h.reply.errors==0,"removed original window can recover");
  });
  test("a queued activity that has never attached is not mistaken for a destroyed one",()->{
   Host h=new Host();h.power.interactive=false;h.begin();Handler.advance(2000);
   check(Context.launches==1,"absence alone is not permission to replace an unobserved launch");
   h.resume();h.success();check(h.reply.ready==1,"late first Activity still uses original deadline");
  });
  test("wake replacement waits for old accessibility window to disappear",()->{
   Host h=new Host();h.power.interactive=false;h.begin();h.resume();h.draw();h.activity.onPause();h.activity.onStop();h.ui.windowPresent=true;
   Handler.advance(2000);check(h.activity.destroyed&&Context.launches==1,"old overlay must disappear before launch");
   h.ui.windowPresent=false;Handler.advance(100);check(Context.launches==2,"replacement starts when old window is gone");
   h.resume();h.success();check(h.reply.ready==1,"replacement can recover");
  });
  test("a second stopped window cannot reset the launch or time budget",()->{
   Host h=new Host();h.power.interactive=false;h.begin();h.resume();h.draw();h.activity.onPause();h.activity.onStop();Handler.advance(1700);
   check(Context.launches==2,"one replacement");h.resume();h.draw();h.activity.onPause();h.activity.onStop();Handler.advance(4300);
   check(Context.launches==2&&h.keyguard.calls==2&&h.reply.errors==1&&Handler.now==6000,"one deadline and shared native call cap");
   check(h.reply.detail.contains("launches=2")&&h.reply.detail.contains("resumed=false"),"failure retains final lifecycle");
  });
  test("authentication and command stop fence a pending awake replacement",()->{
   for(boolean auth:new boolean[]{true,false}){
    Host h=new Host();h.power.interactive=false;h.begin();h.resume();h.activity.onPause();h.activity.onStop();h.ui.windowPresent=true;
    Handler.advance(1700);check(Context.launches==1&&h.activity.destroyed,"old window closed but replacement not started");
    if(auth)h.keyguard.deviceLocked=true;else h.current=false;
    h.ui.windowPresent=false;Handler.advance(1000);
    check(Context.launches==1&&h.keyguard.calls==1&&h.reply.ready==0,"no new window or dismissal after invalidation");
    check(auth?"device_authentication_required".equals(h.reply.error):h.reply.errors==0,"accurate invalidation outcome");
   }
  });
  test("screen-off fallback wakes once and releases on cancel",()->{
   Host h=new Host();h.power.interactive=false;h.begin();Handler.advance(1100);
   check(h.power.wakes==0,"prefer Activity before fallback");Handler.advance(100);
   check(h.power.wakes==1&&h.power.timeout==4800,"one wake within the original deadline");
   h.service.recovery.cancel();check(h.power.releases==1&&!h.power.last.isHeld(),"cancel releases the wake hold");
   Handler.advance(10000);check(h.power.wakes==1&&h.reply.ready==0&&h.reply.errors==0,"cancel cannot resume");
  });
  test("native wake refusal keeps original bounded error and no private exception",()->{
   Host h=new Host();h.power.interactive=false;h.power.rejectWake=true;h.begin();Handler.advance(6000);
   check(h.reply.errors==1&&"device_wake_failed".equals(h.reply.error),"permission refusal does not crash or claim recovery");
   check(android.util.Log.lines.stream().noneMatch(s->s.contains("synthetic-private")),"fixed diagnostics only");
  });
  test("authentication and revoked command never use fallback",()->{
   Host h=new Host();h.power.interactive=false;h.begin();h.keyguard.deviceLocked=true;Handler.advance(1300);
   check(h.power.wakes==0&&"device_authentication_required".equals(h.reply.error),"authentication prevents wake");
   Host stopped=new Host();stopped.power.interactive=false;stopped.begin();stopped.current=false;Handler.advance(1300);
   check(stopped.power.wakes==0&&stopped.reply.errors==0,"revocation prevents wake and stale reply");
  });
  test("observed fallback wake retries pending dismissal on a reused drawn surface",()->{
   Host h=new Host();h.power.interactive=false;h.begin();h.resume();h.draw();h.activity.onPause();
   Handler.advance(1200);check(h.power.wakes==1&&h.keyguard.calls==1,"fallback only wakes before state verification");
   h.activity.onResume();Handler.advance(100);
   check(h.keyguard.calls==2,"screen-on transition permits one current dismissal without requiring a new frame");
   h.success();check(h.reply.ready==1&&h.power.releases==1,"verified ready releases native wake hold");
  });
  test("observed wake retries a pending pre-draw dismissal without waiting for a frame",()->{
   Host h=new Host();h.power.interactive=false;h.begin();h.resume();var first=h.keyguard.callbacks.get(0);
   Handler.advance(1400);
   check(h.power.wakes==1&&h.keyguard.calls==2,"an observed awake state permits the remaining native request without a draw dependency");
   first.onDismissError();check(h.reply.errors==0,"superseded sleeping callback is ignored");
   h.success();check(h.reply.ready==1&&h.reply.errors==0,"state polling, not a synthetic draw, verifies recovery");
  });
  test("awake bootstrap still requires resumed activity and current authentication state",()->{
   Host h=new Host();h.power.interactive=false;h.begin();h.resume();h.activity.onPause();Handler.advance(1400);
   check(h.keyguard.calls==1,"paused activity cannot use the new bootstrap");
   h.keyguard.deviceLocked=true;h.activity.onResume();Handler.advance(100);
   check(h.keyguard.calls==1&&"device_authentication_required".equals(h.reply.error),"authentication still ends the attempt before a second call");
  });
  test("timeout reports bounded current window state without replacing the last useful event",()->{
   Host h=new Host();h.power.interactive=false;h.begin();h.resume();Handler.advance(6000);
   check(h.keyguard.calls==2&&h.reply.errors==1,"one bounded post-wake retry only");
   check(h.reply.detail.length()<=500&&h.reply.detail.contains("drawn=false")&&h.reply.detail.contains("resumed=true")
    &&h.reply.detail.contains("last_event=dismiss_requested")&&!h.reply.detail.contains("synthetic-private"),"fixed current-attempt diagnostics fit the existing CLI bound");
  });
  test("resumed activity can bootstrap without a visible frame and cannot duplicate pending request",()->{
   Host h=new Host();h.begin();h.resume();check(h.keyguard.calls==1&&!h.activity.window.decor.attached,"platform checks visibility without requiring a frame");
   h.draw();check(h.keyguard.calls==1&&h.reply.errors==0,"draw does not duplicate bootstrap");
   h.draw();h.activity.onWindowFocusChanged(true);h.draw();check(h.keyguard.calls==1,"no duplicate dismiss while pending");
   h.success();check(h.reply.ready==1&&h.reply.errors==0&&!h.activity.turnOn&&!h.activity.showLocked,"verified recovery closes temporary window");
  });
  test("keyguard appearing between resume and first visible frame is not skipped",()->{
   Host h=new Host();h.begin();h.keyguard.locked=false;h.resume();h.keyguard.locked=true;h.draw();check(h.keyguard.calls==1,"unlocked resume must not consume request");
  });
  test("platform rejects hidden bootstrap and retry requires an actual visible transition",()->{
   Host h=new Host();h.begin();h.keyguard.visibleIgnoringKeyguard=false;h.resume();check(h.keyguard.calls==1,"one platform-checked bootstrap");
   h.activity.window.decor.windowVisibility=View.INVISIBLE;h.draw();check(h.keyguard.calls==1,"hidden frame cannot retry");
   h.keyguard.visibleIgnoringKeyguard=true;h.activity.window.decor.windowVisibility=View.VISIBLE;h.draw();check(h.keyguard.calls==2,"actual visible transition allows retry after explicit error");
  });
  test("API error retries only after a new ready window and at most once",()->{
   Host h=new Host();h.begin();h.resume();h.draw();var old=h.keyguard.callbacks.get(0);old.onDismissError();
   h.draw();h.activity.onWindowFocusChanged(false);h.draw();check(h.keyguard.calls==1&&h.reply.errors==0,"same frame or focus loss alone cannot loop");
   h.newWindow();check(h.keyguard.calls==2,"new ready signal allows one retry");old.onDismissError();check(h.reply.errors==0,"late old callback ignored");
   h.keyguard.callbacks.get(1).onDismissError();h.newWindow();check(h.keyguard.calls==2&&h.reply.errors==1,"second error ends attempt");
  });
  test("cancel is final even after another visible window",()->{
   Host h=new Host();h.begin();h.resume();h.draw();h.keyguard.callbacks.get(0).onDismissCancelled();h.newWindow();
   check(h.keyguard.calls==1&&h.reply.errors==1,"cancel must not retry");
  });
  test("retry shares original six second deadline",()->{
   Host h=new Host();h.begin();h.resume();h.draw();h.keyguard.callbacks.get(0).onDismissError();Handler.advance(5900);h.newWindow();
   check(h.keyguard.calls==2,"one retry inside original budget");Handler.advance(100);
   check(h.reply.errors==1&&"device_keyguard_dismiss_failed".equals(h.reply.error),"deadline not extended");h.newWindow();check(h.keyguard.calls==2,"no call after deadline");
  });
  test("authentication transition before resume never calls bootstrap",()->{
   Host h=new Host();h.begin();h.keyguard.deviceLocked=true;h.resume();h.draw();
   check(h.keyguard.calls==0&&"device_authentication_required".equals(h.reply.error),"system authentication must win");
  });
  test("authentication transition after error prohibits retry",()->{
   Host h=new Host();h.begin();h.resume();h.draw();h.keyguard.callbacks.get(0).onDismissError();h.keyguard.deviceLocked=true;h.newWindow();
   check(h.keyguard.calls==1&&"device_authentication_required".equals(h.reply.error),"new auth state is checked again");
  });
  test("stop or lost command before posted dismissal invalidates it",()->{
   Host h=new Host();h.begin();h.keyguard.locked=false;h.resume();h.keyguard.locked=true;h.activity.window.decor.attached=true;h.activity.window.decor.getViewTreeObserver().draw();h.current=false;Handler.advance(0);
   check(h.keyguard.calls==0&&h.reply.ready==0&&h.reply.errors==0&&!h.activity.turnOn,"queued call fenced by current command");
  });
  test("delayed first window after deadline cannot wake or dismiss",()->{
   Host h=new Host();h.begin();Handler.advance(6000);h.resume();h.draw();
   check(h.keyguard.calls==0&&h.reply.errors==1&&!h.activity.turnOn,"deadline fences late frame");
  });
  test("success callback alone and visible recovery overlay are not ready",()->{
   Host h=new Host();h.begin();h.resume();h.draw();h.keyguard.callbacks.get(0).onDismissSucceeded();Handler.advance(500);
   check(h.reply.ready==0,"callback alone cannot unlock");h.keyguard.locked=false;h.ui.windowPresent=true;Handler.advance(600);
   check(h.reply.ready==0,"overlay must disappear");h.ui.windowPresent=false;Handler.advance(100);check(h.reply.ready==0,"window disappearance must settle");
   Handler.advance(300);check(h.reply.ready==1,"actual state and window removal verified");
  });
  test("platform exception is final and diagnostics contain only fixed branches",()->{
   Host h=new Host();h.begin();h.keyguard.throwsOnRequest=true;h.resume();h.draw();h.newWindow();
   check(h.keyguard.calls==1&&h.reply.errors==1,"exception is not permission to repeat API");
   for(String line:android.util.Log.lines)check(line.matches("AirtapPreview event=screen_recovery branch=(window_not_ready|bootstrap_requested|dismiss_requested|api_success|api_error|api_cancelled|api_exception|timeout)"),"only fixed metadata");
   check(!android.util.Log.lines.isEmpty(),"diagnostic branch was recorded");
  });
  test("first drawn unlocked sample followed by relock progresses without another frame",()->{
   Host h=new Host();h.begin();h.keyguard.locked=false;h.resume();h.draw();check(h.keyguard.calls==0,"unlocked sample needs no API");
   Handler.advance(10);h.keyguard.locked=true;Handler.advance(190);
   check(h.keyguard.calls==1,"coordinator tick uses still-visible window without a new draw");h.success();check(h.reply.ready==1,"recovered");
  });
  test("relock during pre-close settling keeps the same activity and shared request budget",()->{
   Host h=new Host();h.begin();h.resume();h.draw();var old=h.keyguard.callbacks.get(0);old.onDismissSucceeded();h.keyguard.locked=false;
   Handler.advance(100);check(!h.activity.finishing,"first ready sample must not close");
   Handler.advance(50);h.keyguard.locked=true;Handler.advance(150);
   check(h.keyguard.calls==2&&!h.activity.finishing,"relock may dismiss once more in same attempt");
   old.onDismissError();check(h.reply.errors==0,"old outcome cannot affect new dismissal");h.success();check(h.reply.ready==1,"stable recovered result");
  });
  test("relock after close reopens once with the original deadline and global API cap",()->{
   Host h=new Host();h.begin();h.resume();h.draw();h.keyguard.callbacks.get(0).onDismissSucceeded();h.keyguard.locked=false;Handler.advance(400);
   var previous=h.activity;check(previous.destroyed,"old window closed after stable ready");
   Handler.advance(50);h.keyguard.locked=true;Handler.advance(50);h.resume();h.draw();check(h.keyguard.calls==2,"second Activity uses final shared request");
   previous.onWindowFocusChanged(true);previous.window.decor.getViewTreeObserver().draw();check(h.keyguard.calls==2,"old instance cannot dispatch");
   Handler.advance(6000-Handler.now);check(h.reply.errors==1&&h.keyguard.calls==2,"same original six second deadline");
  });
  test("authentication or owner stop while settling prevents another dismissal",()->{
   for(boolean auth:new boolean[]{true,false}){
    Host h=new Host();h.begin();h.resume();h.draw();h.keyguard.locked=false;Handler.advance(100);
    h.keyguard.locked=true;if(auth)h.keyguard.deviceLocked=true;else h.current=false;Handler.advance(1000);
    check(h.keyguard.calls==1&&h.reply.ready==0,"no retry across authentication or stop");
    check(auth?"device_authentication_required".equals(h.reply.error):h.reply.errors==0,"accurate terminal result");
   }
  });
  test("recreated Activity cannot reset the two-call budget",()->{
   Host h=new Host();h.begin();h.resume();h.draw();h.keyguard.callbacks.get(0).onDismissError();h.newWindow();check(h.keyguard.calls==2,"budget spent");
   h.activity.closeRecovery();Handler.advance(0);h.resume();h.draw();
   check(h.keyguard.calls==2&&h.reply.errors==1,"third request refused across recreation");
  });
  test("screen-off bootstrap delegates visibility to the platform but success still requires awake state",()->{
   Host h=new Host();h.power.interactive=false;h.begin();h.resume();
   check(h.keyguard.calls==1&&!h.activity.window.decor.attached,"bootstrap does not wait for an attached frame");
   h.keyguard.callbacks.get(0).onDismissSucceeded();h.keyguard.locked=false;Handler.advance(500);
   check(h.reply.ready==0,"successful callback without actual screen wake is not completion");
   h.power.interactive=true;Handler.advance(1000);check(h.reply.ready==1&&h.reply.errors==0,"actual awake unlocked state and window removal settle");
  });
  test("repeat resume cannot retry rejected bootstrap without a new visible window",()->{
   Host h=new Host();h.begin();h.keyguard.visibleIgnoringKeyguard=false;h.resume();
   h.activity.onResume();h.activity.onWindowFocusChanged(true);Handler.advance(300);h.activity.onResume();
   check(h.keyguard.calls==1&&h.reply.errors==0,"resume focus and time are not retry permission");
   h.keyguard.visibleIgnoringKeyguard=true;h.draw();check(h.keyguard.calls==2,"explicit error plus real ready frame permits second request");
   h.success();check(h.reply.ready==1,"second request can recover");
  });
  test("recreation cannot replay a bootstrap with an unknown result",()->{
   Host h=new Host();h.begin();h.resume();var old=h.activity;var callback=h.keyguard.callbacks.get(0);
   old.closeRecovery();Handler.advance(0);h.resume();h.draw();
   check(h.keyguard.calls==1,"new Activity and draw cannot replay pending platform request");
   callback.onDismissError();h.newWindow();check(h.keyguard.calls==1,"old instance cannot grant retry permission");
   Handler.advance(6000-Handler.now);check(h.reply.errors==1&&h.reply.ready==0,"unknown request remains bounded");
  });
  test("recreation after a known error still waits for a new real frame",()->{
   Host h=new Host();h.begin();h.keyguard.visibleIgnoringKeyguard=false;h.resume();
   h.activity.closeRecovery();Handler.advance(0);h.keyguard.visibleIgnoringKeyguard=true;h.resume();
   check(h.keyguard.calls==1,"bootstrap is shared across Activity instances");
   h.draw();check(h.keyguard.calls==2,"known failure plus new visible frame may retry");h.success();check(h.reply.ready==1,"recovery remains valid");
  });
  test("unlocked bootstrap samples do not spend the shared first request",()->{
   Host h=new Host();h.begin();h.keyguard.locked=false;h.resume();h.activity.onResume();check(h.keyguard.calls==0,"no dismissal needed");
   h.keyguard.locked=true;h.activity.onResume();h.activity.onResume();
   check(h.keyguard.calls==1,"first actual request happens once after keyguard appears");
  });
  test("owner stop before resume prevents both bootstrap and wake flags",()->{
   Host h=new Host();h.begin();h.current=false;h.resume();h.draw();
   check(h.keyguard.calls==0&&!h.activity.turnOn&&!h.activity.showLocked&&h.reply.ready==0&&h.reply.errors==0,"stale queued activity never wakes or requests");
  });
  System.out.println("TOTAL "+passed);
 }
}'''

p = argparse.ArgumentParser()
p.add_argument('--output', type=Path, required=True)
args = p.parse_args()
sources = [JAVA / (name + '.java') for name in ['ScreenPreparation', 'ScreenRecovery', 'ScreenRecoveryActivity']]
jdk = Path(os.environ.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home')) / 'bin'
with tempfile.TemporaryDirectory(prefix='airtap-screen-activity-') as directory:
    root = Path(directory)
    fixtures = {**FAKES, 'io/airtap/preview/ScreenRecoveryActivityTest.java': TEST}
    generated = []
    for name, body in fixtures.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body)
        generated.append(path)
    subprocess.run([str(jdk / 'javac'), '--release', '17', '-d', str(root), *map(str, sources), *map(str, generated)], check=True, timeout=30)
    run = subprocess.run([str(jdk / 'java'), '-cp', str(root), 'io.airtap.preview.ScreenRecoveryActivityTest'], capture_output=True, text=True, timeout=30)
checks = [line[5:] for line in run.stdout.splitlines() if line.startswith('PASS ')]
report = {'status': 'passed' if run.returncode == 0 else 'failed', 'passed': len(checks), 'checks': checks,
          'recorded_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'source_sha256': {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in [*sources, Path(__file__)]},
          'scope': 'Complete production ScreenRecoveryActivity, ScreenRecovery and ScreenPreparation with deterministic Android lifecycle/window/keyguard/time fakes. No real Android WindowManager, OEM lock screen, device or network validation.'}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, indent=2) + '\n')
print(run.stdout, end='')
if run.returncode:
    print(run.stderr, end='')
    raise SystemExit(run.returncode)
    assert len(checks) == 27
