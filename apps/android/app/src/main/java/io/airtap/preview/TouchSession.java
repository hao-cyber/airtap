package io.airtap.preview;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** One bounded continued stroke. Cancellation only lifts at the last endpoint, never replays DOWN. */
final class TouchSession {
    interface Reply { void complete(); void error(String error); }
    static final long MAX_HOLD_MS=45000;
    final String id=java.util.UUID.randomUUID().toString().replace("-", ""), owner;
    final long deadline=SystemClock.elapsedRealtime()+MAX_HOLD_MS;
    private final AccessibilityService service;
    private final Runnable gate;
    private final java.util.function.Consumer<Boolean> closed;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private GestureDescription.StrokeDescription pointer;
    private float x,y;
    private boolean moving,closing,finished;
    private final Runnable watchdog=new Runnable(){public void run(){
        if(finished)return;
        try{gate.run();if(SystemClock.elapsedRealtime()>=deadline)cancel();}catch(RuntimeException e){cancel();}
        if(!finished)handler.postDelayed(this,200);
    }};
    TouchSession(AccessibilityService service,String owner,Runnable gate,java.util.function.Consumer<Boolean> closed) {
        this.service=service;this.owner=owner;this.gate=gate;this.closed=closed;
    }
    boolean ready(){return !moving&&!closing&&!finished;}
    private void finish(boolean clean){if(finished)return;finished=true;handler.removeCallbacks(watchdog);closed.accept(clean);}
    void start(float x,float y,Reply reply){this.x=x;this.y=y;handler.postDelayed(watchdog,200);dispatch(x,y,1,true,reply);}
    void move(float toX,float toY,int duration,Reply reply){
        if(!ready()){reply.error("touch_session_busy");return;}
        gate.run();
        if(toX==x&&toY==y){reply.complete();return;}
        dispatch(toX,toY,duration,true,reply);
    }
    void end(Reply reply){
        if(!ready()){reply.error("touch_session_busy");return;}
        closing=true;dispatch(x,y,1,false,reply);
    }
    void cancel(){
        if(finished)return;closing=true;
        if(!moving)release(null);
    }
    private void release(Reply previous){
        if(pointer==null){finish(true);if(previous!=null)previous.error("touch_interrupted_outcome_unknown");return;}
        dispatch(x,y,1,false,new Reply(){
            public void complete(){if(previous!=null)previous.error("touch_interrupted_outcome_unknown");}
            public void error(String error){if(previous!=null)previous.error(error);}
        });
    }
    private void dispatch(float toX,float toY,int duration,boolean more,Reply reply){
        Path path=new Path();path.moveTo(x,y);if(x!=toX||y!=toY)path.lineTo(toX,toY);
        try{
            var next=pointer==null?new GestureDescription.StrokeDescription(path,0,duration,more):pointer.continueStroke(path,0,duration,more);
            moving=true;
            boolean accepted=service.dispatchGesture(new GestureDescription.Builder().addStroke(next).build(),new AccessibilityService.GestureResultCallback(){
                @Override public void onCompleted(GestureDescription ignored){
                    if(finished)return;moving=false;pointer=more?next:null;x=toX;y=toY;
                    if(!more){finish(true);reply.complete();}
                    else if(closing){release(reply);}
                    else reply.complete();
                }
                @Override public void onCancelled(GestureDescription ignored){
                    if(finished)return;moving=false;
                    // Android may cancel a segment after accepting DOWN. Release that exact continued stroke.
                    pointer=next;x=toX;y=toY;closing=true;
                    if(!more){finish(false);reply.error("touch_release_failed_outcome_unknown");}else release(reply);
                }
            },handler);
            if(!accepted){moving=false;if(more&&pointer!=null){closing=true;release(reply);}else{finish(pointer==null);reply.error("touch_rejected");}}
        }catch(RuntimeException failure){moving=false;finish(false);reply.error("touch_release_failed_outcome_unknown");}
    }
}
