"""Deterministic Activity, storage and HTTPS fixtures. No device or deployed-service claim."""
SOURCES = {
'android/os/Handler.java': '''package android.os; import java.util.*;
public final class Handler { public final List<Runnable> tasks=new ArrayList<>();public final List<Long> delays=new ArrayList<>();
public void postDelayed(Runnable r,long delay){tasks.add(r);delays.add(delay);}public void removeCallbacks(Runnable r){tasks.removeIf(x->x==r);}public void removeCallbacksAndMessages(Object ignored){tasks.clear();}public void next(){if(tasks.isEmpty())throw new AssertionError("no timer");tasks.remove(0).run();}}
''',
'android/app/Activity.java': '''package android.app;public class Activity {public boolean destroyed;public boolean isDestroyed(){return destroyed;}public void onPause(){}public void onDestroy(){destroyed=true;}protected void onSaveInstanceState(android.os.Bundle state){}}
''',
'android/os/Bundle.java': '''package android.os;import java.util.*;public class Bundle {final Map<String,Object> values=new HashMap<>();public String getString(String k){Object v=values.get(k);return v instanceof String?(String)v:null;}public long getLong(String k){Object v=values.get(k);return v instanceof Long?(Long)v:0;}public void putString(String k,String v){values.put(k,v);}public void putLong(String k,long v){values.put(k,v);}}''',
'okhttp3/Call.java': '''package okhttp3;public interface Call {void cancel();}''',
'io/airtap/preview/Fixtures.java': '''package io.airtap.preview;
import java.util.*;import org.json.JSONObject;
final class Prefs {final Map<String,Object> values=new HashMap<>();boolean fail;
 boolean getBoolean(String k,boolean d){Object v=values.get(k);return v instanceof Boolean?(Boolean)v:d;}
 int getInt(String k,int d){Object v=values.get(k);return v instanceof Integer?(Integer)v:d;}
 String getString(String k,String d){Object v=values.get(k);return v instanceof String?(String)v:d;}
 Edit edit(){return new Edit();}final class Edit {final Map<String,Object> changes=new HashMap<>();Edit putBoolean(String k,boolean v){changes.put(k,v);return this;}Edit putString(String k,String v){changes.put(k,v);return this;}Edit putInt(String k,int v){changes.put(k,v);return this;}Edit remove(String k){changes.put(k,null);return this;}boolean commit(){if(fail)return false;changes.forEach((k,v)->{if(v==null)values.remove(k);else values.put(k,v);});return true;}void apply(){commit();}}
}
final class BuildConfig {static final String PUBLIC_RELAY_ORIGIN="https://relay.example";}
final class EnrollmentStore {static Config saved;static boolean fail;static int serial;
 static final class Config {final String url,token,deviceId;final boolean registered;Config(String origin,String id,String token,boolean registered){this.url=RelayAddress.deviceBase(origin,id);this.deviceId=id;this.token=token;this.registered=registered;}Config(String url,String token,long expires){this.url=url;this.token=token;this.deviceId=null;this.registered=expires>0;}String origin(){return RelayAddress.root(url);}boolean enrolled(){return registered;}boolean publicService(){return deviceId!=null;}}
 EnrollmentStore(Object context){}void save(Config config){if(fail)throw new IllegalStateException();saved=config;}static String token(){return String.format("%064x",++serial);}}
final class TrustedGrant {static JSONObject trusted,pending;static boolean unreadable;TrustedGrant(Object context){}JSONObject read(EnrollmentStore.Config c){if(unreadable)throw new IllegalStateException();return trusted;}JSONObject pending(EnrollmentStore.Config c){if(unreadable)throw new IllegalStateException();return pending;}void stop(){trusted=null;pending=null;}}
final class PreviewState {static EnrollmentStore.Config config;static int stops;static void stop(String message){stops++;}}
final class PreviewService {static PreviewService instance;}
final class SessionService {static boolean notifications=true;static boolean notificationsAllowed(Object context){return notifications;}}
final class ConnectionFeedback {void clear(){}}
final class RelayApi {
 interface StatusResult {void complete(JSONObject body,int status,String error);}interface Result {void complete(JSONObject body,String error);}interface TimedStatusResult {void complete(JSONObject body,int status,String error,InvitationFeedback.ServerTime time);}
 static final List<Request> requests=new ArrayList<>();
 static final class Request implements okhttp3.Call {final String url,path,method,token;final JSONObject body;final TimedStatusResult callback;boolean cancelled;Request(String url,String path,String method,String token,JSONObject body,TimedStatusResult callback){this.url=url;this.path=path;this.method=method;this.token=token;this.body=body;this.callback=callback;}public void cancel(){cancelled=true;}void answer(JSONObject value,int status){callback.complete(value,status,status==200?null:"synthetic failure",null);}}
 static Request requestTimedStatus(String url,String path,String method,String token,JSONObject body,TimedStatusResult callback){Request r=new Request(url,path,method,token,body,callback);requests.add(r);return r;}
 static Request requestStatus(String url,String path,String method,String token,JSONObject body,StatusResult callback){return requestTimedStatus(url,path,method,token,body,(value,status,error,time)->callback.complete(value,status,error));}
 static Request request(String url,String path,String method,String token,JSONObject body,Result callback){return requestStatus(url,path,method,token,body,(value,status,error)->callback.complete(value,error));}
 static JSONObject json(Object... values){JSONObject r=new JSONObject();for(int i=0;i<values.length;i+=2)r.put((String)values[i],values[i+1]);return r;}
 static Request last(){return requests.get(requests.size()-1);}static long posts(String path){return requests.stream().filter(r->r.method.equals("POST")&&r.path.equals(path)).count();}
}
'''
}
