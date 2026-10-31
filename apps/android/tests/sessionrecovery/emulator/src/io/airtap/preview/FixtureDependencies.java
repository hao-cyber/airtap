package io.airtap.preview;
import android.content.Context;import org.json.JSONObject;
// Only these prerequisites/controller are synthetic. Never bind or enable accessibility.
final class SessionRecoveryGate {static boolean operationConfigured(Context c){return true;}static boolean userStopped(Context c){return false;}}
final class PreviewService extends android.content.ContextWrapper {
 static PreviewService instance;PreviewService(Context c){super(c);}
 boolean isAuthorized(){return false;}boolean unlocked(){throw new AssertionError("cold recovery must not inspect/wake screen");}
 boolean notificationBlocked(){return false;}int sessionGeneration(){return 0;}String sessionFence(){return null;}
 void notificationUnavailable(int r,boolean f){throw new AssertionError("no controller");}void notificationReady(int r){throw new AssertionError("no authorization");}
 void sessionCarrierUnavailable(){}void storageUnavailable(){}void stoppedLocally(String f){}void restore(){throw new AssertionError("no synthetic network");}void onInterrupt(){}
}
final class ScopedUi {static final class Refused extends RuntimeException {Refused(String s){super(s);}}}
final class PreviewState {static boolean persistent,allowControl,allowTap,grantAvailable;static String claim,candidate;static long approvedExpiry;static java.util.List<String> allowedPackages;}
final class RelayApi {static JSONObject json(Object... args)throws Exception {JSONObject j=new JSONObject();for(int i=0;i<args.length;i+=2)j.put((String)args[i],args[i+1]);return j;}}
