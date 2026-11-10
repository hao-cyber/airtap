package io.airtap.preview;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Opt-in entry into an identified, normal SIM-PIN keypad. No telephony bypass, PUK, biometric, or PIN guessing. */
final class SimPinInput {
    private static final String UI="com.android.systemui", PREFIX=UI+":id/";
    private final AccessibilityService service;
    private String challenge,owner;
    private int window,slot;
    private long deadline;
    private final Handler main=new Handler(Looper.getMainLooper());
    SimPinInput(AccessibilityService service){this.service=service;}
    static boolean enabled(Context c){return c.getSharedPreferences("sim-pin-entry",0).getBoolean("enabled",false);}
    static void configure(Context c,boolean enabled)throws Exception{
        if(!c.getSharedPreferences("sim-pin-entry",0).edit().putBoolean("enabled",enabled).putBoolean("attempt_pending",false).commit())throw new IllegalStateException();
    }
    private android.content.SharedPreferences prefs(){return service.getSharedPreferences("sim-pin-entry",0);}
    private int sim(int index){return service.getSystemService(TelephonyManager.class).getSimState(index);}
    private AccessibilityNodeInfo node(AccessibilityNodeInfo root,String id,boolean click){
        var matches=root.findAccessibilityNodeInfosByViewId(PREFIX+id);AccessibilityNodeInfo found=null;
        for(var n:matches)if(n.isVisibleToUser()&&n.isEnabled()&&(!click||n.isClickable())){
            if(found!=null)throw new ScopedUi.Refused("sim_pin_ui_unsupported");found=n;
        }
        if(found==null)throw new ScopedUi.Refused("sim_pin_ui_unsupported");return found;
    }
    private AccessibilityNodeInfo keypad(){
        if(!enabled(service))throw new ScopedUi.Refused("sim_pin_entry_not_enabled");
        KeyguardManager keyguard=service.getSystemService(KeyguardManager.class);
        if(keyguard.isDeviceSecure()||!keyguard.isKeyguardLocked())throw new ScopedUi.Refused("sim_pin_ui_unsupported");
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        if(root==null||!UI.contentEquals(root.getPackageName()==null?"":root.getPackageName()))throw new ScopedUi.Refused("sim_pin_ui_unsupported");
        node(root,"simPinEntry",false);for(int i=0;i<10;i++)node(root,"key"+i,true);node(root,"key_enter",true);return root;
    }
    JSONObject prepare(String claim)throws Exception{
        if(!enabled(service))throw new ScopedUi.Refused("sim_pin_entry_not_enabled");
        if(prefs().getBoolean("attempt_pending",false))throw new ScopedUi.Refused("sim_pin_previous_attempt_unresolved");
        TelephonyManager telephony=service.getSystemService(TelephonyManager.class);int selected=-1;
        for(int i=0;i<telephony.getActiveModemCount();i++){
            if(sim(i)==TelephonyManager.SIM_STATE_PUK_REQUIRED)throw new ScopedUi.Refused("sim_puk_requires_owner");
            if(sim(i)==TelephonyManager.SIM_STATE_PIN_REQUIRED){if(selected!=-1)throw new ScopedUi.Refused("sim_pin_multiple_locked_slots");selected=i;}
        }
        if(selected<0)throw new ScopedUi.Refused("sim_pin_not_required");
        PowerManager pm=service.getSystemService(PowerManager.class);
        if(!pm.isInteractive()){
            var wake=pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK|PowerManager.ACQUIRE_CAUSES_WAKEUP,"Airtap:sim-pin");wake.acquire(3000);
            throw new ScopedUi.Refused("sim_pin_screen_preparing");
        }
        AccessibilityNodeInfo root=keypad(),entry=node(root,"simPinEntry",false);
        if(entry.getText()!=null&&entry.getText().length()!=0)throw new ScopedUi.Refused("sim_pin_entry_not_empty");
        slot=selected;window=root.getWindowId();owner=claim;challenge=EnrollmentStore.token().substring(0,32);deadline=SystemClock.elapsedRealtime()+30000;
        return RelayApi.json("challenge",challenge,"slot_index",slot,"expires_in_ms",30000,"method","normal_system_keypad","attempts",1,"stores_pin",false);
    }
    void submit(String claim,JSONObject q,BooleanSupplier allowed,CompanionUi.Reply reply)throws Exception{
        String raw=q.getString("pin");q.remove("pin");
        if(!raw.matches("[0-9]{4,8}"))throw new ScopedUi.Refused("invalid_sim_pin_format");
        if(challenge==null||!challenge.equals(q.getString("challenge"))||!claim.equals(owner)||SystemClock.elapsedRealtime()>=deadline)throw new ScopedUi.Refused("sim_pin_challenge_expired");
        AccessibilityNodeInfo root=keypad(),entry=node(root,"simPinEntry",false);
        if(root.getWindowId()!=window||sim(slot)!=TelephonyManager.SIM_STATE_PIN_REQUIRED||entry.getText()!=null&&entry.getText().length()!=0)throw new ScopedUi.Refused("sim_pin_ui_changed");
        if(!allowed.getAsBoolean())throw new ScopedUi.Refused("permission_denied");
        if(!prefs().edit().putBoolean("attempt_pending",true).commit())throw new ScopedUi.Refused("credential_storage_unavailable");
        challenge=null;char[] pin=raw.toCharArray();
        main.post(new Runnable(){int position;boolean submitted;final long end=SystemClock.elapsedRealtime()+9000;
            public void run(){
                try{
                    if(!allowed.getAsBoolean()||!enabled(service))throw new ScopedUi.Refused("permission_denied");
                    if(submitted){
                        if(sim(slot)==TelephonyManager.SIM_STATE_READY){prefs().edit().putBoolean("attempt_pending",false).commit();reply.success(RelayApi.json("accepted",true,"sim_ready",true,"verification","telephony_sim_ready"));return;}
                        if(SystemClock.elapsedRealtime()>=end){reply.error("sim_pin_result_unknown_no_retry");return;}
                        main.postDelayed(this,250);return;
                    }
                    AccessibilityNodeInfo live=keypad();if(live.getWindowId()!=window||sim(slot)!=TelephonyManager.SIM_STATE_PIN_REQUIRED)throw new ScopedUi.Refused("sim_pin_ui_changed");
                    CharSequence text=node(live,"simPinEntry",false).getText();
                    if((text==null?0:text.length())!=position)throw new ScopedUi.Refused("sim_pin_entry_changed");
                    String key=position<pin.length?"key"+pin[position]:"key_enter";
                    if(!node(live,key,true).performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new ScopedUi.Refused("sim_pin_input_unconfirmed");
                    if(position++==pin.length){submitted=true;Arrays.fill(pin,'\0');}
                    main.postDelayed(this,80);
                }catch(Exception failure){Arrays.fill(pin,'\0');reply.error(failure instanceof ScopedUi.Refused?((ScopedUi.Refused)failure).code:"sim_pin_result_unknown_no_retry");}
            }
        });
    }
}
