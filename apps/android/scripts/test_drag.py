#!/usr/bin/env python3
"""Test production touch planning/execution; fake only Android's pointer dispatch contract."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/io/airtap/preview'
FAKES = {
'org/json/JSONObject.java': '''package org.json; import java.util.*;
public class JSONObject {private final Map<String,Object> v=new HashMap<>();public JSONObject put(String k,Object x){v.put(k,x);return this;}public Object get(String k){if(!v.containsKey(k))throw new IllegalArgumentException();return v.get(k);}public int length(){return v.size();}public boolean has(String k){return v.containsKey(k);}public JSONArray getJSONArray(String k){Object x=get(k);if(!(x instanceof JSONArray))throw new IllegalArgumentException();return (JSONArray)x;}}
''',
'org/json/JSONArray.java': '''package org.json; import java.util.*;
public class JSONArray {private final List<Object> v=new ArrayList<>();public JSONArray put(Object x){v.add(x);return this;}public int length(){return v.size();}public JSONObject getJSONObject(int i){Object x=v.get(i);if(!(x instanceof JSONObject))throw new IllegalArgumentException();return (JSONObject)x;}}
''',
'android/graphics/Rect.java': '''package android.graphics;public class Rect {public int left,top,right,bottom;public Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}public Rect(Rect r){this(r.left,r.top,r.right,r.bottom);}public int width(){return right-left;}public int height(){return bottom-top;}}''',
'android/graphics/Path.java': '''package android.graphics;public class Path {public float startX,startY,endX,endY;public void moveTo(float x,float y){startX=endX=x;startY=endY=y;}public void lineTo(float x,float y){endX=x;endY=y;}}''',
'android/os/Looper.java': '''package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}''',
'android/os/SystemClock.java': '''package android.os;public class SystemClock {public static long now;public static long elapsedRealtime(){return now;}public static long uptimeMillis(){return now;}}''',
'android/os/Handler.java': '''package android.os;import java.util.*;public class Handler {private record Task(long at,Runnable action){}private static final List<Task> tasks=new ArrayList<>();public Handler(Looper l){}public void postDelayed(Runnable r,long delay){tasks.add(new Task(SystemClock.now+delay,r));}public void removeCallbacks(Runnable r){tasks.removeIf(t->t.action==r);}public static boolean pending(){return !tasks.isEmpty();}public static void next(){tasks.sort(Comparator.comparingLong(Task::at));Task t=tasks.remove(0);SystemClock.now=t.at;t.action.run();}public static void reset(){tasks.clear();SystemClock.now=0;}}''',
'android/accessibilityservice/GestureDescription.java': '''package android.accessibilityservice;import android.graphics.Path;import java.util.*;
public class GestureDescription {public final List<StrokeDescription> strokes=new ArrayList<>();public static int getMaxStrokeCount(){return 20;}public static class Builder {private final GestureDescription g=new GestureDescription();public Builder addStroke(StrokeDescription s){if(g.strokes.size()>=getMaxStrokeCount())throw new IllegalArgumentException();g.strokes.add(s);return this;}public GestureDescription build(){if(g.strokes.isEmpty())throw new IllegalArgumentException("empty gesture");return g;}}
public static class StrokeDescription {private static int serial;public final int id=++serial,root;public final Path path;public final long start,duration;public final boolean more;public final StrokeDescription parent;
public StrokeDescription(Path p,long start,long d,boolean m){this(p,start,d,m,null);}private StrokeDescription(Path p,long start,long d,boolean m,StrokeDescription parent){if(start<0||d<=0)throw new IllegalArgumentException();path=p;this.start=start;duration=d;more=m;this.parent=parent;root=parent==null?id:parent.root;}
public boolean willContinue(){return more;}public StrokeDescription continueStroke(Path p,long start,long d,boolean m){if(!more||path.endX!=p.startX||path.endY!=p.startY)throw new AssertionError("broken pointer continuation");return new StrokeDescription(p,start,d,m,this);}}}
''',
'android/accessibilityservice/AccessibilityService.java': '''package android.accessibilityservice;import java.util.*;import android.os.SystemClock;
public class AccessibilityService {public static class GestureResultCallback {public void onCompleted(GestureDescription g){}public void onCancelled(GestureDescription g){}}
public record Event(String action,int pointer,long at,float x,float y){}public record Segment(long at,GestureDescription.StrokeDescription stroke){}
public final List<GestureDescription.StrokeDescription> history=new ArrayList<>();public final List<GestureDescription> gestures=new ArrayList<>();public final List<Event> events=new ArrayList<>();public final List<Segment> segments=new ArrayList<>();
private final Set<GestureDescription.StrokeDescription> active=new HashSet<>();public GestureDescription pending;private GestureResultCallback callback;private boolean invalid,empty;private long began,lastEvent;
public int downs,ups,calls,rejectAt,maxDown;public boolean down;public float x,y;
public boolean dispatchGesture(GestureDescription g,GestureResultCallback c,Object handler){calls++;if(calls==rejectAt)return false;if(pending!=null)throw new AssertionError("overlapping dispatch");Set<GestureDescription.StrokeDescription> continued=new HashSet<>();int newPointers=0;invalid=false;empty=true;began=SystemClock.now;lastEvent=0;
for(var s:g.strokes){if(s.parent==null){newPointers++;if(!active.isEmpty()&&s.start==0)invalid=true;}else if(!active.contains(s.parent)||!continued.add(s.parent)||s.path.startX!=s.parent.path.endX||s.path.startY!=s.parent.path.endY)invalid=true;}
if(!continued.equals(active))invalid=true;
if(!invalid){for(var s:g.strokes){segments.add(new Segment(began,s));if(s.parent==null){downs++;events.add(new Event("DOWN",s.root,began+s.start,s.path.startX,s.path.startY));empty=false;lastEvent=Math.max(lastEvent,s.start);}if(s.path.startX!=s.path.endX||s.path.startY!=s.path.endY||!s.more){empty=false;lastEvent=Math.max(lastEvent,s.start+s.duration);}}maxDown=Math.max(maxDown,active.size()+newPointers);down=!g.strokes.isEmpty();}
history.addAll(g.strokes);gestures.add(g);pending=g;callback=c;return true;}
public void complete(){if(pending==null)throw new AssertionError("nothing in flight");var g=pending;var cb=callback;pending=null;
if(invalid){active.clear();down=false;cb.onCancelled(g);return;}
SystemClock.now=began+lastEvent;active.clear();for(var s:g.strokes){x=s.path.endX;y=s.path.endY;if(s.more)active.add(s);else {ups++;events.add(new Event("UP",s.root,SystemClock.now,x,y));}}down=!active.isEmpty();
// AOSP's empty continuation reports false but retains the remapped held pointers.
if(empty)cb.onCancelled(g);else cb.onCompleted(g);}
public void cancelByUser(){var g=pending;var cb=callback;pending=null;active.clear();down=false;if(g!=null)cb.onCancelled(g);}}
''',
}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    jdk = Path(os.environ.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home')) / 'bin'
    with tempfile.TemporaryDirectory(prefix='airtap-drag-') as directory:
        temp = Path(directory)
        sources = [JAVA / 'DragPlan.java', JAVA / 'GesturePlan.java', JAVA / 'TouchGesture.java', ROOT / 'tests/DragGestureTest.java', ROOT / 'tests/TouchGestureTest.java']
        for name, value in FAKES.items():
            path = temp / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(value); sources.append(path)
        subprocess.run([str(jdk / 'javac'), '--release', '17', '-d', str(temp), *map(str, sources)], check=True, timeout=30)
        output = ''
        for test in ('DragGestureTest', 'TouchGestureTest'):
            result = subprocess.run([str(jdk / 'java'), '-cp', str(temp), 'io.airtap.preview.' + test], text=True, capture_output=True, timeout=30)
            if result.returncode:
                raise SystemExit(result.stdout + result.stderr)
            output += result.stdout
    print(output, end='')
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps({'scope': 'Production Java plan/executor with explicit Android pointer-contract fakes; no physical gesture or OEM acceptance claim.',
                                          'passed': output.count('PASS '), 'checks': output.splitlines()}, indent=2) + '\n')

if __name__ == '__main__': main()
