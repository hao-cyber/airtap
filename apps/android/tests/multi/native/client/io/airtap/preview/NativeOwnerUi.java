package io.airtap.preview;

import android.content.*;
import android.graphics.Rect;
import android.view.*;
import android.view.inspector.WindowInspector;
import android.widget.*;
import org.json.*;
import java.nio.charset.StandardCharsets;

/** Test-package-only inspection of its own real Activity/dialog views, never an accessibility bypass. */
public final class NativeOwnerUi extends BroadcastReceiver {
    private static MainActivity activity(View view) {
        Context context=view.getContext();
        while(context instanceof ContextWrapper){
            if(context instanceof MainActivity)return (MainActivity)context;
            Context next=((ContextWrapper)context).getBaseContext();if(next==context)break;context=next;
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){MainActivity found=activity(group.getChildAt(i));if(found!=null)return found;}}
        return null;
    }
    private static Object field(Object object,String name)throws Exception {
        java.lang.reflect.Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private static void walk(View view,JSONArray items,String scroll,String click,int[] clicks) throws Exception {
        if(view instanceof TextView){
            String text=((TextView)view).getText().toString();
            if(!text.isEmpty()){
                if(clicks[0]==0&&text.equals(click)&&view instanceof Button&&view.isShown()&&view.isEnabled()){view.performClick();clicks[0]++;}
                if(text.equals(scroll))view.requestRectangleOnScreen(new Rect(0,0,view.getWidth(),view.getHeight()),true);
                Rect r=new Rect();boolean visible=view.getGlobalVisibleRect(r)&&view.isShown();
                int[] origin=new int[2];view.getRootView().getLocationOnScreen(origin);r.offset(origin[0],origin[1]);
                items.put(new JSONObject().put("text",text).put("button",view instanceof Button).put("enabled",view.isEnabled())
                    .put("visible",visible).put("bounds",new JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom)));
            }
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)walk(group.getChildAt(i),items,scroll,click,clicks);}
    }
    @Override public void onReceive(Context c,Intent intent){
        JSONObject out=new JSONObject();
        try{
            out.put("request_id",intent.getStringExtra("request_id"));JSONArray items=new JSONArray();int[] clicks={0};MainActivity main=null;
            for(View root:WindowInspector.getGlobalWindowViews()){
                MainActivity found=activity(root);if(found!=null&&!found.isDestroyed())main=found;
                walk(root,items,intent.getStringExtra("scroll"),intent.getStringExtra("click"),clicks);
            }
            if(main!=null){
                Object owner=field(main,"multiOwner");
                // Read-only diagnostics; never change production state, gates or request scheduling.
                out.put("owner_state",new JSONObject().put("remote_known",field(owner,"remote")!=null).put("busy",field(owner,"busy"))
                    .put("visible",field(owner,"visible")).put("invitation_read",field(owner,"invitationRead")!=null).put("generation",field(owner,"generation")));
                if(intent.getBooleanExtra("recreate",false)){main.recreate();out.put("recreated",true);}
            }
            out.put("items",items).put("clicks",clicks[0]).put("ok",true);
        }catch(Exception e){try{out.put("ok",false).put("error",e.getClass().getSimpleName());}catch(Exception ignored){}}
        try(java.io.FileOutputStream f=c.openFileOutput("native-ui.json",Context.MODE_PRIVATE)){f.write(out.toString().getBytes(StandardCharsets.UTF_8));}
        catch(Exception e){throw new RuntimeException(e);}
    }
}
