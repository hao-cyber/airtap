package io.airtap.preview;
import org.json.JSONObject;
import java.time.*;
import java.time.format.*;

public final class InvitationFeedbackTest {
    static int passed; static final long NOW=1793034000;
    interface Test { void run(); }
    static void test(String name,Test t){t.run();passed++;System.out.println("PASS "+name);}
    static void check(boolean c){if(!c)throw new AssertionError();}
    static JSONObject body(long expires){return new JSONObject().put("protocol_version",4).put("expires_at",JSONObject.NULL).put("pairing_expires_at",expires).put("ticket","a".repeat(32));}
    static String date(long at){return DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochSecond(at).atZone(ZoneOffset.UTC));}
    static InvitationFeedback.ServerTime clock(long at){return InvitationFeedback.serverTime(date(at),0,0);}
    static void rejected(String code,JSONObject body,long now,InvitationFeedback.ServerTime clock){try{InvitationFeedback.validate(body,now,clock);throw new AssertionError("accepted");}catch(InvitationFeedback.Rejected e){check(e.code.equals(code));check(!e.userMessage.contains("a".repeat(32)));}}
    public static void main(String[] args){
        test("strict existing acceptance window unchanged across both boundaries",()->{for(long delta=-2;delta<=332;delta++){boolean old=false,newer=false;try{ServiceProtocol.invitation(body(NOW+delta),NOW);old=true;}catch(IllegalArgumentException expected){}try{InvitationFeedback.validate(body(NOW+delta),NOW,clock(NOW));newer=true;}catch(InvitationFeedback.Rejected expected){}check(old==newer);}});
        test("31 second slow phone receives actionable clock mismatch, not network",()->rejected("device_service_clock_mismatch",body(NOW+300),NOW-31,clock(NOW)));
        test("fast phone and still-live server ticket receive clock mismatch",()->rejected("device_service_clock_mismatch",body(NOW+300),NOW+400,clock(NOW)));
        test("server confirms expired ticket; ask for new invitation",()->rejected("invitation_expired",body(NOW-1),NOW,clock(NOW)));
        test("future lifetime exceeding both clocks remains invalid response",()->rejected("invalid_invitation_response",body(NOW+600),NOW-31,clock(NOW)));
        test("missing server Date cannot establish a clock fault",()->rejected("invitation_time_unconfirmed",body(NOW+331),NOW,null));
        test("expired local time without corroborating Date stays uncertain",()->rejected("invitation_time_unconfirmed",body(NOW-1),NOW,null));
        test("malformed ticket is format failure even when clocks differ",()->rejected("invalid_invitation_response",body(NOW+300).put("ticket","not-a-ticket"),NOW-31,clock(NOW)));
        test("wrong protocol and string expiry are format errors",()->{rejected("invalid_invitation_response",body(NOW+300).put("protocol_version",3),NOW-31,clock(NOW));rejected("invalid_invitation_response",body(NOW+300).put("pairing_expires_at","1793034300"),NOW-31,clock(NOW));});
        test("malformed missing and excessive-latency Date evidence is ignored",()->{check(InvitationFeedback.serverTime("not-a-date",0,0)==null);check(InvitationFeedback.serverTime(null,0,0)==null);check(InvitationFeedback.serverTime(date(NOW),20001,0)==null);check(InvitationFeedback.serverTime(date(NOW),0,300001)==null);});
        test("main callback delay advances server bounds instead of inventing skew",()->{var c=InvitationFeedback.serverTime(date(NOW),1000,400000);check(c==null);var delayed=InvitationFeedback.serverTime(date(NOW),1000,299000);check(delayed.earliest()==NOW+299);rejected("invitation_time_unconfirmed",body(NOW+300),NOW+300,delayed);});
        test("network and rounding uncertainty can prevent false clock claim",()->{var uncertain=InvitationFeedback.serverTime(date(NOW),20000,0);check(uncertain.latest()==NOW+21);rejected("invitation_time_unconfirmed",body(NOW+45),NOW+51,uncertain);});
        test("messages offer specific local action without server text",()->{check(InvitationFeedback.message("device_service_clock_mismatch").contains("自动设置"));check(InvitationFeedback.message("invitation_expired").contains("重新生成"));check(InvitationFeedback.message("invalid_invitation_response").contains("服务版本"));});
        System.out.println("TOTAL "+passed);
    }
}
