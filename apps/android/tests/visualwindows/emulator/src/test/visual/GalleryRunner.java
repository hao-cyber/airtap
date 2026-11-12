package test.visual;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Isolated real MediaStore + separate-app Photo Picker tests; no real account, network, or private photos. */
public final class GalleryRunner extends Instrumentation {
    final JSONArray checks = new JSONArray();
    JSONObject publicationMetadata;
    final String owner = "b".repeat(32), path = "gallery-" + UUID.randomUUID() + ".png";
    final AtomicBoolean allowed = new AtomicBoolean(true), interruptPending = new AtomicBoolean(false);
    int pendingBaseline;
    final Runnable fence = () -> {
        if (!allowed.get()) throw new SecurityException("fixture revoked");
        if (interruptPending.get() && count(true) > pendingBaseline) { interruptPending.set(false); throw new IllegalStateException("fixture interrupt after insert"); }
    };
    Method run, validate;
    void check(String name, boolean okay) { checks.put(new JSONObject(Map.of("name",name,"passed",okay))); if(!okay)throw new AssertionError(name); }
    JSONObject q(String action) throws Exception { return new JSONObject().put("id",UUID.randomUUID().toString()).put("action",action); }
    JSONObject call(JSONObject q) throws Exception {
        validate.invoke(null,q);
        try { return (JSONObject)run.invoke(null,getTargetContext(),owner,q,fence,new CancellationSignal(),(java.util.function.BooleanSupplier)allowed::get); }
        catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw e;}
    }
    String hash(byte[] b) throws Exception { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }
    int count(boolean pending) {
        Bundle query=new Bundle();query.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,"relative_path=? AND is_pending=?");
        query.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,new String[]{"Pictures/Airtap/",pending?"1":"0"});
        query.putInt(MediaStore.QUERY_ARG_MATCH_PENDING,MediaStore.MATCH_INCLUDE);
        try(Cursor c=getTargetContext().getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,new String[]{"_id"},query,null)) { return c==null?-1:c.getCount(); }
    }
    Uri uri(String name) {
        try(Cursor c=getTargetContext().getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,new String[]{"_id"},"_display_name=?",new String[]{name},null)) {
            return c!=null&&c.moveToFirst()?ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,c.getLong(0)):null;
        }
    }
    void expect(String name,JSONObject command,String error) throws Exception {
        try { call(command);throw new AssertionError(name+" accepted"); }
        catch(Exception e){if(!String.valueOf(e).contains(error))throw e;check(name,true);}
    }
    AccessibilityNodeInfo node(String text, boolean photo) {
        var root=getUiAutomation().getRootInActiveWindow(); if(root==null)return null;
        ArrayDeque<AccessibilityNodeInfo> all=new ArrayDeque<>();all.add(root);
        while(!all.isEmpty()) {var n=all.remove();String value=String.valueOf(n.getText()),desc=String.valueOf(n.getContentDescription());
            if(photo ? desc.startsWith("Photo taken") || (n.getViewIdResourceName()!=null&&n.getViewIdResourceName().endsWith("/icon_thumbnail")) : value.equals(text)||desc.equals(text))return n;
            for(int i=0;i<n.getChildCount();i++){var child=n.getChild(i);if(child!=null)all.add(child);}
        }return null;
    }
    boolean clickTarget(AccessibilityNodeInfo node) {
        // Photo Picker exposes the thumbnail image separately from its clickable
        // tile. Use the advertised ancestor action; this is the independent
        // receiving-App oracle, not the Airtap executor under test.
        int window=node.getWindowId();
        for(int depth=0;node!=null&&depth<8;depth++,node=node.getParent()){
            if(node.getWindowId()!=window)return false;
            if(node.isClickable())return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        return false;
    }
    JSONArray oracleState(){
        JSONArray result=new JSONArray();var root=getUiAutomation().getRootInActiveWindow();if(root==null)return result;
        ArrayDeque<AccessibilityNodeInfo> nodes=new ArrayDeque<>();nodes.add(root);
        while(!nodes.isEmpty()&&result.length()<100){var n=nodes.remove();
            result.put(new JSONObject(Map.of("class",String.valueOf(n.getClassName()),"text",String.valueOf(n.getText()),
                    "description",String.valueOf(n.getContentDescription()),"clickable",n.isClickable(),"id",String.valueOf(n.getViewIdResourceName()))));
            for(int i=0;i<n.getChildCount();i++){var child=n.getChild(i);if(child!=null)nodes.add(child);}
        }return result;
    }
    void tests() throws Exception {
        pendingBaseline=count(true);
        Class<?> scopes=Class.forName("io.airtap.preview.FileScopes");
        validate=scopes.getDeclaredMethod("validate",JSONObject.class);validate.setAccessible(true);
        run=scopes.getDeclaredMethod("run",Context.class,String.class,JSONObject.class,Runnable.class,CancellationSignal.class,java.util.function.BooleanSupplier.class);run.setAccessible(true);
        Bitmap b=Bitmap.createBitmap(160,120,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(b);canvas.drawColor(Color.rgb(35,130,90));
        Paint p=new Paint();p.setColor(Color.WHITE);p.setTextSize(16);canvas.drawText("Airtap test",15,65,p);
        canvas.drawText(path.substring(8,16),15,100,p); // Distinct bytes prevent an older fixture photo from satisfying the hash oracle.
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,bytes);b.recycle();byte[] data=bytes.toByteArray();String digest=hash(data),id=UUID.randomUUID().toString().replace("-","");
        call(q("file_upload_begin").put("root","workspace").put("path",path).put("transfer_id",id).put("size",data.length).put("sha256",digest));
        call(q("file_upload_chunk").put("transfer_id",id).put("offset",0).put("sha256",digest).put("data_base64",Base64.getEncoder().encodeToString(data)));
        JSONObject file=call(q("file_upload_finish").put("transfer_id",id)).getJSONObject("file");
        JSONObject command=q("file_publish_media").put("root","workspace").put("path",path).put("version",file.getString("version")).put("mime_type","image/png");
        int before=count(false);
        expect("exact source version enforced",new JSONObject(command.toString()).put("version","0".repeat(64)),"file_changed");
        expect("decoded image type checked",new JSONObject(command.toString()).put("mime_type","image/jpeg"),"media_type_mismatch");
        allowed.set(false);expect("revoked task cannot publish",command,"fixture revoked");allowed.set(true);
        check("rejected operations create no gallery file",count(false)==before&&count(true)==pendingBaseline);
        interruptPending.set(true);expect("interrupted unpublished write reports failure",command,"fixture interrupt");
        check("interrupted pending row cleaned without public photo",count(false)==before&&count(true)==pendingBaseline);
        JSONObject result=call(command);
        check("MediaStore publication verifies bytes and metadata",result.getBoolean("published")&&result.getBoolean("readback_verified")&&result.getString("sha256").equals(digest)&&result.getInt("width")==160&&result.getInt("height")==120);
        try(Cursor indexed=getTargetContext().getContentResolver().query(uri(result.getString("display_name")),
                new String[]{"width","height","_size","is_pending","datetaken","date_added"},null,null,null)) {
            check("published media has indexed dimensions for receiving pickers",indexed!=null&&indexed.moveToFirst()
                    &&indexed.getInt(0)==160&&indexed.getInt(1)==120&&indexed.getLong(2)==data.length&&indexed.getInt(3)==0);
            publicationMetadata=new JSONObject().put("width",indexed.getInt(0)).put("height",indexed.getInt(1))
                    .put("bytes",indexed.getLong(2)).put("pending",indexed.getInt(3))
                    .put("date_taken_present",!indexed.isNull(4)).put("date_added_present",!indexed.isNull(5));
        }
        check("exactly one new gallery image",count(false)==before+1&&count(true)==pendingBaseline);
        JSONObject retry=call(command);
        check("same request reuses publication without duplicate",retry.getBoolean("reused")&&retry.getString("publication_id").equals(result.getString("publication_id"))&&count(false)==before+1);
        check("wire result contains no content URI or private path",!result.toString().contains("content://")&&!result.toString().contains(path));
        // The receiving App has its own package and no library permissions. Selection grants just the image.
        getUiAutomation();getContext().startActivity(new Intent().setClassName("io.airtap.fixture.visual","test.visual.VisualActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("picker",true));
        AccessibilityNodeInfo thumbnail=null;
        for(int i=0;i<35&&thumbnail==null;i++){Thread.sleep(200);thumbnail=node("",true);}
        check("separate App system photo picker displays media",thumbnail!=null);
        if(!clickTarget(thumbnail))throw new AssertionError("picker thumbnail click");
        AccessibilityNodeInfo selected=null;boolean confirmed=false;
        for(int i=0;i<30&&selected==null;i++){
            Thread.sleep(200);selected=node("Selected SHA256 "+digest,false);
            if(selected==null&&!confirmed){
                var add=node("Add",false);if(add==null)add=node("Done",false);
                if(add!=null){if(!clickTarget(add))throw new AssertionError("picker selection confirmation");confirmed=true;}
            }
        }
        check("separate App reads selected image with identical hash",selected!=null);
        Uri published=uri(result.getString("display_name"));check("published image remains after request completion",published!=null);
        getTargetContext().getContentResolver().delete(published,null,null);
        expect("owner-deleted photo is not recreated by late retry",command,"media_publication_removed");
        check("deleted photo remains absent",count(false)==before);
    }
    @Override public void onCreate(Bundle b){super.onCreate(b);start();}
    @Override public void onStart(){Bundle result=new Bundle();JSONObject report=new JSONObject();
        try{tests();report.put("complete",true);}catch(Throwable e){try{report.put("complete",false).put("error",e.toString()).put("oracle_state",oracleState());StringWriter out=new StringWriter();e.printStackTrace(new PrintWriter(out));report.put("stack",out.toString());}catch(Exception ignored){}}
        try{report.put("checks",checks).put("sdk",Build.VERSION.SDK_INT).put("publication_metadata",publicationMetadata);}catch(Exception ignored){}result.putString("report",report.toString());finish(report.optBoolean("complete")?-1:0,result);
    }
}
