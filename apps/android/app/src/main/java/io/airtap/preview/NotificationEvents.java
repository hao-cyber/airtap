package io.airtap.preview;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

/** Optional, owner-enabled event collection. No SMS database, cloud archive, or retroactive grant access. */
public final class NotificationEvents extends NotificationListenerService {
    private static boolean connected;
    private static boolean storageFailed;
    static boolean selected(Context c) { return c.getSharedPreferences("device-events", 0).getBoolean("enabled", false); }
    static boolean permission(Context c) {
        return c.getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(new ComponentName(c, NotificationEvents.class));
    }
    static synchronized void select(Context c, boolean enabled) throws Exception {
        if (!c.getSharedPreferences("device-events", 0).edit().putBoolean("enabled", false).commit()) throw new IllegalStateException();
        // Starting a new consent period must not expose old notification contents.
        for (java.io.File f : java.util.Objects.requireNonNullElse(c.getFilesDir().listFiles(), new java.io.File[0]))
            if (f.getName().matches("events-[a-f0-9]{32}\\.aesgcm(?:\\.(?:bak|new))?")) {
                if (!f.delete() && f.exists()) throw new java.io.IOException();
            }
        if(enabled&&!c.getSharedPreferences("device-events",0).edit().putBoolean("enabled",true).commit())throw new IllegalStateException();
        storageFailed = false;
    }
    /** Explicit delegated setup; Android access is still granted through system Settings. */
    static synchronized JSONObject configure(Context c, boolean enabled) throws Exception {
        boolean changed = selected(c) != enabled;
        // A repeated enable must retain the epoch and pending events. Disable
        // retries cleanup, including a partially completed earlier attempt.
        if (changed || !enabled) select(c, enabled);
        return RelayApi.json("accepted", true, "changed", changed, "history_reset", changed || !enabled,
                "status", status(c), "system_permission_required", enabled && !permission(c),
                "settings_action", android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
                "settings_package", "com.android.settings", "affects", "currently_authorized_ai_connections",
                "instruction", "If system permission is required, open Android Settings and allow Airtap notification access through the normal UI. This command does not grant that permission.");
    }
    /** Open only the platform's notification-access page; never grant access ourselves. */
    static synchronized JSONObject configure(Context c, boolean enabled, boolean openSettings) throws Exception {
        if (openSettings && !enabled) throw new IllegalArgumentException();
        JSONObject result = configure(c, enabled);
        if (!openSettings) return result;
        try {
            c.startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            result.put("settings_opened", true);
        } catch (ActivityNotFoundException unavailable) {
            result.put("settings_opened", false).put("settings_error", "settings_activity_unavailable");
        } catch (SecurityException denied) {
            result.put("settings_opened", false).put("settings_error", "settings_activity_blocked");
        }
        return result.put("instruction", "Collection is configured. settings_opened only reports that Android accepted the Settings launch. Observe the actual page, use its normal permission UI, then check event_status.permission and listener_connected. No permission is granted by this command.");
    }
    static JSONObject status(Context c) {
        return RelayApi.json("enabled", selected(c), "permission", permission(c), "listener_connected", connected,
                "storage_ok", !storageFailed, "retention_hours", 24, "max_events", EventWindow.MAX_EVENTS,
                "history_complete", false, "content_visibility", "platform_filtered",
                "sms_database", false, "requires_agent_polling", true);
    }
    private static EnrollmentStore store(Context c, String claim) {
        if (!claim.matches("[a-f0-9]{32}")) throw new IllegalArgumentException();
        return new EnrollmentStore(c, "events-"+claim);
    }
    private static EventWindow load(EnrollmentStore s) throws Exception {
        JSONObject data = s.readRecord();
        if (data == null) return new EventWindow();
        if (data.getInt("schema") != 1) throw new IllegalStateException();
        JSONArray items = data.getJSONArray("events"); var values = new ArrayList<EventWindow.Entry>();
        for (int i=0; i<items.length(); i++) {
            JSONObject e=items.getJSONObject(i);
            values.add(new EventWindow.Entry(e.getLong("sequence"),e.getLong("at"),e.getString("kind"),e.getString("source"),e.getString("key"),e.getString("title"),e.getString("text"),e.getBoolean("truncated")));
        }
        return new EventWindow(data.getString("epoch"),data.getLong("sequence"),values);
    }
    private static JSONObject entry(EventWindow.Entry e) {
        return RelayApi.json("sequence",e.sequence(),"at",e.at(),"kind",e.kind(),"source",e.source(),"key",e.key(),"title",e.title(),"text",e.text(),"truncated",e.truncated());
    }
    private static void save(EnrollmentStore s, EventWindow w) throws Exception {
        JSONArray rows=new JSONArray();for(var e:w.entries)rows.put(entry(e));
        s.saveRecord(RelayApi.json("schema",1,"epoch",w.epoch,"sequence",w.sequence,"events",rows));
    }
    static synchronized JSONObject read(Context c, String claim, JSONObject request) throws Exception {
        if (!selected(c)) throw new ScopedUi.Refused("event_access_not_enabled");
        if (!permission(c)) throw new ScopedUi.Refused("notification_permission_required");
        if (storageFailed) throw new ScopedUi.Refused("event_storage_unavailable");
        EnrollmentStore s=store(c,claim); EventWindow w=load(s);
        EventWindow.Page page=w.read(request.getString("epoch"),ServiceProtocol.integer(request,"after"),request.getInt("limit"),System.currentTimeMillis());
        save(s,w); JSONArray rows=new JSONArray();for(var e:page.entries())rows.put(entry(e));
        return RelayApi.json("epoch",page.epoch(),"next",page.next(),"gap",page.gap(),"events",rows,"status",status(c));
    }
    private static synchronized void record(Context c, String kind, String source, String key, String title, String text) {
        if (!selected(c) || !permission(c)) return;
        try {
            EnrollmentStore.Config config=new EnrollmentStore(c).read(); TrustedGrant trust=new TrustedGrant(c);
            if (config==null || OwnerRevocations.allPending(c,config)) return;
            var revoked=OwnerRevocations.claimsPending(c,config);var claims=trust.validClaims(config);
            // Remove retired grants' encrypted history instead of accumulating private copies.
            for(java.io.File f:java.util.Objects.requireNonNullElse(c.getFilesDir().listFiles(),new java.io.File[0])) {
                String n=f.getName();
                if(n.matches("events-[a-f0-9]{32}\\.aesgcm")&&!claims.contains(n.substring(7,39)))store(c,n.substring(7,39)).forget();
            }
            for(String claim:claims) {
                if(revoked.contains(claim))continue;
                EnrollmentStore s=store(c,claim);EventWindow w=load(s);
                w.append(System.currentTimeMillis(),kind,source,key,title,text);save(s,w);
            }
        } catch(Exception unavailable) { storageFailed=true; } // No content or credentials in logs.
    }
    @Override public void onListenerConnected() { connected=true;record(this,"collection_gap","android","","","Listener connected; earlier events may be missing."); }
    @Override public void onListenerDisconnected() { connected=false;record(this,"collection_gap","android","","","Listener disconnected."); }
    @Override public void onDestroy() { connected=false;super.onDestroy(); }
    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if(sbn==null||sbn.getPackageName().equals(getPackageName()))return;
        Notification n=sbn.getNotification();if(n==null||n.extras==null||(n.flags&Notification.FLAG_GROUP_SUMMARY)!=0)return;
        CharSequence title=n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence body=n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if(body==null)body=n.extras.getCharSequence(Notification.EXTRA_TEXT);
        String key=FileTransferStore.digest(sbn.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        record(this,"notification",sbn.getPackageName(),key,title==null?"":title.toString(),body==null?"":body.toString());
    }
}
