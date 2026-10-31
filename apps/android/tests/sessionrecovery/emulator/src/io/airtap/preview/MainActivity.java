package io.airtap.preview;
import android.app.*;import android.content.*;import android.os.*;import org.json.*;import java.nio.charset.StandardCharsets;
/** Driver stays in the fixture UID. No INTERNET permission, accessibility, relay or device commands. */
public final class MainActivity extends Activity {
 private final Handler main=new Handler(Looper.getMainLooper());
 @Override public void onCreate(Bundle b){super.onCreate(b);android.widget.TextView t=new android.widget.TextView(this);t.setText("Offline recovery lifecycle fixture\nSynthetic consent only");setContentView(t);dispatch(getIntent());}
 @Override public void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);dispatch(i);}
 private EnrollmentStore.Config config()throws Exception{return new EnrollmentStore(this).read();}
 private TrustedGrant trust(){return new TrustedGrant(this);}
 private String fence()throws Exception{return trust().sessionFence(config());}
 private PendingIntent stop(String f){return PendingIntent.getService(this,2,SessionService.stopIntent(this,f),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
 private void assertTrue(boolean b,String m){if(!b)throw new AssertionError(m);}
 private Object field(Object target,String name)throws Exception {var f=SessionService.class.getDeclaredField(name);f.setAccessible(true);return f.get(target);}
 private JSONObject snapshot()throws Exception {
  JSONObject j=new JSONObject().put("pid",android.os.Process.myPid()).put("controller_absent",PreviewService.instance==null).put("grant_present",fence()!=null);
  Object s=field(null,"instance");j.put("service_present",s!=null);
  if(s!=null){j.put("foreground",field(s,"foregroundReady")).put("execution",field(s,"executionReady")).put("screen_lock_absent",field(s,"screen")==null);}
  var notices=getSystemService(NotificationManager.class).getActiveNotifications();j.put("notification_count",notices.length);
  if(notices.length>0)j.put("notification_title",notices[0].getNotification().extras.getString(Notification.EXTRA_TITLE));
  return j;
 }
 private void record(String mode,JSONObject j){try{j.put("mode",mode).put("ok",true);try(var out=openFileOutput("result-"+mode+".json",0)){out.write(j.toString().getBytes(StandardCharsets.UTF_8));}android.util.Log.i("FixtureRecovery","pass="+mode);}catch(Exception e){fail(mode,e);}}
 private void fail(String mode,Throwable e){try{JSONObject j=new JSONObject().put("ok",false).put("failure",e.toString());try(var out=openFileOutput("result-"+mode+".json",0)){out.write(j.toString().getBytes(StandardCharsets.UTF_8));}}catch(Exception ignored){}android.util.Log.e("FixtureRecovery","failed="+mode,e);}
 private void later(String mode,Runnable r){main.postDelayed(()->{try{r.run();}catch(Throwable e){fail(mode,e);}},1000);}
 private void dispatch(Intent i){String mode=i.getStringExtra("mode");main.postDelayed(()->{try{run(mode);}catch(Throwable e){fail(mode,e);}},250);}
 private void run(String mode)throws Exception {
  if("seed".equals(mode)){
   var c=new EnrollmentStore.Config("https://fixture.invalid","11111111111111111111111111111111","2222222222222222222222222222222222222222222222222222222222222222",true);
   new EnrollmentStore(this).save(c);trust().stop();String id=trust().beginApproval(c,"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","Synthetic AI");assertTrue(trust().completeApproval(c,id),"approval");trust().consume(c,87);
   getSharedPreferences("fixture",0).edit().putString("first_fence",fence()).commit();assertTrue(SessionService.ensureRestoring(this),"carrier start");
   later(mode,()->{try{JSONObject j=snapshot();assertTrue(j.getBoolean("foreground")&&!j.getBoolean("execution")&&j.getBoolean("screen_lock_absent"),"cold scope");record(mode,j);}catch(Exception e){throw new RuntimeException(e);}});return;
  }
  if("inspect-restart".equals(mode)){
   JSONObject j=snapshot();assertTrue(j.getBoolean("foreground")&&!j.getBoolean("execution")&&j.getBoolean("screen_lock_absent"),"restored scope");
   assertTrue(fence().equals(getSharedPreferences("fixture",0).getString("first_fence","")),"same grant");assertTrue(trust().read(config()).getLong("sequence")==87,"same replay sequence");record(mode,j);return;
  }
  if("old-stop".equals(mode)){
   String old=fence();PendingIntent oldPi=stop(old);trust().stop();String id=trust().beginApproval(config(),"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","New synthetic consent");assertTrue(trust().completeApproval(config(),id),"new approval");String current=fence();PendingIntent next=stop(current);
   assertTrue(!old.equals(current)&&!oldPi.equals(next),"native PI identity distinct");assertTrue(SessionService.ensureRestoring(this),"new carrier");
   main.postDelayed(()->{try{oldPi.send();}catch(Exception e){fail(mode,e);}},500);
   later(mode,()->{try{assertTrue(current.equals(fence()),"old STOP revoked new consent");JSONObject j=snapshot().put("pending_intents_distinct",true).put("new_consent_survived",true);assertTrue(j.getBoolean("foreground"),"new carrier survived");record(mode,j);}catch(Exception e){throw new RuntimeException(e);}});return;
  }
  if("current-stop".equals(mode)){
   stop(fence()).send();later(mode,()->{try{assertTrue(fence()==null,"STOP persisted");assertTrue(!SessionService.ensureRestoring(this),"stopped grant restarted");JSONObject j=snapshot();assertTrue(!j.getBoolean("service_present")&&j.getInt("notification_count")==0,"STOP closed carrier");record(mode,j);}catch(Exception e){throw new RuntimeException(e);}});return;
  }
  if("inspect-stopped".equals(mode)){
   assertTrue(fence()==null&&!SessionService.ensureRestoring(this),"cold stop fence lost");record(mode,snapshot());return;
  }
  throw new IllegalArgumentException("unknown mode");
 }
}
