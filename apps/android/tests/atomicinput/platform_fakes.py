"""Explicit input/Android boundary fixtures; these do not emulate an Android editor."""
SOURCES = {'org/json/JSONObject.java': 'package org.json; import java.util.*; public class JSONObject {public static final Object NULL=new Object();public final Map<String,Object> fields=new '
                             'HashMap<>(); public JSONObject put(String k,Object v){fields.put(k,v);return this;} public Object get(String '
                             'k){if(!fields.containsKey(k))throw new IllegalArgumentException(k);return fields.get(k);} public String '
                             'getString(String k){return (String)get(k);}}',
 'org/json/JSONArray.java': 'package org.json; import java.util.*; public class JSONArray {public final List<Object> values=new '
                            'ArrayList<>();public JSONArray put(Object x){values.add(x);return this;}public int length(){return '
                            'values.size();}public String optString(int i){return String.valueOf(values.get(i));}}',
 'android/os/Bundle.java': 'package android.os; import java.util.*;public class Bundle {public final Map<String,Integer> values=new '
                           'HashMap<>();public void putInt(String k,int v){values.put(k,v);}}',
 'android/R.java': 'package android;public class R {public static class id {public static final int selectAll=1,copy=2,cut=3,paste=4;}}',
 'android/view/inputmethod/EditorInfo.java': 'package android.view.inputmethod;public class EditorInfo {public String '
                                             'packageName="com.example.app";public int fieldId=100,initialSelStart=0,initialSelEnd=0;public int '
                                             'actionId=0,imeOptions=IME_ACTION_SEARCH;public static final int '
                                             'IME_ACTION_UNSPECIFIED=0,IME_ACTION_NONE=1,IME_ACTION_GO=2,IME_ACTION_SEARCH=3,IME_ACTION_SEND=4,IME_ACTION_NEXT=5,IME_ACTION_DONE=6,IME_ACTION_PREVIOUS=7,IME_MASK_ACTION=255;}',
 'android/accessibilityservice/InputMethod.java': 'package android.accessibilityservice;import android.view.inputmethod.EditorInfo;public class '
                                                  'InputMethod {public InputMethod(){}public InputMethod(AccessibilityService service){}public '
                                                  'void onStartInput(EditorInfo info,boolean restarting){}public void onFinishInput(){}public '
                                                  'void onUpdateSelection(int a,int b,int c,int d,int e,int f){}public boolean '
                                                  'started=true;public EditorInfo info=new EditorInfo();public AccessibilityInputConnection '
                                                  'connection=new AccessibilityInputConnection();public boolean getCurrentInputStarted(){return '
                                                  'started;}public EditorInfo getCurrentInputEditorInfo(){return info;}public '
                                                  'AccessibilityInputConnection getCurrentInputConnection(){return connection;}public static '
                                                  'final class AccessibilityInputConnection {public int '
                                                  'calls,action,start,end,before,after;public String text;public void setSelection(int a,int '
                                                  'b){calls++;start=a;end=b;}public void performContextMenuAction(int '
                                                  'id){calls++;action=id;}public void commitText(CharSequence value,int cursor,Object '
                                                  'attribute){calls++;text=value.toString();if(cursor!=1||attribute!=null)throw new '
                                                  'AssertionError();}public void deleteSurroundingText(int a,int '
                                                  'b){calls++;before=a;after=b;}public void performEditorAction(int id){calls++;action=id;}}}',
 'android/view/accessibility/AccessibilityNodeInfo.java': 'package android.view.accessibility;import java.util.*;import '
                                                          'android.os.Bundle;public class AccessibilityNodeInfo {private static int '
                                                          'serial;private int identity=++serial;public String '
                                                          'viewId="com.example.app:id/first";public AccessibilityNodeInfo(){}public '
                                                          'AccessibilityNodeInfo(AccessibilityNodeInfo '
                                                          'source){identity=source.identity;window=source.window;packageName=source.packageName;viewId=source.viewId;}public '
                                                          'List<AccessibilityNodeInfo> searchResults=null;public List<AccessibilityNodeInfo> '
                                                          'findAccessibilityNodeInfosByViewId(String name){if(searchResults!=null)return '
                                                          'searchResults;var found=new '
                                                          'ArrayList<AccessibilityNodeInfo>();if(name.equals(viewId))found.add(this);if(focus!=null&&!focus.equals(this)&&name.equals(focus.viewId))found.add(focus);return '
                                                          'found;}public boolean equals(Object o){return o instanceof AccessibilityNodeInfo '
                                                          'n&&n.identity==identity;}public int hashCode(){return identity;}public String '
                                                          'getViewIdResourceName(){return viewId;}public boolean isVisibleToUser(){return '
                                                          'true;}public boolean isEnabled(){return true;}public boolean '
                                                          'editable=true,focused=true,accept=true;public int window=7,calls,operation;public '
                                                          'String packageName="com.example.app";public Bundle arguments;public '
                                                          'AccessibilityNodeInfo focus=this;public List<AccessibilityAction> actions=new '
                                                          'ArrayList<>();public boolean isEditable(){return editable;}public boolean '
                                                          'isFocused(){return focused;}public int getWindowId(){return window;}public '
                                                          'CharSequence getPackageName(){return packageName;}public AccessibilityNodeInfo '
                                                          'findFocus(int kind){return focus;}public List<AccessibilityAction> '
                                                          'getActionList(){return actions;}public boolean performAction(int id,Bundle '
                                                          'args){calls++;operation=id;arguments=args;return accept;}public static final int '
                                                          'ACTION_FOCUS=1,ACTION_SET_SELECTION=2,ACTION_COPY=3,ACTION_CUT=4,ACTION_PASTE=5,FOCUS_INPUT=1;public '
                                                          'static final String '
                                                          'ACTION_ARGUMENT_SELECTION_START_INT="start",ACTION_ARGUMENT_SELECTION_END_INT="end";public '
                                                          'static class AccessibilityAction {public static final AccessibilityAction '
                                                          'ACTION_IME_ENTER=new AccessibilityAction(6);final int id;public '
                                                          'AccessibilityAction(int id){this.id=id;}public int getId(){return id;}}}',
 'android/accessibilityservice/AccessibilityService.java': 'package android.accessibilityservice;import java.util.*;import '
                                                           'android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;public class '
                                                           'AccessibilityService {public android.view.accessibility.AccessibilityNodeInfo '
                                                           'root;public android.view.accessibility.AccessibilityNodeInfo '
                                                           'getRootInActiveWindow(){return root;}public android.content.pm.PackageManager '
                                                           'getPackageManager(){return new android.content.pm.PackageManager();}public '
                                                           'InputMethod input=new InputMethod();public int calls,last;public boolean '
                                                           'accept=true;public List<AccessibilityAction> actions=new ArrayList<>();public '
                                                           'InputMethod getInputMethod(){return input;}public List<AccessibilityAction> '
                                                           'getSystemActions(){return actions;}public boolean performGlobalAction(int '
                                                           'id){calls++;last=id;return accept;}public static final int '
                                                           'GLOBAL_ACTION_NOTIFICATIONS=1,GLOBAL_ACTION_QUICK_SETTINGS=2,GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE=3,GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS=4,GLOBAL_ACTION_DPAD_UP=5,GLOBAL_ACTION_DPAD_DOWN=6,GLOBAL_ACTION_DPAD_LEFT=7,GLOBAL_ACTION_DPAD_RIGHT=8,GLOBAL_ACTION_DPAD_CENTER=9,GLOBAL_ACTION_POWER_DIALOG=10,GLOBAL_ACTION_LOCK_SCREEN=11,GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN=12;}',
 'io/airtap/preview/ScopedUi.java': 'package io.airtap.preview;final class ScopedUi {static final class Refused extends RuntimeException '
                                    '{Refused(String code){super(code);}}}',
 'android/content/pm/PackageManager.java': 'package android.content.pm;public class PackageManager {public static class NameNotFoundException '
                                           'extends Exception{}public android.content.res.Resources getResourcesForApplication(String '
                                           'name)throws NameNotFoundException{return new android.content.res.Resources();}}',
 'android/content/res/Resources.java': 'package android.content.res;public class Resources {public static class NotFoundException extends '
                                       'RuntimeException{}public String getResourceName(int id){if(id==100)return '
                                       '"com.example.app:id/first";if(id==200)return "com.example.app:id/second";throw new '
                                       'NotFoundException();}public int getIdentifier(String name,String type,String '
                                       'pkg){if("com.example.app:id/first".equals(name))return '
                                       '100;if("com.example.app:id/second".equals(name))return 200;return 0;}}'}

# A transport close can occur while a synchronous editor lookup is in progress.
SOURCES['android/accessibilityservice/InputMethod.java'] = SOURCES['android/accessibilityservice/InputMethod.java'].replace('public AccessibilityInputConnection getCurrentInputConnection(){return connection;}', 'public Runnable connectionReadHook;public AccessibilityInputConnection getCurrentInputConnection(){if(connectionReadHook!=null){var hook=connectionReadHook;connectionReadHook=null;hook.run();}return connection;}')

# Android queries can return after the transport has already closed.
SOURCES['android/content/Intent.java'] = 'package android.content;public class Intent {}'
SOURCES['android/accessibilityservice/AccessibilityService.java'] = SOURCES['android/accessibilityservice/AccessibilityService.java'].replace('getSystemActions(){return actions;}', 'getSystemActions(){if(actionsReadHook!=null){var hook=actionsReadHook;actionsReadHook=null;hook.run();}return actions;}public Runnable actionsReadHook;public int activityCalls;public android.content.Intent lastIntent;public void startActivity(android.content.Intent intent){activityCalls++;lastIntent=intent;}')
SOURCES['android/view/accessibility/AccessibilityNodeInfo.java'] = SOURCES['android/view/accessibility/AccessibilityNodeInfo.java'].replace('public boolean isEditable()', 'public CharSequence text;public boolean showingHint;public CharSequence getText(){return text;}public boolean isShowingHintText(){return showingHint;}public boolean isEditable()')

SOURCES['android/view/accessibility/AccessibilityNodeInfo.java'] = SOURCES['android/view/accessibility/AccessibilityNodeInfo.java'].replace('public CharSequence text;', 'public int selectionStart=0,selectionEnd=0;public int getTextSelectionStart(){return selectionStart;}public int getTextSelectionEnd(){return selectionEnd;}public CharSequence text;')
