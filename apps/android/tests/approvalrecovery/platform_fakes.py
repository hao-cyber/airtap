"""Explicit disk, process, clock and HTTP fault fixtures; no Android/relay emulation claims."""
SOURCES = {
'org/json/JSONObject.java': '''package org.json; import java.util.*;
public class JSONObject {public static final Object NULL=new Object();public final Map<String,Object> fields=new LinkedHashMap<>();
public JSONObject put(String k,Object v){fields.put(k,v==null?NULL:v);return this;}public Object opt(String k){return fields.get(k);}public Object get(String k){if(!fields.containsKey(k))throw new IllegalArgumentException(k);return fields.get(k);}public boolean has(String k){return fields.containsKey(k);}public boolean isNull(String k){return !has(k)||get(k)==NULL;}public String optString(String k){Object v=opt(k);return v instanceof String?(String)v:"";}public String getString(String k){Object v=get(k);if(!(v instanceof String))throw new IllegalArgumentException(k);return (String)v;}public boolean getBoolean(String k){return (Boolean)get(k);}public long getLong(String k){return ((Number)get(k)).longValue();}public JSONObject getJSONObject(String k){return (JSONObject)get(k);}public JSONArray getJSONArray(String k){return (JSONArray)get(k);}public Object remove(String k){return fields.remove(k);}
public JSONObject copy(){JSONObject n=new JSONObject();for(var e:fields.entrySet()){Object v=e.getValue();n.put(e.getKey(),v instanceof JSONObject?((JSONObject)v).copy():v);}return n;}}
''',
'org/json/JSONArray.java': '''package org.json;public class JSONArray {private final java.util.List<Object> v=new java.util.ArrayList<>();public JSONArray put(Object x){v.add(x);return this;}public int length(){return v.size();}}''',
'android/content/SharedPreferences.java': '''package android.content;public interface SharedPreferences {boolean getBoolean(String k,boolean d);String getString(String k,String d);Editor edit();interface Editor {Editor putBoolean(String k,boolean v);Editor putString(String k,String v);Editor remove(String k);boolean commit();}}''',
'android/content/Context.java': '''package android.content;public class Context {public Context getApplicationContext(){return this;}public <T>T getSystemService(Class<T> type){try{return type.getDeclaredConstructor().newInstance();}catch(Exception failure){throw new IllegalStateException(failure);}}public SharedPreferences getSharedPreferences(String k,int mode){return io.airtap.preview.FakeDisk.preferences;}}''',
'android/os/SystemClock.java': '''package android.os;public class SystemClock {public static long now=100000;public static long elapsedRealtime(){return now;}}''',
'io/airtap/preview/FakeDisk.java': '''package io.airtap.preview;import android.content.SharedPreferences;import org.json.JSONObject;import java.util.*;
public final class FakeDisk {
 public static final class Crash extends Error {public Crash(){super("injected process death");}}
 static final class Prefs implements SharedPreferences {
  final Map<String,Object> disk=new HashMap<>(),memory=new HashMap<>();int calls,failAt,crashBeforeAt,crashAfterAt;
  public boolean getBoolean(String k,boolean d){Object v=memory.get(k);return v instanceof Boolean?(Boolean)v:d;}
  public String getString(String k,String d){Object v=memory.get(k);return v instanceof String?(String)v:d;}
  public Editor edit(){return new Editor(){final Map<String,Object> changes=new HashMap<>();public Editor putBoolean(String k,boolean v){changes.put(k,v);return this;}public Editor putString(String k,String v){changes.put(k,v);return this;}public Editor remove(String k){changes.put(k,null);return this;}
   public boolean commit(){calls++;if(calls==crashBeforeAt)throw new Crash();apply(memory,changes);if(calls==failAt)return false;apply(disk,changes);if(calls==crashAfterAt)throw new Crash();return true;}};}
  static void apply(Map<String,Object> dst,Map<String,Object> delta){for(var e:delta.entrySet())if(e.getValue()==null)dst.remove(e.getKey());else dst.put(e.getKey(),e.getValue());}
  void reboot(){memory.clear();memory.putAll(disk);failAt=crashBeforeAt=crashAfterAt=0;}
 }
 public static Prefs preferences=new Prefs();static JSONObject record;static EnrollmentStore.Config config;static int saves,failSaveAt,crashBeforeSaveAt,crashAfterSaveAt;static boolean failForget;
 static void reset(){preferences=new Prefs();record=null;config=null;saves=failSaveAt=crashBeforeSaveAt=crashAfterSaveAt=0;failForget=false;}
 static void reboot(){preferences.reboot();saves=failSaveAt=crashBeforeSaveAt=crashAfterSaveAt=0;failForget=false;}
}
''',
'io/airtap/preview/EnrollmentStore.java': '''package io.airtap.preview;import android.content.Context;import org.json.JSONObject;
final class EnrollmentStore {EnrollmentStore(Context c){}EnrollmentStore(Context c,String purpose){if(!"trusted-ai".equals(purpose))throw new AssertionError("real encrypted purpose required");}
 static final class Config {final String url,token;final boolean registered;Config(String u,String t){url=u;token=t;registered=true;}boolean publicService(){return true;}boolean enrolled(){return registered;}}
 Config read(){return FakeDisk.config;}JSONObject readRecord(){return FakeDisk.record==null?null:FakeDisk.record.copy();}
 void saveRecord(JSONObject value){FakeDisk.saves++;if(FakeDisk.saves==FakeDisk.crashBeforeSaveAt)throw new FakeDisk.Crash();if(FakeDisk.saves==FakeDisk.failSaveAt)throw new IllegalStateException("disk failed");FakeDisk.record=value.copy();if(FakeDisk.saves==FakeDisk.crashAfterSaveAt)throw new FakeDisk.Crash();}
 void forget(){if(FakeDisk.failForget)throw new IllegalStateException("delete failed");FakeDisk.record=null;}
 static String token(){return java.util.UUID.randomUUID().toString().replace("-","")+java.util.UUID.randomUUID().toString().replace("-","");}}
''',
'io/airtap/preview/RelayApi.java': '''package io.airtap.preview;import org.json.JSONObject;import java.util.*;
final class RelayApi {interface Reply {void done(JSONObject body,int status,String error);}static final class Request {final String url,token,path,method;final JSONObject body;final Reply reply;Request(String u,String p,String m,String t,JSONObject b,Reply r){url=u;path=p;method=m;token=t;body=b;reply=r;}void respond(JSONObject b,int s,String e){reply.done(b,s,e);}}
 static final List<Request> requests=new ArrayList<>();static void requestStatus(String url,String path,String method,String token,JSONObject body,Reply reply){if(!(("/v1/device/approve".equals(path)&&"POST".equals(method))||("/v1/device/pairing".equals(path)&&"GET".equals(method))))throw new AssertionError("unexpected action");requests.add(new Request(url,path,method,token,body==null?null:body.copy(),reply));}
 static JSONObject json(Object... args){JSONObject value=new JSONObject();for(int i=0;i<args.length;i+=2)value.put((String)args[i],args[i+1]);return value;}}
''',
'io/airtap/preview/ScopedUi.java': '''package io.airtap.preview;final class ScopedUi {static final class Refused extends RuntimeException {Refused(String code){super(code);}}}''',
'io/airtap/preview/PreviewState.java': '''package io.airtap.preview;final class PreviewState {static EnrollmentStore.Config config;static boolean persistent,allowControl,allowTap,grantAvailable;static String claim,candidate;static long approvedExpiry;static java.util.List<String> allowedPackages;}''',
}

# Only the scheduling runner adds these. Timers are per-owner queues; advancing the
# clock never calls Activity refresh or the service implicitly.
SCHEDULING_SOURCES = {
'io/airtap/preview/WorkspaceShares.java': '''package io.airtap.preview;final class WorkspaceShares {static int revocations;static void revokeAll(android.content.Context c){revocations++;}}''',
'android/os/Handler.java': '''package android.os;import java.util.*;public final class Handler {private record Task(long at,Runnable action){}private final List<Task> tasks=new ArrayList<>();public void postDelayed(Runnable action,long delay){tasks.add(new Task(SystemClock.now+delay,action));}public void removeCallbacksAndMessages(Object ignored){tasks.clear();}public int pending(){return tasks.size();}public void advance(long millis){long end=SystemClock.now+millis;int count=0;for(;;){Task next=null;for(Task item:tasks)if(item.at<=end&&(next==null||item.at<next.at))next=item;if(next==null)break;if(++count>1000)throw new AssertionError("timer loop");tasks.remove(next);SystemClock.now=next.at;next.action.run();}SystemClock.now=end;}}''',
'android/app/Activity.java': '''package android.app;public class Activity extends android.content.Context {private boolean destroyed;public void onDestroy(){destroyed=true;}public boolean isDestroyed(){return destroyed;}}''',
'android/net/ConnectivityManager.java': '''package android.net;public class ConnectivityManager {public void unregisterNetworkCallback(Object callback){}}''',
'okhttp3/WebSocket.java': '''package okhttp3;public interface WebSocket {void cancel();}''',
'io/airtap/preview/SessionService.java': '''package io.airtap.preview;final class SessionService {static boolean permitted=true,ready,deferBegin,startFails;static int begun,ended,run;static boolean notificationsAllowed(android.content.Context c){return permitted;}static boolean readyFor(PreviewService c,int generation){return ready&&permitted&&run==generation&&PreviewService.instance==c;}static void begin(android.content.Context c){begun++;PreviewService p=(PreviewService)c;run=p.generation;if(startFails){p.notificationUnavailable(run,true);return;}if(!deferBegin){ready=true;p.notificationReady(run);}}static void end(android.content.Context c){ended++;ready=false;}}''',
'io/airtap/preview/RelayAddress.java': '''package io.airtap.preview;final class RelayAddress {static String webSocket(String base){return base.replace("https:","wss:")+"/socket";}}''',
}
