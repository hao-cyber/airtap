package io.airtap.preview;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.util.function.BooleanSupplier;

/** One visible, finite capture at a time; access stays bound to the originating local grant. */
final class MediaCapture {
    static final long MAX_BYTES=64L*1024*1024;
    private static MediaCapture current;
    final String id=EnrollmentStore.token().substring(0,32), owner, kind;
    final BooleanSupplier authority;
    final Context context;
    final int seconds;
    String state="awaiting_system_ui", error;
    JSONObject file;
    Uri output;
    MediaCaptureActivity activity;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable deadline=()->cancel("capture_expired");
    private final Runnable fence=new Runnable(){public void run(){
        if(!active())return;
        if(!authority.getAsBoolean())cancel("capture_authorization_lost");
        else if(CaptureOutputProvider.file(context,id).length()>MAX_BYTES)cancel("capture_too_large");
        else handler.postDelayed(this,250);
    }};
    private MediaCapture(Context c,String owner,String kind,int seconds,BooleanSupplier authority){this.context=c.getApplicationContext();this.owner=owner;this.kind=kind;this.seconds=seconds;this.authority=authority;}
    boolean active(){return state.equals("awaiting_system_ui")||state.equals("recording")||state.equals("importing");}
    static boolean quiescent(){return current==null||!current.active();}
    /** Readiness only: never opens an Activity, asks for consent, or starts a recorder. */
    static JSONObject readiness(Context context){
        Object microphone=JSONObject.NULL;
        try{microphone=context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED;}catch(RuntimeException ignored){}
        return RelayApi.json("busy",!quiescent(),"photo_handler_available",handlerAvailable(context,android.provider.MediaStore.ACTION_IMAGE_CAPTURE),
                "video_handler_available",handlerAvailable(context,android.provider.MediaStore.ACTION_VIDEO_CAPTURE),
                "camera_permission","external_camera_app","microphone_permission",microphone,
                "screen_consent","required_each_session","starts_capture",false);
    }
    private static Object handlerAvailable(Context context,String action){
        try{return new Intent(action).resolveActivity(context.getPackageManager())!=null;}
        catch(RuntimeException unavailable){return JSONObject.NULL;}
    }
    static void invalidate(){if(current!=null&&current.active())current.cancel("capture_authorization_lost");}
    static MediaCapture get(String id){return current!=null&&current.id.equals(id)?current:null;}
    static JSONObject start(Context c,String owner,JSONObject q,BooleanSupplier authority)throws Exception{
        if(current!=null&&current.active())throw new ScopedUi.Refused("capture_busy");
        recoverPending(c);
        String kind=q.getString("kind");long seconds=ServiceProtocol.integer(q,"seconds");
        if(!java.util.Set.of("photo","video","audio","screen").contains(kind)||seconds<1||seconds>60)throw new ScopedUi.Refused("invalid_request");
        MediaCapture next=new MediaCapture(c,owner,kind,(int)seconds,authority);current=next;
        next.handler.postDelayed(next.deadline,180000);next.handler.postDelayed(next.fence,250);
        try{c.startActivity(new Intent(c,MediaCaptureActivity.class).putExtra("capture_id",next.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
        catch(Exception failure){next.cancel("capture_ui_unavailable");throw new ScopedUi.Refused("capture_ui_unavailable");}
        return next.describe();
    }
    static JSONObject status(String owner,String id)throws Exception{
        MediaCapture capture=get(id);if(capture==null||!capture.owner.equals(owner))throw new ScopedUi.Refused("capture_not_found");
        return capture.describe();
    }
    static JSONObject stop(String owner,String id)throws Exception{
        MediaCapture capture=get(id);if(capture==null||!capture.owner.equals(owner))throw new ScopedUi.Refused("capture_not_found");
        if(capture.state.equals("recording")){if(capture.kind.equals("audio"))AudioCaptureService.finishCapture(id);else ScreenCaptureService.finishCapture(id);}
        else if(capture.active())capture.cancel("capture_canceled");
        return capture.describe();
    }
    static void recoverPending(Context c)throws Exception {
        if(current!=null&&current.active())return;
        EnrollmentStore pending=new EnrollmentStore(c,"media-pending");JSONObject old=pending.readRecord();
        if(old!=null){deleteOwned(c,Uri.parse(old.getString("uri")),old.getString("capture_id"));pending.forget();}
        for(java.io.File f:java.util.Objects.requireNonNullElse(c.getCacheDir().listFiles(),new java.io.File[0]))
            if(f.getName().matches("(?:airtap-capture-[0-9]+\\.part|airtap-output-[a-f0-9]{32}\\.bin)")&&!f.delete()&&f.exists())throw new java.io.IOException();
    }
    void createdOutput(Uri uri)throws Exception {
        output=uri;
        new EnrollmentStore(context,"media-pending").saveRecord(RelayApi.json("capture_id",id,"uri",uri.toString()));
    }
    private static void deleteOwned(Context c,Uri uri,String id)throws Exception {
        if(uri.equals(CaptureOutputProvider.uri(c,id))){
            c.revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            java.io.File file=CaptureOutputProvider.file(c,id);
            if(!file.delete()&&file.exists())throw new java.io.IOException();
            return;
        }
        if(!id.matches("[a-f0-9]{32}")||!"content".equals(uri.getScheme())||!"media".equals(uri.getAuthority())||uri.getQuery()!=null||uri.getFragment()!=null)throw new IllegalArgumentException();
        String[] fields={android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME,android.provider.MediaStore.MediaColumns.DISPLAY_NAME};
        try(android.database.Cursor row=c.getContentResolver().query(uri,fields,null,null,null)){
            if(row==null)throw new java.io.IOException();
            if(!row.moveToFirst())return;
            if(!c.getPackageName().equals(row.getString(0))||!row.getString(1).matches("Airtap-"+id+"\\.(?:jpg|mp4|m4a)"))throw new SecurityException();
        }
        c.getContentResolver().delete(uri,null,null);
    }
    JSONObject describe(){return RelayApi.json("accepted",true,"capture_id",id,"kind",kind,"state",state,"file",file==null?JSONObject.NULL:file,"error",error==null?JSONObject.NULL:error,
            "requested_seconds",seconds,"duration_enforced",kind.equals("screen")||kind.equals("audio"),
            "audio_included",kind.equals("screen")||kind.equals("photo")?false:kind.equals("audio")?true:JSONObject.NULL,
            "external_recorder",kind.equals("photo")||kind.equals("video"),"verification","inspect_output");}
    void captured(Uri uri){
        if(!active()||!authority.getAsBoolean()){cancel("capture_authorization_lost");return;}
        state="importing";
        String extension=switch(kind){case "photo"->"jpg";case "audio"->"m4a";default->"mp4";};
        FileScopes.importCapture(context,owner,id,extension,uri,()->active()&&authority.getAsBoolean(),new CompanionUi.Reply(){
            public void success(JSONObject value){if(!active())return;file=value;state="finished";cleanup();}
            public void error(String code){cancel(code);}
        });
    }
    void cancel(String code){if(!active())return;error=code;state="canceled";ScreenCaptureService.cancelCapture(id);AudioCaptureService.cancelCapture(id);cleanup();}
    private void cleanup(){
        handler.removeCallbacks(deadline);handler.removeCallbacks(fence);
        if(activity!=null){activity.finishActivity(1);activity.finish();activity=null;}
        // Only a row/file created by this capture; never delete a recorder's returned original.
        if(output!=null){try{deleteOwned(context,output,id);new EnrollmentStore(context,"media-pending").forget();}catch(Exception ignored){}output=null;}
    }
}
