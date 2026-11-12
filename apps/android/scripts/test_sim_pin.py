#!/usr/bin/env python3
"""Test production SIM keypad gating with synthetic Android adapters; never submits a live PIN."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
FAKES = {
    'android/os/Looper.java': 'package android.os;public class Looper{public static Looper getMainLooper(){return new Looper();}}',
    'android/os/SystemClock.java': 'package android.os;public class SystemClock{public static long now;public static long elapsedRealtime(){return now;}}',
    'android/os/Handler.java': '''package android.os;import java.util.*;public class Handler{
      public static final ArrayDeque<Runnable> queue=new ArrayDeque<>();public Handler(Looper l){}public void post(Runnable r){queue.add(r);}public void postDelayed(Runnable r,long d){queue.add(()->{SystemClock.now+=d;r.run();});}
      public static void next(){queue.remove().run();}public static void drain(){int n=0;while(!queue.isEmpty()){if(++n>200)throw new AssertionError();next();}}}''',
    'android/os/PowerManager.java': '''package android.os;public class PowerManager{public static final int SCREEN_BRIGHT_WAKE_LOCK=1,ACQUIRE_CAUSES_WAKEUP=2;public boolean interactive=true;public boolean isInteractive(){return interactive;}public WakeLock newWakeLock(int f,String t){return new WakeLock();}public class WakeLock{public void acquire(long ms){interactive=true;}}}''',
    'android/app/KeyguardManager.java': 'package android.app;public class KeyguardManager{public boolean secure,locked=true;public boolean isDeviceSecure(){return secure;}public boolean isKeyguardLocked(){return locked;}}',
    'android/telephony/TelephonyManager.java': '''package android.telephony;public class TelephonyManager{public static final int SIM_STATE_UNKNOWN=0,SIM_STATE_NOT_READY=6,SIM_STATE_PIN_REQUIRED=2,SIM_STATE_PUK_REQUIRED=3,SIM_STATE_READY=5;public int[] slots={2};public int getActiveModemCount(){return slots.length;}public int getSimState(int slot){return slots[slot];}}''',
    'android/content/SharedPreferences.java': '''package android.content;import java.util.*;public class SharedPreferences{final Map<String,Boolean> values=new HashMap<>();public boolean getBoolean(String k,boolean d){return values.getOrDefault(k,d);}public Editor edit(){return new Editor();}public class Editor{Map<String,Boolean> changes=new HashMap<>();public Editor putBoolean(String k,boolean v){changes.put(k,v);return this;}public boolean commit(){values.putAll(changes);return true;}}}''',
    'android/content/Context.java': '''package android.content;import java.util.*;public class Context{public final Map<Class<?>,Object> systems=new HashMap<>();private final SharedPreferences preferences=new SharedPreferences();public <T>T getSystemService(Class<T> c){return c.cast(systems.get(c));}public SharedPreferences getSharedPreferences(String n,int m){return preferences;}}''',
    'android/view/accessibility/AccessibilityNodeInfo.java': '''package android.view.accessibility;import java.util.*;public class AccessibilityNodeInfo{
      public static final int ACTION_CLICK=16;public String text="",pkg="com.android.systemui";public int window=7;public boolean visible=true,enabled=true,clickable=true;public Runnable click=()->{};public final Map<String,AccessibilityNodeInfo> ids=new HashMap<>();
      public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByViewId(String id){return ids.containsKey(id)?List.of(ids.get(id)):List.of();}public boolean isVisibleToUser(){return visible;}public boolean isEnabled(){return enabled;}public boolean isClickable(){return clickable;}public CharSequence getText(){return text;}public CharSequence getPackageName(){return pkg;}public int getWindowId(){return window;}public boolean performAction(int a){click.run();return true;}}''',
    'android/accessibilityservice/AccessibilityService.java': 'package android.accessibilityservice;public class AccessibilityService extends android.content.Context{public android.view.accessibility.AccessibilityNodeInfo root;public android.view.accessibility.AccessibilityNodeInfo getRootInActiveWindow(){return root;}}',
    'org/json/JSONObject.java': '''package org.json;import java.util.*;public class JSONObject{public static final Object NULL=new Object();public Object get(String k){return values.get(k);}final Map<String,Object> values=new HashMap<>();public JSONObject put(String k,Object v){values.put(k,v);return this;}public String getString(String k){return (String)values.get(k);}public Object remove(String k){return values.remove(k);}public boolean has(String k){return values.containsKey(k);}}''',
    'io/airtap/preview/ScopedUi.java': 'package io.airtap.preview;final class ScopedUi{static class Refused extends RuntimeException{final String code;Refused(String c){super(c);code=c;}}}',
    'io/airtap/preview/RelayApi.java': 'package io.airtap.preview;final class RelayApi{static org.json.JSONObject json(Object...a){var r=new org.json.JSONObject();for(int i=0;i<a.length;i+=2)r.put((String)a[i],a[i+1]);return r;}}',
    'io/airtap/preview/EnrollmentStore.java': 'package io.airtap.preview;final class EnrollmentStore{static String token(){return java.util.UUID.randomUUID().toString().replace("-","");}}',
    'io/airtap/preview/CompanionUi.java': 'package io.airtap.preview;final class CompanionUi{interface Reply{void success(org.json.JSONObject r);void error(String c);}}',
}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    jdk = Path(os.environ.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home')) / 'bin'
    with tempfile.TemporaryDirectory(prefix='airtap-sim-fixture-') as temporary:
        directory = Path(temporary)
        for name, source in FAKES.items():
            target = directory / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(source)
        sources = list(directory.rglob('*.java')) + [ROOT/'app/src/main/java/io/airtap/preview/SimPinInput.java', ROOT/'tests/SimPinInputTest.java']
        subprocess.run([str(jdk/'javac'), '--release', '17', '-d', temporary, *map(str, sources)], check=True)
        result = subprocess.run([str(jdk/'java'), '-cp', temporary, 'io.airtap.preview.SimPinInputTest'], capture_output=True, text=True, timeout=30)
        print(result.stdout, end='')
        if result.returncode:
            print(result.stderr, end='')
            raise SystemExit(result.returncode)
        checks = [line[5:] for line in result.stdout.splitlines() if line.startswith('PASS ')]
        assert result.stdout.splitlines()[-1] == 'TOTAL ' + str(len(checks))
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps({'passed':len(checks), 'tests':checks, 'limitations':['Synthetic keypad and telephony adapters; not a live SIM, OEM keypad, correct physical-card PIN or system-password acceptance.']}, indent=2)+'\n')

if __name__ == '__main__':
    main()
