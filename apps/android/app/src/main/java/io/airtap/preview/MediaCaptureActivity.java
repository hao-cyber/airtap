package io.airtap.preview;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.media.projection.MediaProjectionManager;

/** Normal Android capture/consent UI. Never auto-accept permissions or replay a consumed projection token. */
public final class MediaCaptureActivity extends Activity {
    private MediaCapture capture;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        capture=MediaCapture.get(getIntent().getStringExtra("capture_id"));
        if(capture==null||!capture.active()||!capture.authority.getAsBoolean()){finish();return;}
        capture.activity=this;
        if(state!=null)return;
        try{
            Intent intent;
            switch(capture.kind){
                case "screen": intent=getSystemService(MediaProjectionManager.class).createScreenCaptureIntent();break;
                case "audio":
                    if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                        requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},2);
                    else getWindow().getDecorView().post(this::startAudio);
                    return;
                default:
                    boolean video=capture.kind.equals("video");
                    capture.createdOutput(CaptureOutputProvider.uri(this,capture.id));
                    if(!CaptureOutputProvider.file(this,capture.id).createNewFile())throw new IllegalStateException();
                    intent=new Intent(video?MediaStore.ACTION_VIDEO_CAPTURE:MediaStore.ACTION_IMAGE_CAPTURE)
                        .putExtra(MediaStore.EXTRA_OUTPUT,capture.output).putExtra(MediaStore.EXTRA_DURATION_LIMIT,capture.seconds)
                        .putExtra(MediaStore.EXTRA_SIZE_LIMIT,MediaCapture.MAX_BYTES).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    intent.setClipData(android.content.ClipData.newRawUri("Airtap capture",capture.output));break;
            }
            startActivityForResult(intent,1);
        }catch(Exception e){capture.cancel("capture_handler_unavailable");finish();}
    }
    private void startAudio(){
        if(capture==null||!capture.active()||!capture.authority.getAsBoolean()){finish();return;}
        try{startForegroundService(new Intent(this,AudioCaptureService.class).putExtra("capture_id",capture.id));}
        catch(Exception failure){capture.cancel("capture_start_denied");}
        // Keep this Activity visible until the microphone service has started.
        // Android checks while-in-use microphone permission at foreground-service creation.
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request!=2)return;
        if(results.length==1&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)startAudio();
        else{if(capture!=null)capture.cancel("capture_permission_missing");finish();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(capture==null||!capture.active()){finish();return;}
        if(result!=RESULT_OK){capture.cancel("capture_canceled");finish();return;}
        if(capture.kind.equals("screen")){
            if(data==null){capture.cancel("capture_permission_missing");finish();return;}
            try{
                Intent service=new Intent(this,ScreenCaptureService.class).putExtra("capture_id",capture.id).putExtra("result_code",result).putExtra("projection",data);
                startForegroundService(service);
            }catch(Exception e){capture.cancel("capture_start_denied");}
            capture.activity=null;finish();return;
        }
        Uri uri=capture.output!=null?capture.output:data==null?null:data.getData();
        if(uri==null||!"content".equals(uri.getScheme()))capture.cancel("capture_result_unavailable");else capture.captured(uri);
        capture.activity=null;finish();
    }
}
