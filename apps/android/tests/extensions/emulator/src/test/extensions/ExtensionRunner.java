package test.extensions;
import android.app.*;import android.os.*;import android.content.*;import android.view.accessibility.*;
import java.lang.reflect.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.util.function.*;import org.json.*;
public final class ExtensionRunner extends Instrumentation {
 static final String OWNER="a".repeat(32),OTHER="b".repeat(32),FIXTURE="io.airtap.fixture.extensions";
 final JSONArray checks=new JSONArray();final AtomicBoolean allowed=new AtomicBoolean(true);Object ui,service;Class<?> replyClass;Method execute;int downs,ups;
 @Override public void onCreate(Bundle b){super.onCreate(b);start();}
 Class<?> type(String name)throws Exception{return Class.forName("io.airtap.preview."+name);}
 Object call(Object instance,Class<?> cls,String name,Class<?>[] types,Object...args)throws Exception{Method m=cls.getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(instance,args);}catch(InvocationTargetException e){throw new RuntimeException(e.getCause());}}
 Object make(String name,Class<?>[] signature,Object...args)throws Exception{Constructor<?> c=type(name).getDeclaredConstructor(signature);c.setAccessible(true);return c.newInstance(args);}
 JSONObject q(String action)throws Exception{return new JSONObject().put("id",UUID.randomUUID().toString()).put("action",action);}
 void check(String name,boolean value)throws Exception{checks.put(new JSONObject().put("name",name).put("passed",value));if(!value)throw new AssertionError(name);}
 JSONObject request(JSONObject request)throws Exception{
  CountDownLatch done=new CountDownLatch(1);AtomicReference<JSONObject> out=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
  Object reply=Proxy.newProxyInstance(replyClass.getClassLoader(),new Class[]{replyClass},(p,m,a)->{out.set(m.getName().equals("success")?(JSONObject)a[0]:new JSONObject().put("error",a[0]));done.countDown();return null;});
  runOnMainSync(()->{try{execute.invoke(ui,request,reply,(BooleanSupplier)allowed::get,OWNER,(BooleanSupplier)allowed::get);}catch(Throwable e){error.set(e);done.countDown();}});
  if(!done.await(15,TimeUnit.SECONDS))throw new AssertionError("request timed out");if(error.get()!=null)throw new RuntimeException(error.get());return out.get();
 }
 void launch(boolean notify)throws Exception{getContext().startActivity(new Intent().setClassName(FIXTURE,"test.extensions.ExtensionActivity").putExtra("notify",notify).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));Thread.sleep(900);}
 void save(String purpose,JSONObject value)throws Exception{Object store=make("EnrollmentStore",new Class[]{Context.class,String.class},getTargetContext(),purpose);call(store,type("EnrollmentStore"),"saveRecord",new Class[]{JSONObject.class},value);}
 JSONObject events(String owner)throws Exception{return (JSONObject)call(null,type("NotificationEvents"),"read",new Class[]{Context.class,String.class,JSONObject.class},getTargetContext(),owner,new JSONObject().put("epoch","").put("after",0).put("limit",16));}
 void eventsTest()throws Exception{
  Context c=getTargetContext();String device="c".repeat(32),token="d".repeat(64),origin="https://fixture.invalid",url=origin+"/d/"+device;
  save("enrollment",new JSONObject().put("protocol_version",3).put("origin",origin).put("device_id",device).put("device_token",token).put("registered",true).put("enrollment_expires_at",JSONObject.NULL));
  String identity=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((url+"\n"+token).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  save("trusted-ai",new JSONObject().put("identity",identity).put("claim",OWNER).put("sequence",0));
  c.getSharedPreferences("trust-state",0).edit().clear().putBoolean("stopped",false).commit();
  call(null,type("NotificationEvents"),"select",new Class[]{Context.class,boolean.class},c,true);launch(true);
  JSONObject page=null;for(int i=0;i<30;i++){page=events(OWNER);if(page.toString().contains("SYNTHETIC_EVENT_PAYLOAD"))break;Thread.sleep(200);}
  check("real Android notification delivered through listener",page.toString().contains("SYNTHETIC_EVENT_PAYLOAD"));
  byte[] disk=java.nio.file.Files.readAllBytes(new java.io.File(c.getFilesDir(),"events-"+OWNER+".aesgcm").toPath());
  check("event content encrypted at rest",!new String(disk,java.nio.charset.StandardCharsets.ISO_8859_1).contains("SYNTHETIC_EVENT_PAYLOAD"));
  check("other grant has no historical event content",!events(OTHER).toString().contains("SYNTHETIC_EVENT_PAYLOAD"));
  call(null,type("NotificationEvents"),"select",new Class[]{Context.class,boolean.class},c,false);
  check("owner opt out deletes recorded events",!new java.io.File(c.getFilesDir(),"events-"+OWNER+".aesgcm").exists());
 }
 void touchTest()throws Exception{
  launch(false);JSONObject image=request(q("observe"));check("native screenshot for continued touch",image.has("observation_id"));
  JSONObject hold=request(q("touch_begin").put("package",FIXTURE).put("observation_id",image.getString("observation_id")).put("x",.3).put("y",.4));
  check("held native pointer acknowledged",hold.optBoolean("held"));Thread.sleep(400);
  image=request(q("observe"));check("screenshot while pointer stays down",image.has("observation_id")&&downs==1&&ups==0);
  JSONObject move=request(q("touch_move").put("package",FIXTURE).put("observation_id",image.getString("observation_id")).put("touch_id",hold.getString("touch_id")).put("x",.6).put("y",.6).put("duration_ms",400));
  check("native continuation keeps same pointer",move.optBoolean("held")&&downs==1&&ups==0);
  JSONObject end=request(q("touch_end").put("touch_id",hold.getString("touch_id")));Thread.sleep(300);
  check("one native down and one up across observe/move",end.optBoolean("accepted")&&downs==1&&ups==1);
 }
 String labels(AccessibilityNodeInfo root){
  if(root==null)return "null";StringBuilder out=new StringBuilder();java.util.ArrayDeque<AccessibilityNodeInfo> queue=new java.util.ArrayDeque<>();queue.add(root);
  int n=0;while(!queue.isEmpty()&&n++<80){var node=queue.remove();if(node.getText()!=null)out.append(node.getText()).append("|");if(node.getContentDescription()!=null)out.append(node.getContentDescription()).append("|");if(node.getViewIdResourceName()!=null)out.append(node.getViewIdResourceName()).append("|");for(int i=0;i<node.getChildCount();i++){var child=node.getChild(i);if(child!=null)queue.add(child);}}
  return root.getPackageName()+":"+out;
 }
 void captureTest()throws Exception{
  JSONObject readiness=(JSONObject)call(null,type("MediaCapture"),"readiness",new Class[]{Context.class},getTargetContext());
  check("capture readiness is side-effect-free and declares per-session screen consent",!readiness.getBoolean("starts_capture")&&!readiness.getBoolean("busy")&&readiness.getString("screen_consent").equals("required_each_session"));
  check("capture readiness matches Android microphone permission",readiness.getBoolean("microphone_permission")== (getTargetContext().checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED));
  launch(false);JSONObject start=request(q("media_capture").put("kind","screen").put("seconds",3));check("screen capture requests system consent",start.optString("state").equals("awaiting_system_ui"));String id=start.getString("capture_id");
  // The host driver accepts the normal system dialog on this explicitly isolated emulator.
  java.util.Set<String> diagnostics=new java.util.LinkedHashSet<>();JSONObject status=null;for(int i=0;i<120;i++){
   status=(JSONObject)call(null,type("MediaCapture"),"status",new Class[]{String.class,String.class},OWNER,id);
   if(status.optString("state").equals("finished")||status.optString("state").equals("canceled"))break;
   if(status.optString("state").equals("awaiting_system_ui")){
    AccessibilityNodeInfo root=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).getRootInActiveWindow();
    if(diagnostics.size()<6)diagnostics.add(labels(root));
    if(root!=null&&"com.android.systemui".contentEquals(root.getPackageName()==null?"":root.getPackageName())){
     boolean clicked=false;
     for(String label:List.of("Share screen","Start now","Start recording","Start","Share entire screen","Entire screen","Share one app","A single app","Next","Airtap Extension Fixture")){
      for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText(label))if(!clicked&&label.contentEquals(n.getText()==null?"":n.getText())){
       // A selected spinner label is only opened if it still selects one app; the full-screen selection proceeds to confirmation.
       if((label.equals("Share entire screen")||label.equals("Entire screen"))&&root.findAccessibilityNodeInfosByText("Cancel").size()>0)continue;
       AccessibilityNodeInfo target=n;for(int depth=0;depth<3&&target!=null&&!target.isClickable();depth++)target=target.getParent();
       if(target!=null&&target.isEnabled())clicked=target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
      }
      if(clicked)break;
     }
    }
   }
   Thread.sleep(250);
  }
  if(status==null||!status.optString("state").equals("finished"))throw new AssertionError("capture result: "+status+"; system UI: "+diagnostics);check("system-consented recording imported into workspace",true);JSONObject file=status.getJSONObject("file");
  check("recording has nonempty verified hash",file.getLong("size")>1000&&file.getString("sha256").matches("[a-f0-9]{64}"));
  JSONObject stat=request(q("file_stat").put("root","workspace").put("path",file.getString("path")));
  check("media output available to normal file transport",stat.optString("sha256").equals(file.getString("sha256")));
  JSONObject chunk=request(q("file_read").put("root","workspace").put("path",file.getString("path")).put("offset",0).put("length",16).put("version",stat.getString("version")));
  byte[] bytes=java.util.Base64.getDecoder().decode(chunk.getString("data_base64"));
  check("recording bytes accessible through normal scoped file read",bytes.length==16&&new String(bytes,4,4,java.nio.charset.StandardCharsets.US_ASCII).equals("ftyp"));
  check("completed capture clears its encrypted pending-output marker",!new java.io.File(getTargetContext().getFilesDir(),"media-pending.aesgcm").exists());
  launch(false);JSONObject next=request(q("media_capture").put("kind","screen").put("seconds",3));Thread.sleep(500);
  runOnMainSync(()->{try{call(null,type("MediaCapture"),"invalidate",new Class[]{});}catch(Exception e){throw new RuntimeException(e);}});
  JSONObject canceled=(JSONObject)call(null,type("MediaCapture"),"status",new Class[]{String.class,String.class},OWNER,next.getString("capture_id"));
  check("task invalidation cancels pending consent without recording",canceled.optString("state").equals("canceled")&&canceled.isNull("file"));
 }
 boolean clickCapture(AccessibilityNodeInfo root,String...wanted){
  for(String label:wanted){java.util.ArrayDeque<AccessibilityNodeInfo> queue=new java.util.ArrayDeque<>();queue.add(root);int n=0;
   while(!queue.isEmpty()&&n++<200){var node=queue.remove();String id=node.getViewIdResourceName(),desc=node.getContentDescription()==null?"":node.getContentDescription().toString();
    if(node.isVisibleToUser()&&node.isEnabled()&&(label.equals(desc)||label.contentEquals(node.getText()==null?"":node.getText())||id!=null&&id.endsWith(":id/"+label))){
     var target=node;for(int depth=0;depth<3&&target!=null&&!target.isClickable();depth++)target=target.getParent();if(target!=null&&target.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
    }
    for(int i=0;i<node.getChildCount();i++){var child=node.getChild(i);if(child!=null)queue.add(child);}
   }
  }return false;
 }
 void cameraTest(String kind)throws Exception{
  launch(false);JSONObject start=request(q("media_capture").put("kind",kind).put("seconds",3));String id=start.getString("capture_id");JSONObject status=null;boolean shutter=false,observed=false;long started=0;
  var diagnostics=new java.util.LinkedHashSet<String>();
  for(int i=0;i<100;i++){
   status=(JSONObject)call(null,type("MediaCapture"),"status",new Class[]{String.class,String.class},OWNER,id);
   if(status.optString("state").equals("finished")||status.optString("state").equals("canceled"))break;
   var root=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).getRootInActiveWindow();if(diagnostics.size()<6)diagnostics.add(labels(root));
   if(root!=null&&"com.android.camera2".contentEquals(root.getPackageName()==null?"":root.getPackageName())){
    if(!observed){JSONObject tree=request(q("read_tree"));if(tree.has("error")){diagnostics.add("Airtap: "+tree);Thread.sleep(500);continue;}check("normal "+kind+" camera is observable by Airtap",true);observed=true;}
    if(!clickCapture(root,"Done","Save","done_button","review_done")){
     if(!shutter){shutter=clickCapture(root,"Take photo","Take picture","Start video","Start recording","Shutter","shutter_button");if(shutter)started=SystemClock.elapsedRealtime();}
     else if(kind.equals("video")&&SystemClock.elapsedRealtime()-started>3500)clickCapture(root,"Stop video","Stop recording");
    }
   }else if(root!=null&&String.valueOf(root.getPackageName()).contains("permissioncontroller"))clickCapture(root,"While using the app","Allow");
   Thread.sleep(250);
  }
  if(status==null||!status.optString("state").equals("finished"))throw new AssertionError(kind+" result: "+status+"; UI: "+diagnostics);
  check("normal Android "+kind+" capture imports a nonempty workspace file",status.getJSONObject("file").getLong("size")>1000);
  boolean refused=false;try{getTargetContext().getContentResolver().openFileDescriptor(android.net.Uri.parse("content://io.airtap.preview.capture-output/"+id),"r");}catch(java.io.FileNotFoundException e){refused=true;}
  check("completed "+kind+" output URI no longer opens",refused);
 }
 void audioTest()throws Exception{
  launch(false);JSONObject started=request(q("media_capture").put("kind","audio").put("seconds",3));String id=started.getString("capture_id");JSONObject status=null;
  for(int i=0;i<100;i++){
   status=(JSONObject)call(null,type("MediaCapture"),"status",new Class[]{String.class,String.class},OWNER,id);
   if(status.optString("state").equals("finished")||status.optString("state").equals("canceled"))break;
   var root=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).getRootInActiveWindow();
   if(root!=null&&String.valueOf(root.getPackageName()).contains("permissioncontroller"))clickCapture(root,"While using the app","Only this time","Allow");
   Thread.sleep(250);
  }
  if(status==null||!status.optString("state").equals("finished"))throw new AssertionError("audio result: "+status);
  check("runtime-consented bounded microphone recording returns M4A",status.getJSONObject("file").getLong("size")>100&&status.getJSONObject("file").getString("path").endsWith(".m4a"));
  launch(false);JSONObject next=request(q("media_capture").put("kind","audio").put("seconds",60));String nextId=next.getString("capture_id");
  for(int i=0;i<30;i++){status=(JSONObject)call(null,type("MediaCapture"),"status",new Class[]{String.class,String.class},OWNER,nextId);if(status.optString("state").equals("recording"))break;Thread.sleep(100);}
  check("authorized microphone session starts",status.optString("state").equals("recording"));allowed.set(false);Thread.sleep(650);
  status=(JSONObject)call(null,type("MediaCapture"),"status",new Class[]{String.class,String.class},OWNER,nextId);Field running=type("AudioCaptureService").getDeclaredField("running");running.setAccessible(true);
  check("authority loss stops microphone service and discards partial capture",status.optString("state").equals("canceled")&&status.isNull("file")&&running.get(null)==null);allowed.set(true);
 }
 @Override public void onStart(){Bundle result=new Bundle();BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){int action=i.getIntExtra("action",-1);if(action==0)downs++;if(action==1)ups++;}};
  try{
   getTargetContext().registerReceiver(receiver,new IntentFilter("io.airtap.fixture.TOUCH"),Context.RECEIVER_EXPORTED);
   Field f=type("PreviewService").getDeclaredField("instance");f.setAccessible(true);for(int i=0;i<150&&service==null;i++){service=f.get(null);if(service==null)Thread.sleep(200);}if(service==null)throw new AssertionError("no accessibility service");
   ui=make("CompanionUi",new Class[]{android.accessibilityservice.AccessibilityService.class,BooleanSupplier.class},service,(BooleanSupplier)allowed::get);replyClass=type("CompanionUi$Reply");
   execute=type("CompanionUi").getDeclaredMethod("execute",JSONObject.class,replyClass,BooleanSupplier.class,String.class,BooleanSupplier.class);execute.setAccessible(true);
   eventsTest();touchTest();captureTest();cameraTest("photo");cameraTest("video");audioTest();
   Object pin=make("SimPinInput",new Class[]{android.accessibilityservice.AccessibilityService.class},service);boolean denied=false;
   try{call(pin,type("SimPinInput"),"prepare",new Class[]{String.class},OWNER);}catch(RuntimeException e){denied=e.getCause().getMessage().equals("sim_pin_entry_not_enabled");}
   check("SIM PIN entry disabled by default",denied);
   result.putString("report",new JSONObject().put("complete",true).put("checks",checks).toString());finish(-1,result);
  }catch(Throwable e){result.putString("error",android.util.Log.getStackTraceString(e));result.putString("checks",checks.toString());finish(1,result);}
  finally{try{getTargetContext().unregisterReceiver(receiver);}catch(Exception ignored){}}
 }
}
