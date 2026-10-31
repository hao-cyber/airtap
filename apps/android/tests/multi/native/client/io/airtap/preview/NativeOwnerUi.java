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
    private static void walk(View view,JSONArray items,String scroll,String click) throws Exception {
        if(view instanceof TextView){
            String text=((TextView)view).getText().toString();
            if(!text.isEmpty()){
                if(text.equals(click)&&view instanceof Button&&view.isShown()&&view.isEnabled())view.performClick();
                if(text.equals(scroll))view.requestRectangleOnScreen(new Rect(0,0,view.getWidth(),view.getHeight()),true);
                Rect r=new Rect();boolean visible=view.getGlobalVisibleRect(r)&&view.isShown();
                int[] origin=new int[2];view.getRootView().getLocationOnScreen(origin);r.offset(origin[0],origin[1]);
                items.put(new JSONObject().put("text",text).put("button",view instanceof Button).put("enabled",view.isEnabled())
                    .put("visible",visible).put("bounds",new JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom)));
            }
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)walk(group.getChildAt(i),items,scroll,click);}
    }
    @Override public void onReceive(Context c,Intent intent){
        JSONObject out=new JSONObject();
        try{
            out.put("request_id",intent.getStringExtra("request_id"));JSONArray items=new JSONArray();
            for(View root:WindowInspector.getGlobalWindowViews())walk(root,items,intent.getStringExtra("scroll"),intent.getStringExtra("click"));
            out.put("items",items).put("ok",true);
        }catch(Exception e){try{out.put("ok",false).put("error",e.getClass().getSimpleName());}catch(Exception ignored){}}
        try(java.io.FileOutputStream f=c.openFileOutput("native-ui.json",Context.MODE_PRIVATE)){f.write(out.toString().getBytes(StandardCharsets.UTF_8));}
        catch(Exception e){throw new RuntimeException(e);}
    }
}
