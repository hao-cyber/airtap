#!/usr/bin/env python3
"""Run production window selection with explicit Android metadata fakes, not a device."""
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
public class JSONObject {public static final Object NULL=new Object(){public String toString(){return "null";}};private final Map<String,Object> v=new LinkedHashMap<>();public JSONObject put(String k,Object x){v.put(k,x);return this;}public Object get(String k){if(!v.containsKey(k))throw new IllegalArgumentException(k);return v.get(k);}public boolean has(String k){return v.containsKey(k);}public String toString(){return v.toString();}}
''',
'org/json/JSONArray.java': '''package org.json; import java.util.*;
public class JSONArray {private final List<Object> v=new ArrayList<>();public JSONArray put(Object x){v.add(x);return this;}public int length(){return v.size();}public Object get(int i){return v.get(i);}public String toString(){return v.toString();}}
''',
'android/graphics/Rect.java': '''package android.graphics;public class Rect {public int left,top,right,bottom;}''',
'android/graphics/Point.java': '''package android.graphics;public class Point {public int x,y;}''',
'android/util/SparseArray.java': '''package android.util;import java.util.*;public class SparseArray<T>{private final List<T> values=new ArrayList<>();public void add(T x){values.add(x);}public int size(){return values.size();}public T valueAt(int i){return values.get(i);}}''',
'android/view/Display.java': '''package android.view;import android.graphics.Point;public class Display {public static final int DEFAULT_DISPLAY=0;private final int id;public Display(int id){this.id=id;}public int getDisplayId(){return id;}public void getRealSize(Point p){p.x=1080;p.y=2400;}public int getRotation(){return 1;}public int getState(){return 2;}}''',
'android/hardware/display/DisplayManager.java': '''package android.hardware.display;import android.view.Display;public class DisplayManager {public Display[] getDisplays(){return new Display[]{new Display(0),new Display(5)};}}''',
'android/view/accessibility/AccessibilityNodeInfo.java': '''package android.view.accessibility;public class AccessibilityNodeInfo {private final String pkg;private final int id;public AccessibilityNodeInfo(String pkg,int id){this.pkg=pkg;this.id=id;}public int getWindowId(){return id;}public CharSequence getPackageName(){return pkg;}public CharSequence getText(){throw new AssertionError("must not read private content");}}''',
'android/view/accessibility/AccessibilityWindowInfo.java': '''package android.view.accessibility;import android.graphics.Rect;
public class AccessibilityWindowInfo {public static final int TYPE_APPLICATION=1,TYPE_INPUT_METHOD=2,TYPE_SYSTEM=3,TYPE_ACCESSIBILITY_OVERLAY=4,TYPE_SPLIT_SCREEN_DIVIDER=5;public int id,display,type=TYPE_APPLICATION,reads;public boolean active,focused,fail;public AccessibilityNodeInfo root;
public AccessibilityWindowInfo(int id,int display,boolean active,boolean focused,AccessibilityNodeInfo root){this.id=id;this.display=display;this.active=active;this.focused=focused;this.root=root;}public int getId(){return id;}public int getDisplayId(){return display;}public boolean isActive(){return active;}public boolean isFocused(){return focused;}public int getType(){return type;}public int getLayer(){return id;}public AccessibilityNodeInfo getRoot(){reads++;if(fail)throw new IllegalStateException();return root;}public void getBoundsInScreen(Rect r){r.left=0;r.top=40;r.right=1080;r.bottom=2200;}public CharSequence getTitle(){throw new AssertionError("must not read private title/ticket");}}
''',
'android/accessibilityservice/AccessibilityService.java': '''package android.accessibilityservice;import java.util.*;import android.util.SparseArray;import android.view.accessibility.*;import android.hardware.display.DisplayManager;
public class AccessibilityService {public AccessibilityNodeInfo active;public final SparseArray<List<AccessibilityWindowInfo>> windows=new SparseArray<>();public boolean fail,noManager;public AccessibilityService(AccessibilityNodeInfo root,AccessibilityWindowInfo... windows){active=root;this.windows.add(Arrays.asList(windows));}public AccessibilityNodeInfo getRootInActiveWindow(){if(fail)throw new IllegalStateException();return active;}public SparseArray<List<AccessibilityWindowInfo>> getWindowsOnAllDisplays(){return windows;}public <T>T getSystemService(Class<T> type){return noManager?null:type.cast(new DisplayManager());}}
''',
'io/airtap/preview/BuildConfig.java': '''package io.airtap.preview;final class BuildConfig {static final String APPLICATION_ID="io.airtap.preview.dev";}''',
'io/airtap/preview/ScopedUi.java': '''package io.airtap.preview;final class ScopedUi {static final class Refused extends RuntimeException {final String code;Refused(String code){this.code=code;}}}''',
'io/airtap/preview/RelayApi.java': '''package io.airtap.preview;import org.json.JSONObject;final class RelayApi {static JSONObject json(Object... pairs){JSONObject value=new JSONObject();for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);return value;}}''',
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    jdk = Path(os.environ.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home')) / 'bin'
    # Compile the actual allow-list predicate without mocking its behavior or the unrelated package manager.
    catalog = (JAVA / 'AppCatalog.java').read_text()
    allowed = catalog[catalog.index('    static boolean allowed('):catalog.index('    static List<Entry> installed(')]
    fakes = {**FAKES, 'io/airtap/preview/AppCatalog.java': 'package io.airtap.preview;final class AppCatalog {\n' + allowed + '}'}
    with tempfile.TemporaryDirectory(prefix='airtap-windows-') as directory:
        temp = Path(directory)
        sources = [JAVA / 'WindowInventory.java', ROOT / 'tests/WindowInventoryTest.java']
        for name, value in fakes.items():
            path = temp / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(value); sources.append(path)
        subprocess.run([str(jdk / 'javac'), '--release', '17', '-d', str(temp), *map(str, sources)], check=True, timeout=30)
        result = subprocess.run([str(jdk / 'java'), '-cp', str(temp), 'io.airtap.preview.WindowInventoryTest'], check=True, text=True, capture_output=True, timeout=30)
    print(result.stdout, end='')
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps({'scope': 'Production Java window inventory/selection plus actual AppCatalog predicate; explicit Android metadata fakes. No physical installer or OEM acceptance claim.',
                                          'passed': result.stdout.count('PASS '), 'checks': result.stdout.splitlines()}, indent=2) + '\n')


if __name__ == '__main__': main()
