package io.airtap.preview;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;

/** Silent video only. Every session obtains a fresh, explicit Android projection consent. */
public final class ScreenCaptureService extends Service {
    private static ScreenCaptureService running;
    private MediaCapture capture;
    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaRecorder recorder;
    private android.net.Uri output;
    private ParcelFileDescriptor fd;
    private boolean started,finishing;
    private final Handler main=new Handler(Looper.getMainLooper());
    static void finishCapture(String id){if(running!=null&&running.capture!=null&&running.capture.id.equals(id))running.finish(true);}
    static void cancelCapture(String id){if(running!=null&&running.capture!=null&&running.capture.id.equals(id))running.finish(false);}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&"stop".equals(intent.getAction())){finish(true);return START_NOT_STICKY;}
        if(running!=null||intent==null){stopSelf();return START_NOT_STICKY;}
        capture=MediaCapture.get(intent.getStringExtra("capture_id"));
        if(capture==null||!capture.active()||!capture.authority.getAsBoolean()){stopSelf();return START_NOT_STICKY;}
        running=this;
        try{
            NotificationManager nm=getSystemService(NotificationManager.class);String channel="airtap-recording";
            nm.createNotificationChannel(new NotificationChannel(channel,"录屏",NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop=PendingIntent.getService(this,0,new Intent(this,ScreenCaptureService.class).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            startForeground(702,new Notification.Builder(this,channel).setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Airtap 正在录屏（无声）").setOngoing(true).addAction(new Notification.Action.Builder(null,"停止录屏",stop).build()).build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            Intent consent=intent.getParcelableExtra("projection",Intent.class);
            if(consent==null)throw new IllegalStateException();
            projection=getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("result_code",Activity.RESULT_CANCELED),consent);
            projection.registerCallback(new MediaProjection.Callback(){@Override public void onStop(){finish(true);}},main);
            android.content.ContentValues values=new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"Airtap-"+capture.id+".mp4");values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"video/mp4");values.put(android.provider.MediaStore.MediaColumns.IS_PENDING,1);
            output=getContentResolver().insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values);if(output!=null)capture.createdOutput(output);
            if(output==null)throw new IllegalStateException();fd=getContentResolver().openFileDescriptor(output,"w");if(fd==null)throw new IllegalStateException();
            var metrics=getResources().getDisplayMetrics();var bounds=getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
            float scale=Math.min(1f,720f/Math.max(1,bounds.width()));int width=Math.max(2,((int)(bounds.width()*scale))&~1),height=Math.max(2,((int)(bounds.height()*scale))&~1);
            recorder=new MediaRecorder(this);recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(width,height);recorder.setVideoFrameRate(24);recorder.setVideoEncodingBitRate(2000000);recorder.setOutputFile(fd.getFileDescriptor());recorder.setMaxFileSize(MediaCapture.MAX_BYTES);recorder.setMaxDuration(capture.seconds*1000);
            recorder.setOnInfoListener((r,what,extra)->{if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED||what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)finish(true);});recorder.setOnErrorListener((r,what,extra)->finish(false));recorder.prepare();
            display=projection.createVirtualDisplay("Airtap capture",width,height,metrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,recorder.getSurface(),null,main);
            recorder.start();started=true;capture.state="recording";main.postDelayed(()->finish(true),capture.seconds*1000L);
        }catch(Exception e){finish(false);}
        return START_NOT_STICKY;
    }
    private void finish(boolean keep){
        if(finishing)return;finishing=true;main.removeCallbacksAndMessages(null);boolean valid=keep&&started;
        try{if(started)recorder.stop();}catch(Exception e){valid=false;}
        if(display!=null)display.release();if(recorder!=null)recorder.release();if(projection!=null)projection.stop();try{if(fd!=null)fd.close();}catch(Exception ignored){}
        if(capture!=null&&capture.active()){if(valid)capture.captured(output);else capture.cancel("capture_interrupted");}
        running=null;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    @Override public void onDestroy(){finish(false);super.onDestroy();}
}
