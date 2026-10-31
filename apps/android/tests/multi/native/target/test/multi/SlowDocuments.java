package test.multi;

import android.database.*;import android.os.*;import android.provider.DocumentsContract;import android.provider.DocumentsProvider;import java.io.*;

/** Real Binder/SAF provider with a deliberately slow query, confined to synthetic files. */
public final class SlowDocuments extends DocumentsProvider {
    private static final String[] DOC={"document_id","_display_name","mime_type","_size","last_modified","flags"};
    @Override public boolean onCreate(){return true;}
    private void log(String event){try(FileOutputStream out=getContext().openFileOutput("provider-events.jsonl",android.content.Context.MODE_APPEND)){
        out.write(("{\"event\":\""+event+"\",\"elapsed_ms\":"+SystemClock.elapsedRealtime()+"}\n").getBytes());
    }catch(Exception e){throw new RuntimeException(e);}}
    @Override public Cursor queryRoots(String[] projection){MatrixCursor c=new MatrixCursor(new String[]{"root_id","document_id","title","flags"});c.addRow(new Object[]{"root","root","Synthetic native files",DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD});return c;}
    @Override public Cursor queryDocument(String id,String[] projection){MatrixCursor c=new MatrixCursor(DOC);c.addRow(new Object[]{id,id.equals("root")?"Native root":"synthetic.txt",id.equals("root")?DocumentsContract.Document.MIME_TYPE_DIR:"text/plain",15,1,0});return c;}
    @Override public Cursor queryChildDocuments(String parent,String[] projection,String sort){
        int delay=getContext().getSharedPreferences("native",0).getInt("delay_ms",0);log("query_started");
        if(delay>0)SystemClock.sleep(delay);
        MatrixCursor c=new MatrixCursor(DOC);c.addRow(new Object[]{"document","synthetic.txt","text/plain",15,1,0});log("query_finished");return c;
    }
    @Override public boolean isChildDocument(String parent,String child){return parent.equals("root")&&(child.equals("root")||child.equals("document"));}
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException{
        File file=new File(getContext().getFilesDir(),"synthetic.txt");try{if(!file.exists())try(FileOutputStream out=new FileOutputStream(file)){out.write("Synthetic bytes".getBytes());}}catch(Exception e){throw new FileNotFoundException();}
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
