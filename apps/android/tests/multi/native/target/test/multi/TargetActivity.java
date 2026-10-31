package test.multi;

import android.app.Activity;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.*;

/** Synthetic app records actual MotionEvent delivery; it never executes Airtap commands. */
public final class TargetActivity extends Activity {
    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(30,50,30,30);
        TextView title=new TextView(this);title.setText("Native multi-controller target");title.setTextSize(22);root.addView(title);
        EditText edit=new EditText(this);edit.setText("Synthetic text");edit.setContentDescription("Synthetic editor");root.addView(edit);
        Button button=new Button(this);button.setText("Synthetic button");button.setOnClickListener(v->record("button",null));root.addView(button);
        TextView surface=new TextView(this);surface.setText("Gesture surface\nActual Android input events");surface.setTextSize(24);surface.setBackgroundColor(0xffbbddff);
        surface.setOnTouchListener((v,e)->{record("touch",e);return true;});root.addView(surface,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
    }
    private void record(String kind,MotionEvent event){try{
        JSONObject r=new JSONObject().put("kind",kind).put("elapsed_ms",android.os.SystemClock.elapsedRealtime());
        if(event!=null)r.put("action",event.getActionMasked()).put("event_time",event.getEventTime()).put("pointers",event.getPointerCount()).put("x",event.getX()).put("y",event.getY());
        try(java.io.FileOutputStream out=openFileOutput("events.jsonl",MODE_APPEND)){out.write((r+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    }catch(Exception failure){throw new RuntimeException(failure);}}
}
