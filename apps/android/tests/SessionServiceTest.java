package io.airtap.preview;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.PowerManager;

/** Production carrier and grant with explicit platform and controller boundaries. */
public final class SessionServiceTest {
    private static final String CLAIM="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String RESTORING="正在恢复原 AI 连接", READY="我的 AI 可以使用这台手机";
    private static final java.util.List<SessionService> services=new java.util.ArrayList<>();
    private static SessionService service;
    private static PreviewService control;
    private static EnrollmentStore.Config config;
    private static int passed,failed,startId;
    interface Checked { void run() throws Exception; }
    private static void require(boolean condition) { if(!condition)throw new AssertionError(); }
    private static TrustedGrant trust(){return new TrustedGrant(new Context());}
    private static String grant() throws Exception {String id=trust().beginApproval(config,CLAIM,"Synthetic AI");require(id!=null);require(trust().completeApproval(config,id));return trust().sessionFence(config);}
    private static void reset() throws Exception {
        services.clear();service=null;control=null;PreviewService.instance=null;FakeDisk.reset();Handler.reset();PendingIntent.reset();android.util.Log.warnings.clear();
        Context.power=new PowerManager();Context.notifications=new NotificationManager();Context.stops=Context.foregroundStarts=0;Context.started=null;
        Context.startFails=false;Context.permission=true;Service.foregroundFails=false;Service.foregroundCalls=0;Service.foregroundHook=null;
        SessionRecoveryGate.operation=true;SessionRecoveryGate.stopped=false;startId=0;
        config=new EnrollmentStore.Config("https://relay.invalid/d/11111111111111111111111111111111","synthetic-secret-A");FakeDisk.config=config;grant();
    }
    private static SessionService create(){service=new SessionService();services.add(service);service.onCreate();return service;}
    private static PreviewService controller(boolean approved) throws Exception {
        control=new PreviewService();PreviewService.instance=control;control.authorized=approved;control.fence=trust().sessionFence(config);return control;
    }
    private static Intent readyIntent(){Context.started=null;SessionService.begin(control);require(Context.started!=null);return new Intent(Context.started);}
    private static int deliver(Intent intent){return service.onStartCommand(intent,0,++startId);}
    private static void ready() throws Exception {controller(true);create();require(deliver(readyIntent())==Service.START_STICKY);Handler.advance(0);require(SessionService.readyFor(control,control.generation));}
    private static void restoring() {create();require(deliver(null)==Service.START_STICKY);Handler.advance(0);require(RESTORING.equals(service.notice.title));}
    private static void reboot() {
        PreviewService.instance=null;control=null;for(SessionService old:services)old.onDestroy();services.clear();service=null;
        FakeDisk.reboot();Handler.reset();Context.power=new PowerManager();Context.started=null;
    }
    private static void test(String name,Checked body) throws Exception {
        reset();try{body.run();passed++;System.out.println("PASS "+name);}catch(Exception|AssertionError failure){
            failed++;System.err.println("FAIL "+name);failure.printStackTrace();
        }finally{
            PreviewService.instance=null;for(SessionService old:services)old.onDestroy();Handler.reset();
        }
    }
    public static void main(String[] args) throws Exception {
        test("null Intent and absent controller restore a visible sticky carrier without screen or execution",()->{
            restoring();require(PreviewService.instance==null&&Context.power.lock==null);require(service.notice.ongoing);
            require(service.notice.actions.size()==1);require(service.notice.text.contains("尚不能操作手机"));
            Handler.advance(120000);require(Context.power.lock==null&&service.stopped==0);require(trust().read(config)!=null);
        });
        test("delayed controller binding stays restoring until explicit validated READY",()->{
            restoring();controller(false);Handler.advance(2000);require(control.restores==1&&control.readyCalls==0);
            require(!control.tryCommand()&&control.commands==0&&Context.power.lock==null);
            control.authorized=true;require(deliver(readyIntent())==Service.START_STICKY);Handler.advance(2000);
            require(READY.equals(service.notice.title)&&control.readyCalls==1&&SessionService.readyFor(control,1));
            require(control.tryCommand()&&control.commands==1&&Context.power.lock.isHeld());
        });
        test("ensureRestoring emits a fenced restore and reuses the current carrier",()->{
            require(SessionService.ensureRestoring(new Context()));require(Context.foregroundStarts==1&&Context.started!=null);
            create();require(deliver(new Intent(Context.started))==Service.START_STICKY);require(RESTORING.equals(service.notice.title));
            require(SessionService.ensureRestoring(new Context())&&Context.foregroundStarts==1);require(Context.power.lock==null);
        });
        test("unknown and absent actions cannot cold start or demote a live carrier",()->{
            create();Intent unknown=new Intent(new Context(),SessionService.class).setAction("synthetic.UNKNOWN").putExtra("grant_fence",trust().sessionFence(config));
            require(deliver(unknown)==Service.START_NOT_STICKY&&service.notice==null);
            require(deliver(new Intent(new Context(),SessionService.class).putExtra("grant_fence",trust().sessionFence(config)))==Service.START_NOT_STICKY);
            ready();int notices=Service.foregroundCalls;require(deliver(unknown)==Service.START_STICKY&&SessionService.readyFor(control,control.generation));
            require(Service.foregroundCalls==notices&&READY.equals(service.notice.title));
        });
        test("ready session crosses sixty-second deadlines without release gaps",()->{
            ready();Handler.advance(181000);require(Context.power.interactive&&Context.power.lock.isHeld());
            require(Context.power.lock.expirations==0&&Context.power.lock.releases==0);
        });
        test("ready lease renewal has no obsolete fifteen-minute cap",()->{
            ready();Handler.advance(16*60000);require(Context.power.lock.isHeld()&&Context.power.lock.expirations==0);
        });
        test("last bounded lease expires if service ticks stop",()->{
            ready();Handler.advance(58000);require(Context.power.lock.isHeld());
            var field=SessionService.class.getDeclaredField("main");field.setAccessible(true);((Handler)field.get(service)).removeCallbacksAndMessages(null);
            Handler.advance(117999);require(Context.power.lock.isHeld());Handler.advance(118000);
            require(!Context.power.lock.isHeld()&&Context.power.lock.expirations==1);
        });
        test("screen-off and locked states release without waking or reacquiring",()->{
            ready();Context.power.interactive=false;Handler.advance(2000);require(!Context.power.lock.isHeld());int count=Context.power.lock.acquires;
            Handler.advance(66000);require(!Context.power.interactive&&Context.power.lock.acquires==count);
            Context.power.interactive=true;control.locked=true;Handler.advance(68000);require(Context.power.lock.acquires==count);
            control.locked=false;Handler.advance(70000);require(Context.power.lock.isHeld());
        });
        test("controller authorization loss pauses screen while consent remains recoverable",()->{
            ready();int count=Context.power.lock.acquires,restores=control.restores;control.authorized=false;Handler.advance(2000);
            require(!Context.power.lock.isHeld()&&control.restores==restores+1&&!SessionService.readyFor(control,1));
            require(RESTORING.equals(service.notice.title)&&service.stopped==0&&trust().read(config)!=null);
            Handler.advance(120000);require(Context.power.lock.acquires==count);
        });
        test("stale controller generation cannot retain a ready screen",()->{
            ready();int count=Context.power.lock.acquires;control.generation++;Handler.advance(2000);
            require(!Context.power.lock.isHeld()&&!SessionService.readyFor(control,control.generation));
            require(Context.power.lock.acquires==count&&RESTORING.equals(service.notice.title));
        });
        test("durable grant revocation stops the carrier and screen renewal",()->{
            ready();int count=Context.power.lock.acquires;trust().stop();Handler.advance(2000);
            require(service.stopped>0&&control.carrierLosses==1&&!Context.power.lock.isHeld());
            Handler.advance(120000);require(Context.power.lock.acquires==count&&trust().sessionFence(config)==null);
        });
        test("current notification STOP closes controller and persists across process restart",()->{
            ready();PendingIntent stop=service.notice.actions.get(0).intent;Handler.advance(58000);int count=Context.power.lock.acquires;
            require(deliver(stop.intent())==Service.START_NOT_STICKY);require(!Context.power.lock.isHeld()&&control.stopCalls==1);
            Handler.advance(120000);require(Context.power.lock.acquires==count);reboot();create();
            require(deliver(null)==Service.START_NOT_STICKY&&trust().read(config)==null&&Context.power.lock==null);
        });
        test("cold STOP with no controller durably prevents later sticky restart",()->{
            String fence=trust().sessionFence(config);reboot();create();require(PreviewService.instance==null);
            Context.permission=false;SessionRecoveryGate.operation=false;SessionRecoveryGate.stopped=true;
            require(deliver(SessionService.stopIntent(service,fence))==Service.START_NOT_STICKY);require(service.stopped>0);
            reboot();create();require(deliver(null)==Service.START_NOT_STICKY);require(trust().read(config)==null&&service.notice==null);
        });
        test("old PendingIntent keeps old extras and cannot stop same-claim new consent",()->{
            ready();PendingIntent old=service.notice.actions.get(0).intent;String oldFence=control.fence;trust().stop();
            String current=grant();control.fence=current;control.generation++;require(deliver(readyIntent())==Service.START_STICKY);
            PendingIntent replacement=service.notice.actions.get(0).intent;require(old!=replacement);
            require(oldFence.equals(old.intent().getStringExtra("grant_fence")));require(current.equals(replacement.intent().getStringExtra("grant_fence")));
            require(!old.intent().getData().toString().equals(replacement.intent().getData().toString()));
            int stopped=service.stopped;require(deliver(old.intent())==Service.START_STICKY);
            require(service.stopped==stopped&&control.stopCalls==0&&trust().isCurrent(config,current)&&SessionService.readyFor(control,control.generation));
        });
        test("PendingIntent fixture models extras updates for identical action and data identity",()->{
            Intent first=new Intent(new Context(),SessionService.class).setAction("synthetic-stop").putExtra("value","old");
            PendingIntent old=PendingIntent.getService(new Context(),2,first,PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent newer=PendingIntent.getService(new Context(),2,new Intent(first).putExtra("value","new"),PendingIntent.FLAG_UPDATE_CURRENT);
            require(old==newer&&"new".equals(old.intent().getStringExtra("value")));
        });
        test("invalid READY generation cannot demote an already newer foreground session",()->{
            ready();Intent stale=readyIntent();control.generation++;require(deliver(readyIntent())==Service.START_STICKY);
            int stopped=service.stopped,calls=Service.foregroundCalls;require(deliver(stale)==Service.START_STICKY);
            require(service.stopped==stopped&&Service.foregroundCalls==calls&&SessionService.readyFor(control,control.generation));
            require(READY.equals(service.notice.title));
        });
        test("old authorization delivery and null redelivery preserve the newer ready carrier",()->{
            ready();Intent old=readyIntent();trust().stop();control.fence=grant();control.generation++;
            require(deliver(readyIntent())==Service.START_STICKY);int calls=Service.foregroundCalls,stopped=service.stopped;
            require(deliver(old)==Service.START_STICKY);require(deliver(null)==Service.START_STICKY);
            require(Service.foregroundCalls==calls&&service.stopped==stopped&&SessionService.readyFor(control,control.generation));
        });
        test("generation-only legacy READY intent cannot start a cold carrier",()->{
            controller(true);create();require(deliver(new Intent(service,SessionService.class).putExtra("generation",1))==Service.START_NOT_STICKY);
            require(service.notice==null&&Context.power.lock==null&&control.readyCalls==0);
        });
        test("begin requires an authorized controller with an observed grant fence",()->{
            SessionService.begin(new Context());require(Context.foregroundStarts==0);
            controller(false);SessionService.begin(control);require(Context.foregroundStarts==0);
            control.authorized=true;control.fence=null;SessionService.begin(control);require(Context.foregroundStarts==0);
        });
        test("disabled app notifications fence execution and retain consent",()->{
            ready();Context.notifications.enabled=false;require(!SessionService.readyFor(control,1));Handler.advance(2000);
            require(control.stopCalls==0&&control.suspends==1&&trust().read(config)!=null);
            require(!Context.power.lock.isHeld()&&Handler.pending()==0);
        });
        test("revoked POST_NOTIFICATIONS fences before the next carrier tick",()->{
            ready();Context.permission=false;require(!SessionService.readyFor(control,1));Handler.advance(2000);
            require(control.suspends==1&&control.stopCalls==0&&trust().read(config)!=null&&!Context.power.lock.isHeld());
        });
        test("muted connection channel fences despite app notification availability",()->{
            ready();Context.notifications=new NotificationManager();Context.notifications.createNotificationChannel(new android.app.NotificationChannel("airtap-active-session","test",0));
            require(!SessionService.readyFor(control,1));Handler.advance(2000);require(control.suspends==1&&trust().read(config)!=null);
        });
        test("latched notification loss suspends and requires revalidation after permission returns",()->{
            ready();control.blocked=true;Handler.advance(2000);
            require(control.suspends==1&&trust().read(config)!=null&&!Context.power.lock.isHeld());
            require(!control.authorized&&!SessionService.readyFor(control,control.generation));
        });
        test("foreground service launch refusal suspends without deleting consent",()->{
            ready();Context.startFails=true;SessionService.begin(control);
            require(control.suspends==1&&control.lastStartFailure&&control.stopCalls==0&&trust().read(config)!=null);
            require(!SessionService.readyFor(control,control.generation));
        });
        test("foreground notification failure requests notification retry without blaming storage",()->{
            controller(true);create();Service.foregroundFails=true;require(deliver(readyIntent())==Service.START_NOT_STICKY);
            require(control.suspends==1&&control.lastStartFailure&&control.storageLosses==0);
            require(control.readyCalls==0&&control.stopCalls==0&&trust().read(config)!=null);
            require(!SessionService.readyFor(control,control.generation));
            require(!android.util.Log.warnings.isEmpty());
        });
        test("permission lost before asynchronous READY delivery creates no notification",()->{
            controller(true);Intent intent=readyIntent();create();Context.permission=false;int calls=Service.foregroundCalls;
            require(deliver(intent)==Service.START_NOT_STICKY);require(control.carrierLosses==1&&trust().read(config)!=null&&Service.foregroundCalls==calls);
        });
        test("permission revoked during notification creation is rechecked before READY callback",()->{
            controller(true);create();Service.foregroundHook=()->Context.permission=false;
            require(deliver(readyIntent())==Service.START_NOT_STICKY);require(control.readyCalls==0&&control.carrierLosses==1&&trust().read(config)!=null);
            require(Context.power.lock==null);
        });
        test("consent replaced during foreground creation cannot authorize the old READY delivery",()->{
            controller(true);create();final String[] replacement={null};
            Service.foregroundHook=()->{Service.foregroundHook=null;try{trust().stop();replacement[0]=grant();}catch(Exception failure){throw new IllegalStateException(failure);}};
            require(deliver(readyIntent())==Service.START_NOT_STICKY);require(control.readyCalls==0&&control.carrierLosses==1);
            require(Context.power.lock==null&&trust().isCurrent(config,replacement[0]));
        });
        test("accessibility prerequisite loss stops renewal without deleting original consent",()->{
            ready();SessionRecoveryGate.operation=false;Handler.advance(2000);
            require(service.stopped>0&&control.carrierLosses==1&&!Context.power.lock.isHeld()&&trust().read(config)!=null);
            require(!SessionService.ensureRestoring(new Context()));
        });
        test("cold restore rejects missing operation permission and explicit system user stop",()->{
            SessionRecoveryGate.operation=false;create();require(deliver(null)==Service.START_NOT_STICKY&&service.notice==null);
            require(!SessionService.ensureRestoring(new Context()));SessionRecoveryGate.operation=true;SessionRecoveryGate.stopped=true;
            create();require(deliver(null)==Service.START_NOT_STICKY&&service.notice==null);require(!SessionService.ensureRestoring(new Context()));
            require(trust().read(config)!=null);
        });
        test("current carrier halts when the platform reports an explicit user stop",()->{
            ready();SessionRecoveryGate.stopped=true;Handler.advance(2000);
            require(service.stopped>0&&control.carrierLosses==1&&!Context.power.lock.isHeld()&&trust().read(config)!=null);
        });
        test("pending or stopped grants cannot cold-start a restoring foreground carrier",()->{
            trust().stop();trust().beginApproval(config,CLAIM,"Synthetic AI");create();
            require(deliver(null)==Service.START_NOT_STICKY&&service.notice==null&&Context.power.lock==null);
            require(!SessionService.ensureRestoring(new Context()));trust().stop();create();require(deliver(null)==Service.START_NOT_STICKY);
        });
        test("cold grant read failure preserves encrypted record and never opens execution",()->{
            controller(false);create();var original=new java.util.LinkedHashMap<>(FakeDisk.record.fields);
            int saves=FakeDisk.saves,commits=FakeDisk.preferences.calls;FakeDisk.failReadRecord=true;
            require(deliver(null)==Service.START_NOT_STICKY&&service.notice==null&&control.storageLosses==1);
            require(original.equals(FakeDisk.record.fields)&&FakeDisk.saves==saves&&FakeDisk.preferences.calls==commits);
            boolean unreadable=false;try{SessionService.ensureRestoring(new Context());}catch(IllegalStateException expected){unreadable=true;}require(unreadable);reboot();create();require(deliver(null)==Service.START_STICKY);
        });
        test("watchdog read failure closes ready execution while preserving original consent",()->{
            ready();String fence=control.fence;var original=new java.util.LinkedHashMap<>(FakeDisk.record.fields);FakeDisk.failReadRecord=true;
            Handler.advance(2000);require(control.storageLosses==1&&!Context.power.lock.isHeld()&&!control.tryCommand());
            require(original.equals(FakeDisk.record.fields));reboot();require(trust().isCurrent(config,fence));
        });
        test("STOP read failure reports storage failure and preserves the original record",()->{
            ready();Intent stop=service.notice.actions.get(0).intent.intent();var original=new java.util.LinkedHashMap<>(FakeDisk.record.fields);FakeDisk.failReadRecord=true;
            require(deliver(stop)==Service.START_NOT_STICKY);require(control.storageLosses==1&&control.stopCalls==0);
            require(original.equals(FakeDisk.record.fields));
        });
        test("STOP marker persists when encrypted deletion fails",()->{
            ready();Intent stop=service.notice.actions.get(0).intent.intent();FakeDisk.failForget=true;
            require(deliver(stop)==Service.START_NOT_STICKY&&control.stopCalls==1&&control.storageLosses==0);require(FakeDisk.record!=null);
            reboot();require(trust().read(config)==null);create();require(deliver(null)==Service.START_NOT_STICKY);
        });
        test("STOP deletion persists when marker commit fails",()->{
            ready();Intent stop=service.notice.actions.get(0).intent.intent();FakeDisk.preferences.failAt=FakeDisk.preferences.calls+1;
            require(deliver(stop)==Service.START_NOT_STICKY&&control.stopCalls==1&&FakeDisk.record==null);reboot();require(trust().read(config)==null);
        });
        test("double STOP persistence failure never reports durable success",()->{
            ready();String fence=control.fence;Intent stop=service.notice.actions.get(0).intent.intent();
            FakeDisk.preferences.failAt=FakeDisk.preferences.calls+1;FakeDisk.failForget=true;
            require(deliver(stop)==Service.START_NOT_STICKY);require(control.stopCalls==0&&control.storageLosses==1&&!Context.power.lock.isHeld());
            require(android.util.Log.warnings.contains("AirtapRecovery:stage=stop_persistence failure=unavailable"));
            reboot();require(trust().isCurrent(config,fence)); // Both durable writes failed; fixture intentionally preserves that limitation.
        });
        test("destroying the old service cannot interrupt its replacement",()->{
            ready();SessionService old=service;create();require(deliver(readyIntent())==Service.START_STICKY);
            old.onDestroy();require(control.authorized&&SessionService.readyFor(control,1));
        });
        test("service destruction releases the screen and cancels carrier renewal",()->{
            ready();service.onDestroy();require(!Context.power.lock.isHeld()&&Handler.pending()==0&&control.carrierLosses==1);
        });
        test("unsupported screen retention remains safe without another wake lock",()->{
            Context.power.supported=false;ready();Handler.advance(62000);
            require(Context.power.lock==null&&control.authorized&&SessionService.readyFor(control,1));
        });
        System.out.println("TOTAL "+passed);
        if(failed!=0)throw new AssertionError(failed+" session carrier cases failed");
    }
}
