package io.airtap.preview;

import android.content.*;
import android.content.pm.PackageManager;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.os.storage.StorageManager;
import android.provider.OpenableColumns;
import android.system.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;

/** Non-exported provider; Android grants a selected recipient temporary read access to one file. */
public final class WorkspaceShareProvider extends ContentProvider {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final HandlerThread IO=new HandlerThread("airtap-share-reader");
    static {IO.start();}
    @Override public boolean onCreate(){return true;}
    private WorkspaceShares.Ticket ticket(Uri uri,int pid,int uid)throws FileNotFoundException {
        Context c=getContext();
        if(uid!=android.os.Process.myUid()&&c.checkUriPermission(uri,pid,uid,Intent.FLAG_GRANT_READ_URI_PERMISSION)!=PackageManager.PERMISSION_GRANTED)
            throw new FileNotFoundException("share_permission_denied");
        FutureTask<WorkspaceShares.Ticket> f=new FutureTask<>(()->WorkspaceShares.current(c,uri));
        if(Looper.myLooper()==Looper.getMainLooper())f.run();else MAIN.post(f);
        try{return f.get(5,TimeUnit.SECONDS);}catch(Exception e){f.cancel(false);throw new FileNotFoundException("share_expired_or_revoked");}
    }
    private WorkspaceShares.Ticket ticket(Uri uri)throws FileNotFoundException{return ticket(uri,Binder.getCallingPid(),Binder.getCallingUid());}
    @Override public String getType(Uri uri){try{return ticket(uri).mime;}catch(FileNotFoundException e){return null;}}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
        if(selection!=null||args!=null||sort!=null)throw new IllegalArgumentException("unsupported_query");
        try{
            var t=ticket(uri);String[] cols=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
            MatrixCursor result=new MatrixCursor(cols,1);Object[] row=new Object[cols.length];
            for(int i=0;i<cols.length;i++)row[i]=cols[i].equals(OpenableColumns.DISPLAY_NAME)?t.name:cols[i].equals(OpenableColumns.SIZE)?t.size:null;
            result.addRow(row);return result;
        }catch(FileNotFoundException e){throw new SecurityException("share_unavailable");}
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException("share_read_only");
        int pid=Binder.getCallingPid(),uid=Binder.getCallingUid();var t=ticket(uri,pid,uid);
        final FileScopes.SharedReader reader;
        try{reader=FileScopes.sharedReader(getContext(),t,()->{try{ticket(uri,pid,uid);}catch(IOException e){throw new SecurityException("share_revoked");}});}catch(Exception e){throw new FileNotFoundException("share_unavailable");}
        try{return getContext().getSystemService(StorageManager.class).openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,new ProxyFileDescriptorCallback(){
            private void check()throws ErrnoException{try{ticket(uri,pid,uid);}catch(Exception e){throw new ErrnoException("share_revoked",OsConstants.EACCES);}}
            @Override public long onGetSize()throws ErrnoException{check();return t.size;}
            @Override public int onRead(long offset,int size,byte[] data)throws ErrnoException{
                check();try{
                    // Re-open within the scope for every bounded read; no retained raw FD survives stop/cleanup.
                    if(offset<0||size<0)throw new IOException();
                    int wanted=(int)Math.min(Math.min(size,data.length),Math.max(0,t.size-offset));
                    int copied=0;
                    // FUSE treats a short reply as EOF: fill its entire request, fencing each 4 KiB.
                    while(copied<wanted){
                        FileTransferStore.Chunk chunk=reader.read(offset+copied,Math.min(wanted-copied,FileTransferStore.MAX_CHUNK));
                        check();if(chunk.data().length==0)throw new IOException("file_changed");
                        System.arraycopy(chunk.data(),0,data,copied,chunk.data().length);copied+=chunk.data().length;
                    }
                    return copied;
                }catch(Exception e){throw new ErrnoException("share_unavailable",OsConstants.EIO);}
            }
            @Override public void onRelease(){}
        },new Handler(IO.getLooper()));}catch(IOException e){throw new FileNotFoundException("share_unavailable");}
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new SecurityException("share_read_only");}
    @Override public int delete(Uri uri,String where,String[] args){throw new SecurityException("share_read_only");}
    @Override public int update(Uri uri,ContentValues values,String where,String[] args){throw new SecurityException("share_read_only");}
}
