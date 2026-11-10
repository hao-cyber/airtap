package test.extensions;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;
public final class ExtensionActivity extends Activity {
 @Override public void onCreate(Bundle b){super.onCreate(b);render(getIntent());}
 @Override public void onNewIntent(Intent i){super.onNewIntent(i);render(i);}
 void render(Intent intent){
  getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  View view=new View(this){protected void onDraw(android.graphics.Canvas c){c.drawColor(0xffcceedd);}public boolean onTouchEvent(MotionEvent e){sendBroadcast(new Intent("io.airtap.fixture.TOUCH").setPackage("io.airtap.preview").putExtra("action",e.getActionMasked()));return true;}};
  view.setContentDescription("Synthetic gesture canvas");view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);setContentView(view);
  if(intent.getBooleanExtra("notify",false)){
   NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("fixture","Fixture",NotificationManager.IMPORTANCE_DEFAULT));
   manager.notify(7,new Notification.Builder(this,"fixture").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Synthetic event").setContentText("SYNTHETIC_EVENT_PAYLOAD").build());
  }
 }
}
