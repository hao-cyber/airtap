package io.airtap.preview;

import android.content.*;
import org.json.*;
import java.nio.charset.StandardCharsets;

/** Added only to a disposable, differently named test APK. Never a production component. */
public final class NativeBridge extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        JSONObject reply=new JSONObject();
        try {
            JSONObject q=new JSONObject(intent.getStringExtra("request"));
            reply.put("request_id",q.getString("request_id"));
            String op=q.getString("op");
            EnrollmentStore store=new EnrollmentStore(c);
            EnrollmentStore.Config config=store.read();
            TrustedGrant trust=new TrustedGrant(c);
            switch(op) {
                case "seed":
                    if(config!=null)throw new IllegalStateException("existing_enrollment");
                    config=new EnrollmentStore.Config("https://localhost",q.getString("device_id"),q.getString("token"),true);
                    store.save(config); PreviewState.config=config;
                    c.getSharedPreferences("io.airtap.preview.MainActivity",0).edit().putInt("protocol",4).commit();
                    c.getSharedPreferences("updates",0).edit().putLong("checked",System.currentTimeMillis()).commit();
                    break;
                case "approve":
                    trust.beginApproval(config,q.getString("claim"),q.getString("name"));
                    if(PreviewService.instance==null)throw new IllegalStateException("accessibility_not_bound");
                    PreviewService.instance.restore();
                    break;
                case "enable":
                    trust.beginMigration(config,trust.sessionFence(config));
                    if(PreviewService.instance!=null)PreviewService.instance.restore();
                    break;
                case "invitation":
                    reply.put("invitation",trust.beginInvitation(config,q.getLong("watermark")));
                    break;
                case "invitation_ready":
                    reply.put("completed",trust.completeInvitation(config,q.getString("id"),q.getLong("sequence"),q.getLong("expiry"),q.getString("cli_sha"),q.getString("agent_sha")));
                    break;
                case "revoke":
                    String claim=q.getString("claim");
                    OwnerRevocations.queue(c,config,claim);
                    trust.revoke(config,claim);FileScopes.revoke(c,claim);
                    if(PreviewService.instance!=null)PreviewService.instance.ownerGrantsChanged();
                    OwnerRevocations.flush(c,config,null);
                    break;
                case "stop":
                    if(q.has("generation"))OwnerRevocations.queueAll(c,config,q.getLong("generation"));
                    else OwnerRevocations.queueAllUnknown(c,config);
                    if(PreviewService.instance!=null)PreviewState.stop("Synthetic owner stop");else trust.stop();
                    FileScopes.revoke(c);OwnerRevocations.flush(c,config,null);
                    break;
                case "queue_revoke": OwnerRevocations.queue(c,config,q.getString("claim"));break;
                case "notification_stop":
                    boolean sent=false;
                    for(android.service.notification.StatusBarNotification n:c.getSystemService(android.app.NotificationManager.class).getActiveNotifications()){
                        android.app.Notification.Action[] actions=n.getNotification().actions;
                        if(actions!=null)for(android.app.Notification.Action action:actions)if("停止控制".contentEquals(action.title)){action.actionIntent.send();sent=true;}
                    }
                    if(!sent)throw new IllegalStateException("stop_notification_action_missing");break;
                case "flush": OwnerRevocations.flush(c,config,null);break;
                case "restore": if(PreviewService.instance!=null)PreviewService.instance.restore();break;
                case "inspect": break;
                default: throw new IllegalArgumentException("unknown_test_action");
            }
            JSONArray grants=new JSONArray();
            if(config!=null)for(JSONObject grant:trust.grants(config))grants.put(grant);
            reply.put("grants",grants).put("multi",config!=null&&trust.multi(config));
            reply.put("migration",config==null||trust.migration(config)==null?JSONObject.NULL:trust.migration(config));
            reply.put("grant_fence",config==null||trust.sessionFence(config)==null?JSONObject.NULL:trust.sessionFence(config));
            reply.put("pending_claim_revokes",new JSONArray(config==null?java.util.Set.of():OwnerRevocations.claimsPending(c,config)));
            PreviewService service=PreviewService.instance;
            reply.put("bound",service!=null).put("authorized",service!=null&&service.isAuthorized()).put("connected",service!=null&&service.isConnected());
            reply.put("file_quiescent",FileScopes.quiescent());
            if(service!=null) {
                reply.put("status",service.statusText());
                java.lang.reflect.Field field=PreviewService.class.getDeclaredField("ui");field.setAccessible(true);
                reply.put("ui_quiescent",((CompanionUi)field.get(service)).quiescent());
            }
            reply.put("all_stop_pending",config!=null&&OwnerRevocations.allPending(c,config));
            reply.put("ok",true);
        } catch(Throwable failure) {
            try{reply.put("ok",false).put("error",failure.getClass().getSimpleName()).put("detail",String.valueOf(failure.getMessage()));}catch(Exception ignored){}
        }
        try(java.io.FileOutputStream out=c.openFileOutput("native-result.json",Context.MODE_PRIVATE)){out.write(reply.toString().getBytes(StandardCharsets.UTF_8));}
        catch(Exception failure){throw new RuntimeException(failure);}
    }
}
