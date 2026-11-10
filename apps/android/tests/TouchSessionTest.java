package io.airtap.preview;
import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
public class TouchSessionTest {
    static class Result implements TouchSession.Reply{int ok;String error;public void complete(){ok++;}public void error(String e){error=e;}}
    static void check(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args){
        Handler.reset();var s=new AccessibilityService();boolean[] valid={true},closed={false};
        var session=new TouchSession(s,"owner",()->{if(!valid[0])throw new IllegalStateException();},clean->{check(clean);closed[0]=true;});
        var r=new Result();session.start(10,10,r);s.complete();check(r.ok==1&&s.down&&s.downs==1);
        session.move(10,10,100,r);check(r.ok==2&&s.calls==1);
        session.move(80,90,400,r);s.complete();check(r.ok==3&&s.downs==1&&s.ups==0);
        session.end(r);s.complete();check(closed[0]&&!s.down&&s.ups==1&&r.ok==4);
        System.out.println("PASS hold_observe_move_single_down_and_up");
        Handler.reset();s=new AccessibilityService();final boolean[] denied={false};closed[0]=false;
        session=new TouchSession(s,"owner",()->{if(denied[0])throw new IllegalStateException();},clean->{check(clean);closed[0]=true;});
        session.start(1,2,new Result());s.complete();denied[0]=true;Handler.next();s.complete();check(closed[0]&&s.downs==1&&s.ups==1);
        System.out.println("PASS authority_loss_only_lifts_pointer");
        Handler.reset();s=new AccessibilityService();closed[0]=false;
        session=new TouchSession(s,"owner",()->{},clean->{check(clean);closed[0]=true;});session.start(1,2,new Result());s.complete();
        while(s.pending==null)Handler.next();s.complete();check(closed[0]&&s.downs==1&&s.ups==1);
        System.out.println("PASS bounded_hold_timeout_releases");System.out.println("TOTAL 3");
    }
}
