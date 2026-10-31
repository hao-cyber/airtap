package test.multi;
import android.content.*;
public final class ConfigureReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){int delay=i.getIntExtra("delay_ms",0);if(delay<0||delay>20000)throw new IllegalArgumentException();c.getSharedPreferences("native",0).edit().putInt("delay_ms",delay).commit();}
}
