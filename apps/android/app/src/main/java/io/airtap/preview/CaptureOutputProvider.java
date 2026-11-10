package io.airtap.preview;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileNotFoundException;

/** A single capture output, granted to the selected system camera by Android's URI grant. */
public final class CaptureOutputProvider extends ContentProvider {
    static Uri uri(Context context,String id) {
        if(!id.matches("[a-f0-9]{32}"))throw new IllegalArgumentException();
        return new Uri.Builder().scheme("content").authority(context.getPackageName()+".capture-output").appendPath(id).build();
    }
    static File file(Context context,String id) {
        if(!id.matches("[a-f0-9]{32}"))throw new IllegalArgumentException();
        return new File(context.getCacheDir(),"airtap-output-"+id+".bin");
    }
    private String identity(Uri uri)throws FileNotFoundException {
        if(uri==null||!"content".equals(uri.getScheme())||!(getContext().getPackageName()+".capture-output").equals(uri.getAuthority())
                ||uri.getQuery()!=null||uri.getFragment()!=null||uri.getPathSegments().size()!=1
                ||!uri.getLastPathSegment().matches("[a-f0-9]{32}"))throw new FileNotFoundException("capture_unavailable");
        return uri.getLastPathSegment();
    }
    @Override public boolean onCreate(){return true;}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
        String id=identity(uri);
        boolean writing=mode.equals("w")||mode.equals("wt")||mode.equals("rw")||mode.equals("rwt");
        if(!writing&&!mode.equals("r"))throw new FileNotFoundException("capture_mode_unavailable");
        try {
            FileScopes.onMain(()->{
                MediaCapture capture=MediaCapture.get(id);
                if(capture==null||!capture.active()||!uri.equals(capture.output)||!capture.authority.getAsBoolean()
                        ||writing&&!capture.state.equals("awaiting_system_ui"))throw new ScopedUi.Refused("capture_unavailable");
            });
            int access=mode.startsWith("rw")?OsConstants.O_RDWR:writing?OsConstants.O_WRONLY:OsConstants.O_RDONLY;
            int truncate=mode.equals("w")||mode.equals("wt")||mode.equals("rwt")?OsConstants.O_TRUNC:0;
            var fd=Os.open(file(getContext(),id).toString(),access|truncate|OsConstants.O_NOFOLLOW,0600);
            try {
                if(!OsConstants.S_ISREG(Os.fstat(fd).st_mode))throw new FileNotFoundException("capture_unavailable");
                return ParcelFileDescriptor.dup(fd);
            }finally{Os.close(fd);}
        }catch(Exception failure){throw new FileNotFoundException("capture_unavailable");}
    }
    @Override public String getType(Uri uri){
        try{MediaCapture capture=MediaCapture.get(identity(uri));return capture!=null&&capture.kind.equals("photo")?"image/jpeg":"video/mp4";}
        catch(Exception unavailable){return "application/octet-stream";}
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){return null;}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
}
