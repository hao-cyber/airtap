package io.airtap.preview;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.PowerManager;

public final class SessionServiceTest {
    static SessionService service;
    static PreviewService control;
    static void require(boolean condition) { if (!condition) throw new AssertionError(); }
    static void start() {
        Handler.reset(); Context.power=new PowerManager(); Context.notifications=new NotificationManager(); Context.stops=Context.foregroundStarts=0;Context.startFails=false;Context.permission=true;android.app.Service.foregroundFails=false;android.app.Service.foregroundCalls=0;
        control=new PreviewService(); PreviewService.instance=control; service=new SessionService(); service.onCreate();
        service.onStartCommand(new Intent(service,SessionService.class).putExtra("generation",1),0,1); Handler.advance(0);
    }
    static void check(String name,Runnable test) { start(); try {test.run();System.out.println("PASS "+name);}finally{service.onDestroy();} }
    public static void main(String[] args) {
        check("ready authorized session crosses original 60-second deadlines without release gaps",()->{
            Handler.advance(181000); require(Context.power.interactive); require(Context.power.lock.isHeld());
            require(Context.power.lock.expirations==0); require(Context.power.lock.releases==0);
        });
        check("lease renewal does not impose obsolete fifteen-minute session cap",()->{
            Handler.advance(16*60000); require(Context.power.lock.isHeld());require(Context.power.lock.expirations==0);
        });
        check("no more renewals means the last bounded lease expires",()->{
            Handler.advance(58000); require(Context.power.lock.isHeld());
            // Stop only service ticks, leaving the independent WakeLock timeout alive.
            try {var f=SessionService.class.getDeclaredField("main");f.setAccessible(true);((Handler)f.get(service)).removeCallbacksAndMessages(null);}catch(Exception e){throw new AssertionError(e);}
            Handler.advance(117999); require(Context.power.lock.isHeld());
            Handler.advance(118000); require(!Context.power.lock.isHeld());require(Context.power.lock.expirations==1);
        });
        check("screen-off and locked states release without waking or reacquiring",()->{
            Context.power.interactive=false;Handler.advance(2000);require(!Context.power.lock.isHeld());int count=Context.power.lock.acquires;
            Handler.advance(66000);require(!Context.power.interactive);require(Context.power.lock.acquires==count);
            Context.power.interactive=true;control.locked=true;Handler.advance(68000);require(Context.power.lock.acquires==count);
            control.locked=false;Handler.advance(70000);require(Context.power.lock.isHeld());
        });
        check("authorization revoked before next tick releases and never renews",()->{
            int count=Context.power.lock.acquires;control.authorized=false;Handler.advance(2000);
            require(!Context.power.lock.isHeld());require(service.stopped==1);Handler.advance(120000);require(Context.power.lock.acquires==count);
        });
        check("stale generation cannot renew the new approval",()->{
            int count=Context.power.lock.acquires;control.generation++;Handler.advance(2000);
            require(!Context.power.lock.isHeld());require(service.stopped==1);require(Context.power.lock.acquires==count);
        });
        check("current notification stop releases immediately and cancels renewal",()->{
            Handler.advance(58000);int count=Context.power.lock.acquires;
            service.onStartCommand(new Intent(service,SessionService.class).setAction("io.airtap.preview.STOP_SESSION").putExtra("generation",1),0,2);
            require(!Context.power.lock.isHeld());require(control.stopCalls==1);Handler.advance(120000);require(Context.power.lock.acquires==count);
        });
        check("disabled notifications immediately fence execution and release with consent retained",()->{
            require(SessionService.readyFor(control,1));Context.notifications.enabled=false;require(!SessionService.readyFor(control,1));
            Handler.advance(2000);require(control.stopCalls==0&&control.suspends==1&&control.consent);require(!Context.power.lock.isHeld());require(Handler.pending()==0);
        });
        check("revoked POST_NOTIFICATIONS fences before the service tick",()->{
            Context.permission=false;require(!SessionService.readyFor(control,1));Handler.advance(2000);
            require(control.suspends==1&&control.stopCalls==0&&control.consent&&!Context.power.lock.isHeld());
        });
        check("muted connection channel fences even when app notifications remain enabled",()->{
            Context.notifications=new NotificationManager();Context.notifications.createNotificationChannel(new android.app.NotificationChannel("airtap-active-session","test",0));
            require(!SessionService.readyFor(control,1));Handler.advance(2000);require(control.suspends==1&&control.consent);
        });
        check("latched notification loss still suspends after permission was restored",()->{
            control.blocked=true;Handler.advance(2000);
            require(control.suspends==1&&control.consent&&!Context.power.lock.isHeld());
        });
        check("foreground service launch exception suspends without revoking",()->{
            Context.startFails=true;SessionService.begin(control);
            require(control.suspends==1&&control.lastStartFailure&&control.stopCalls==0&&control.consent&&!SessionService.readyFor(control,1));
        });
        check("notification creation failure cannot open execution or clear consent",()->{
            service.onDestroy();control.authorized=true;android.app.Service.foregroundFails=true;
            service=new SessionService();service.onCreate();int ready=control.readyCalls;
            service.onStartCommand(new Intent(service,SessionService.class).putExtra("generation",1),0,2);
            require(control.suspends==1&&control.lastStartFailure&&control.stopCalls==0&&control.consent&&control.readyCalls==ready);
            require(!SessionService.readyFor(control,1));
        });
        check("notification permission lost before asynchronous onStart never starts foreground",()->{
            service.onDestroy();control.authorized=true;Context.permission=false;int calls=android.app.Service.foregroundCalls;
            service=new SessionService();service.onCreate();service.onStartCommand(new Intent(service,SessionService.class).putExtra("generation",1),0,2);
            require(control.suspends==1&&!control.lastStartFailure&&control.consent&&android.app.Service.foregroundCalls==calls);
        });
        check("old-generation notification stop cannot revoke a newer connection",()->{
            control.generation=2;
            service.onStartCommand(new Intent(service,SessionService.class).setAction("io.airtap.preview.STOP_SESSION").putExtra("generation",1),0,2);
            require(control.stopCalls==0&&control.consent);
        });
        check("queued stale start cannot stop a newer visible service",()->{
            control.generation=2;
            service.onStartCommand(new Intent(service,SessionService.class).putExtra("generation",2),0,2);
            int stopped=service.stopped;
            service.onStartCommand(new Intent(service,SessionService.class).putExtra("generation",1),0,3);
            require(service.stopped==stopped&&SessionService.readyFor(control,2)&&control.consent);
        });
        check("destroying old SessionService cannot interrupt its replacement",()->{
            SessionService old=service;service=new SessionService();service.onCreate();
            service.onStartCommand(new Intent(service,SessionService.class).putExtra("generation",1),0,2);
            old.onDestroy();require(control.authorized&&SessionService.readyFor(control,1));
        });
        check("service destruction releases and removes pending renewal",()->{
            service.onDestroy();require(!Context.power.lock.isHeld());require(Handler.pending()==0);
        });
        check("unsupported screen-lock level remains safe and does not create another lock",()->{
            service.onDestroy();Handler.reset();Context.power=new PowerManager();Context.power.supported=false;control.authorized=true;
            service=new SessionService();service.onCreate();service.onStartCommand(new Intent(service,SessionService.class).putExtra("generation",1),0,1);
            Handler.advance(62000);require(Context.power.lock==null);require(control.authorized);
        });
    }
}
