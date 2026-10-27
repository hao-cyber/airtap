package test.pages;
import android.app.*;import android.os.*;import android.widget.*;import android.view.*;import android.content.*;
public final class PageActivity extends Activity {
 EditText longEditor;RangeView rangeView;BroadcastReceiver mutation;
 static final class RangeView extends View {
  int current=10;RangeView(Context context){super(context);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);setContentDescription("Range metadata fixture");}
  @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo node){super.onInitializeAccessibilityNodeInfo(node);node.setClassName("android.widget.SeekBar");node.setRangeInfo(android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(0,0,100,current));}
 }
 public static final String LONG=("文字🚀e\u0301").repeat(2000);
 @Override public void onCreate(Bundle b) {super.onCreate(b);render(getIntent());mutation=new BroadcastReceiver(){public void onReceive(Context c,Intent i){if(i.getBooleanExtra("range",false)){rangeView.current++;rangeView.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);}else longEditor.append("suffix changed");}};registerReceiver(mutation,new IntentFilter("io.airtap.fixture.pages.MUTATE"),Context.RECEIVER_EXPORTED);}
 @Override public void onDestroy(){if(mutation!=null)unregisterReceiver(mutation);super.onDestroy();}
 @Override public void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);render(i);}
 void render(Intent intent){FrameLayout root=new FrameLayout(this);int count=intent.getIntExtra("count",300);String variant=intent.getStringExtra("variant");
  for(int i=0;i<count;i++){TextView view=i==0?new EditText(this):new TextView(this);if(i==0)longEditor=(EditText)view;view.setId(10000+i);view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);view.setContentDescription("Page fixture node "+i);
   view.setText(i==0?LONG+("changed".equals(variant)?"different":""):"Node "+i);view.setTextSize(10);FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(200,40);lp.leftMargin=(i%5)*201;lp.topMargin=100+(i/5%40)*42;root.addView(view,lp);
  }
  EditText secret=new EditText(this);secret.setId(20000);secret.setInputType(129);secret.setText("PRIVATE_PASSWORD_MARKER_ABC");secret.setContentDescription("Private password description");secret.setHint("Private password hint");FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(500,80);lp.topMargin=1750;root.addView(secret,lp);root.setFocusableInTouchMode(true);root.requestFocus();setContentView(root);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  rangeView=new RangeView(this);rangeView.setId(20001);FrameLayout.LayoutParams rangeBounds=new FrameLayout.LayoutParams(500,60);rangeBounds.topMargin=1850;root.addView(rangeView,rangeBounds);
 }
}
