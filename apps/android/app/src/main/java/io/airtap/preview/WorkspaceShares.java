package io.airtap.preview;

import android.content.*;
import android.net.Uri;
import android.os.*;
import java.util.*;
import org.json.JSONObject;

/** Ephemeral, read-only handoffs. No source path or bearer URI crosses the agent protocol. */
final class WorkspaceShares {
    static final long TTL=5*60*1000L;
    static final int LIMIT=16;
    private static final Map<String,Ticket> TICKETS=new HashMap<>(); // Main thread only.
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    static final class Ticket {
        final String id,owner,path,mime,version,name;final long size,epoch,deadline; final int sessionEpoch;
        Ticket(String owner,String path,String mime,FileTransferStore.Entry file,long epoch){
            this.id=UUID.randomUUID().toString().replace("-","");this.owner=owner;this.path=path;this.mime=mime;
            this.version=file.version();this.name=file.name();this.size=file.size();this.epoch=epoch;
            this.deadline=SystemClock.elapsedRealtime()+TTL;this.sessionEpoch=PreviewState.epoch;
        }
    }
    static Uri uri(Context c,Ticket t){return new Uri.Builder().scheme("content").authority(c.getPackageName()+".workspace-share").appendPath(t.id).appendPath(t.name).build();}
    static void revokeAll(Context c){
        for(Ticket t:new ArrayList<>(TICKETS.values()))revoke(c,t);
    }
    private static void revoke(Context c,Ticket t){
        TICKETS.remove(t.id);c.revokeUriPermission(uri(c,t),Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    static Ticket current(Context c,Uri uri){
        if(!"content".equals(uri.getScheme())||!(c.getPackageName()+".workspace-share").equals(uri.getAuthority())||uri.getQuery()!=null||uri.getFragment()!=null||uri.getPathSegments().size()!=2)
            throw new SecurityException("share_unavailable");
        Ticket t=TICKETS.get(uri.getPathSegments().get(0));
        if(t==null||!t.name.equals(uri.getLastPathSegment()))throw new SecurityException("share_unavailable");
        // PreviewState/authorization transitions are main-thread owned; do not decrypt the durable grant per data chunk.
        PreviewService service=PreviewService.instance;
        try {
            if(t.deadline<=SystemClock.elapsedRealtime()||t.sessionEpoch!=PreviewState.epoch||service==null||!service.isAuthorized()
                    ||FileScopes.epoch(c)!=t.epoch||!t.owner.equals(PreviewState.claim)) {
                revoke(c,t);throw new SecurityException("share_expired_or_revoked");
            }
        }catch(SecurityException e){throw e;}catch(Exception e){revoke(c,t);throw new SecurityException("share_unavailable");}
        return t;
    }
    static JSONObject open(Context c,String owner,String path,String mime,String target,FileTransferStore.Entry file,Runnable fence)throws Exception{
        fence.run();
        for(Ticket t:new ArrayList<>(TICKETS.values()))if(t.deadline<=SystemClock.elapsedRealtime())revoke(c,t);
        if(TICKETS.size()>=LIMIT)throw new FileTransferStore.Failure("file_share_limit");
        Ticket t=new Ticket(owner,path,mime,file,FileScopes.epoch(c));Uri uri=uri(c,t);
        Intent send=new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(file.name(),uri));
        if(!target.isEmpty())send.setPackage(target);
        if(send.resolveActivity(c.getPackageManager())==null)throw new FileTransferStore.Failure("file_share_target_unavailable");
        TICKETS.put(t.id,t);
        try {
            fence.run();c.startActivity(Intent.createChooser(send,"将文件交给 App").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_GRANT_READ_URI_PERMISSION));
            MAIN.postDelayed(()->revoke(c,t),TTL);
        }catch(Exception error){revoke(c,t);throw error;}
        return RelayApi.json("accepted",true,"share_id",t.id,"root","workspace","path",path,"mime_type",mime,
                "target_package",target.isEmpty()?JSONObject.NULL:target,"expires_in_ms",TTL,"read_only",true,"chooser_requested",true,
                "delivery_verified",false,"requires_observation",true,"next_step","Observe the system share sheet and choose the intended recipient under the owner's authorization. Opening the chooser does not prove selection, delivery or sending. Stop/revoke/cleanup ends future reads, but cannot erase bytes already copied by another app.");
    }
}
