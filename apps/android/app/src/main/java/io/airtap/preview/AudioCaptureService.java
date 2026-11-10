package io.airtap.preview;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.*;
import android.provider.MediaStore;

/** Bounded microphone recording started from a visible Activity after Android runtime consent. */
public final class AudioCaptureService extends Service {
    private static AudioCaptureService running;
    private MediaCapture capture;
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
            NotificationManager manager=getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("airtap-recording","录音与录屏",NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop=PendingIntent.getService(this,0,new Intent(this,AudioCaptureService.class).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            startForeground(703,new Notification.Builder(this,"airtap-recording").setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("Airtap 正在录音").setOngoing(true).addAction(new Notification.Action.Builder(null,"停止录音",stop).build()).build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            var values=new android.content.ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME,"Airtap-"+capture.id+".m4a");values.put(MediaStore.MediaColumns.MIME_TYPE,"audio/mp4");values.put(MediaStore.MediaColumns.IS_PENDING,1);
            output=getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,values);
            if(output==null)throw new IllegalStateException();capture.createdOutput(output);
            fd=getContentResolver().openFileDescriptor(output,"w");if(fd==null)throw new IllegalStateException();
            recorder=new MediaRecorder(this);recorder.setAudioSource(MediaRecorder.AudioSource.MIC);recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);recorder.setAudioSamplingRate(44100);recorder.setAudioEncodingBitRate(96000);
            recorder.setOutputFile(fd.getFileDescriptor());recorder.setMaxDuration(capture.seconds*1000);recorder.setMaxFileSize(MediaCapture.MAX_BYTES);
            recorder.setOnInfoListener((r,what,extra)->{if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED||what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)finish(true);});
            recorder.setOnErrorListener((r,what,extra)->finish(false));recorder.prepare();recorder.start();started=true;capture.state="recording";
            if(capture.activity!=null){capture.activity.finish();capture.activity=null;}
            main.postDelayed(()->finish(true),capture.seconds*1000L);
        }catch(Exception failure){finish(false);}
        return START_NOT_STICKY;
    }
    private void finish(boolean keep){
        if(finishing)return;finishing=true;main.removeCallbacksAndMessages(null);boolean valid=keep&&started;
        try{if(started)recorder.stop();}catch(Exception error){valid=false;}
        if(recorder!=null)recorder.release();try{if(fd!=null)fd.close();}catch(Exception ignored){}
        if(capture!=null&&capture.active()){if(valid)capture.captured(output);else capture.cancel("capture_interrupted");}
        running=null;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    @Override public void onDestroy(){finish(false);super.onDestroy();}
}
