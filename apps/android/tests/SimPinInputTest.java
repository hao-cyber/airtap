package io.airtap.preview;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.os.Handler;
import android.os.PowerManager;
import android.telephony.TelephonyManager;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONObject;

public final class SimPinInputTest {
    static final String OWNER="a".repeat(32);
    static final String PREFIX="com.android.systemui:id/";
    static int passed;
    static void check(boolean value){if(!value)throw new AssertionError();}
    interface Action {void run() throws Exception;}
    static void refuses(String expected,Action action)throws Exception{
        try{action.run();throw new AssertionError("Expected "+expected);}catch(ScopedUi.Refused failure){check(expected.equals(failure.code));}
    }
    static void pass(String name){passed++;System.out.println("PASS "+name);}
    static final class Reply implements CompanionUi.Reply {
        int successes;String error;
        public void success(JSONObject result){successes++;}
        public void error(String code){error=code;}
    }
    static final class Fixture {
        final AccessibilityService service=new AccessibilityService();
        final TelephonyManager telephony=new TelephonyManager();
        final KeyguardManager keyguard=new KeyguardManager();
        final PowerManager power=new PowerManager();
        final AccessibilityNodeInfo root=new AccessibilityNodeInfo(),entry=new AccessibilityNodeInfo();
        final SimPinInput pin=new SimPinInput(service);
        int digits,enters;boolean permit=true,accept=true;
        Fixture()throws Exception{
            Handler.queue.clear();android.os.SystemClock.now=0;
            service.systems.put(TelephonyManager.class,telephony);service.systems.put(KeyguardManager.class,keyguard);service.systems.put(PowerManager.class,power);service.root=root;
            root.ids.put(PREFIX+"simPinEntry",entry);
            for(int i=0;i<10;i++){var button=new AccessibilityNodeInfo();button.click=()->{digits++;entry.text+="•";};root.ids.put(PREFIX+"key"+i,button);}
            var enter=new AccessibilityNodeInfo();enter.click=()->{enters++;if(accept)telephony.slots[0]=TelephonyManager.SIM_STATE_READY;};root.ids.put(PREFIX+"key_enter",enter);
        }
        void enable()throws Exception{SimPinInput.configure(service,true);}
        JSONObject input(String challenge){return new JSONObject().put("challenge",challenge).put("pin","24681357");}
        Reply submit()throws Exception{String challenge=pin.prepare(OWNER).getString("challenge");Reply reply=new Reply();JSONObject q=input(challenge);pin.submit(OWNER,q,()->permit,reply);check(!q.has("pin"));return reply;}
    }
    public static void main(String[] args)throws Exception{
        Fixture readOnly=new Fixture();readOnly.power.interactive=false;readOnly.telephony.slots=new int[]{2,3};
        JSONObject readiness=readOnly.pin.status();
        check(Boolean.FALSE.equals(readiness.get("enabled"))&&Integer.valueOf(1).equals(readiness.get("pin_locked_slots"))&&Integer.valueOf(1).equals(readiness.get("puk_locked_slots")));
        check(!readOnly.power.interactive&&readOnly.digits==0&&readOnly.enters==0&&Handler.queue.isEmpty());
        check(!readiness.has("challenge")&&!readiness.has("pin")&&Boolean.FALSE.equals(readiness.get("stores_pin")));
        pass("readiness_reports_disabled_locked_slots_without_waking_or_input");
        readOnly.telephony.slots=new int[]{TelephonyManager.SIM_STATE_UNKNOWN};readiness=readOnly.pin.status();
        check(Integer.valueOf(0).equals(readiness.get("pin_locked_slots"))&&Boolean.FALSE.equals(readiness.get("sim_states_known")));
        pass("unknown_sim_state_not_reported_as_known_unlocked");
        readOnly.service.systems.remove(TelephonyManager.class);readOnly.service.systems.remove(KeyguardManager.class);
        readiness=readOnly.pin.status();check(readiness.get("pin_locked_slots")==JSONObject.NULL&&readiness.get("puk_locked_slots")==JSONObject.NULL&&readiness.get("device_secure")==JSONObject.NULL);
        pass("unavailable_readiness_stays_unknown_not_false_success");
        Fixture disabled=new Fixture();refuses("sim_pin_entry_not_enabled",()->disabled.pin.prepare(OWNER));check(disabled.digits==0);pass("disabled_by_default_no_input");
        Fixture puk=new Fixture();puk.enable();puk.telephony.slots[0]=3;refuses("sim_puk_requires_owner",()->puk.pin.prepare(OWNER));puk.telephony.slots=new int[]{2,2};refuses("sim_pin_multiple_locked_slots",()->puk.pin.prepare(OWNER));pass("puk_and_ambiguous_slot_refused");
        Fixture secure=new Fixture();secure.enable();secure.keyguard.secure=true;refuses("sim_pin_ui_unsupported",()->secure.pin.prepare(OWNER));secure.keyguard.secure=false;secure.root.pkg="com.example.spoof";refuses("sim_pin_ui_unsupported",()->secure.pin.prepare(OWNER));pass("device_password_and_non_system_window_refused");
        Fixture prepared=new Fixture();prepared.enable();String challenge=prepared.pin.prepare(OWNER).getString("challenge");refuses("sim_pin_challenge_expired",()->prepared.pin.submit("b".repeat(32),prepared.input(challenge),()->true,new Reply()));android.os.SystemClock.now=30001;refuses("sim_pin_challenge_expired",()->prepared.pin.submit(OWNER,prepared.input(challenge),()->true,new Reply()));check(prepared.digits==0);pass("challenge_bound_to_claim_and_expiry");
        Fixture success=new Fixture();success.enable();Reply done=success.submit();Handler.drain();check(done.successes==1&&success.enters==1&&success.digits==8);check(!success.service.getSharedPreferences("sim-pin-entry",0).getBoolean("attempt_pending",true));pass("one_keypad_submission_telephony_ready_and_request_redaction");
        Fixture changed=new Fixture();changed.enable();Reply partial=changed.submit();Handler.next();changed.entry.text+="•";Handler.drain();check("sim_pin_entry_changed".equals(partial.error)&&changed.enters==0&&changed.digits==1);refuses("sim_pin_previous_attempt_unresolved",()->new SimPinInput(changed.service).prepare(OWNER));pass("field_interference_stops_before_submit_persistent_no_retry");
        Fixture revoked=new Fixture();revoked.enable();Reply revokedReply=revoked.submit();Handler.next();revoked.permit=false;Handler.drain();check("permission_denied".equals(revokedReply.error)&&revoked.enters==0&&revoked.digits==1);pass("authority_loss_stops_remaining_digits");
        Fixture unknown=new Fixture();unknown.enable();unknown.accept=false;Reply unresolved=unknown.submit();Handler.drain();check("sim_pin_result_unknown_no_retry".equals(unresolved.error)&&unknown.enters==1);refuses("sim_pin_previous_attempt_unresolved",()->unknown.pin.prepare(OWNER));pass("failed_result_does_not_repeat_pin");
        System.out.println("TOTAL "+passed);
    }
}
