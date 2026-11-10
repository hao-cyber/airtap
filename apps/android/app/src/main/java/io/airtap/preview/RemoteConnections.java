package io.airtap.preview;

import android.content.Context;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Connection management through an existing full-device grant, never through an unauthenticated invitation. */
final class RemoteConnections {
    static final Set<String> ACTIONS = Set.of("connection_status", "connection_invite", "connection_approve", "connection_cancel", "connection_revoke");
    private final Context context;
    private final EnrollmentStore.Config config;
    private final String actor, fence;
    private final BooleanSupplier authority;
    private final CompanionUi.Reply reply;
    private boolean finished;
    private RemoteConnections(Context context, EnrollmentStore.Config config, String actor, String fence,
                              BooleanSupplier authority, CompanionUi.Reply reply) {
        this.context=context;this.config=config;this.actor=actor;this.fence=fence;this.authority=authority;this.reply=reply;
    }
    static void validate(JSONObject request) throws Exception {
        String action=request.getString("action");
        if ("connection_approve".equals(action)) {
            MultiAiProtocol.exact(request,"id","action","invitation_id","claim_id","verification_code");
            MultiAiProtocol.hex(request,"invitation_id");MultiAiProtocol.hex(request,"claim_id");
            if (!request.getString("verification_code").matches("[0-9]{6}")) throw new IllegalArgumentException();
        } else if ("connection_revoke".equals(action)) {
            MultiAiProtocol.exact(request,"id","action","claim_id");MultiAiProtocol.hex(request,"claim_id");
        } else if ("connection_cancel".equals(action)) {
            MultiAiProtocol.exact(request,"id","action","invitation_id");MultiAiProtocol.hex(request,"invitation_id");
        } else if (ACTIONS.contains(action)) MultiAiProtocol.exact(request,"id","action");
        else throw new IllegalArgumentException();
    }
    static void execute(Context context, EnrollmentStore.Config config, String actor, String fence,
                        JSONObject request, BooleanSupplier authority, CompanionUi.Reply reply) {
        RemoteConnections operation=new RemoteConnections(context,config,actor,fence,authority,reply);
        try { validate(request);operation.start(request); }
        catch (Exception failure) { operation.fail(failure); }
    }
    private boolean current() throws Exception {
        if (finished || !authority.getAsBoolean()) return false;
        EnrollmentStore.Config saved=new EnrollmentStore(context).read();
        return saved!=null&&config.url.equals(saved.url)&&config.token.equals(saved.token)
                &&fence!=null&&fence.equals(new TrustedGrant(context).grantFence(config,actor))
                &&!OwnerRevocations.allPending(context,config)&&!OwnerRevocations.claimsPending(context,config).contains(actor);
    }
    private void ok(JSONObject result) throws Exception { if(current()){finished=true;reply.success(result);} }
    private void fail(Exception failure) {
        if (finished) return;
        finished=true;
        String code=failure.getMessage();
        if (code==null || !Set.of("multi_api_required","grant_limit_reached","client_upgrade_required","approval_pending",
                "invitation_in_progress","invitation_mismatch","invitation_canceled","invitation_expired","candidate_mismatch",
                "comparison_code_mismatch","self_approval_refused","connection_state_unavailable","connection_result_unknown",
                "permission_denied","grant_unavailable","use_self_revoke").contains(code)) code="connection_state_unavailable";
        reply.error(code);
    }
    private interface Step { void run(JSONObject value) throws Exception; }
    private void request(String path,String method,JSONObject payload,Step step) {
        RelayApi.requestStatus(config.url,path,method,config.token,payload,(body,status,error)->{
            try {
                if(!current()) return;
                if(error!=null||status!=200) throw new IllegalStateException("GET".equals(method)?"connection_state_unavailable":"connection_result_unknown");
                ServiceProtocol.version(body);step.run(body);
            } catch(Exception failure){fail(failure);}
        });
    }
    private void start(JSONObject request) throws Exception {
        if(!current()) throw new IllegalStateException("permission_denied");
        TrustedGrant trust=new TrustedGrant(context);
        String action=request.getString("action");
        if(!trust.multi(config)) {
            if(!"connection_invite".equals(action)) throw new IllegalStateException("multi_api_required");
            if(trust.beginMigration(config,fence)==null) throw new IllegalStateException("grant_unavailable");
            ok(RelayApi.json("state","migration_pending","next_step","Keep the original pairing. Query status, acquire a task in multi mode, then run connection-invite again."));
            if(PreviewService.instance!=null) PreviewService.instance.restore();
            return;
        }
        request("/v1/device/grants","GET",null,remote->{
            Set<String> claims=MultiAiProtocol.claims(remote);
            if(!claims.contains(actor)) throw new IllegalStateException("permission_denied");
            switch(action) {
                case "connection_status" -> status(remote);
                case "connection_invite" -> invite(remote);
                case "connection_approve" -> approve(remote,request);
                case "connection_cancel" -> cancel(request);
                case "connection_revoke" -> revoke(remote,request);
                default -> throw new IllegalArgumentException();
            }
        });
    }
    private boolean ours(JSONObject invitation) {
        return invitation!=null&&actor.equals(invitation.optString("delegated_by"))&&fence.equals(invitation.optString("delegator_fence"));
    }
    private JSONObject remoteInvitation(JSONObject remote,JSONObject local) throws Exception {
        if(local==null||remote.isNull("invitation")) return null;
        JSONObject value=remote.getJSONObject("invitation");
        return local.getString("invitation_id").equals(value.getString("invitation_id"))
                &&local.getLong("invitation_sequence")==value.getLong("invitation_sequence")?value:null;
    }
    private void status(JSONObject remote) throws Exception {
        TrustedGrant trust=new TrustedGrant(context);JSONArray grants=new JSONArray();
        Set<String> live=MultiAiProtocol.claims(remote);
        for(JSONObject grant:trust.grants(config)) {
            String claim=grant.getString("claim");
            if(!live.contains(claim)||OwnerRevocations.claimsPending(context,config).contains(claim)) continue;
            JSONObject item=RelayApi.json("claim_id",claim,"name",grant.getString("name"),"state","approved",
                    "self",actor.equals(claim),"lease_ready",grant.getBoolean("lease_ready"));
            if(grant.has("delegated_by")) item.put("added_by",grant.getString("delegated_by"));
            grants.put(item);
        }
        for(int i=0;i<remote.getJSONArray("grants").length();i++) {
            JSONObject grant=remote.getJSONArray("grants").getJSONObject(i);String claim=grant.getString("claim_id");
            if(trust.grant(config,claim)==null&&!OwnerRevocations.claimsPending(context,config).contains(claim))
                grants.put(RelayApi.json("claim_id",claim,"name",grant.getString("name"),"state","not_locally_approved","self",false));
        }
        JSONObject local=trust.invitation(config), invitation=null;
        if(local!=null) {
            invitation=RelayApi.json("invitation_id",local.getString("invitation_id"),"state",local.getString("phase"),
                    "pairing_expires_at",local.getLong("pairing_expires_at"),"managed_by_self",ours(local));
            JSONObject pending=trust.pending(config), actual=remoteInvitation(remote,local);
            if(pending!=null) invitation.put("state","approving").put("claim_id",pending.getString("claim"));
            else if(actual!=null&&!actual.isNull("candidate")) {
                JSONObject candidate=actual.getJSONObject("candidate");
                // Require the new AI's independently returned code; never hand the approver a code to echo back.
                invitation.put("state","pending").put("candidate",RelayApi.json("claim_id",candidate.getString("claim_id"),"name",candidate.getString("name")));
            }
        }
        JSONArray revoking=new JSONArray();for(String claim:OwnerRevocations.claimsPending(context,config)) revoking.put(claim);
        ok(RelayApi.json("grants",grants,"max_grants",4,"pending_revocations",revoking,"invitation",invitation==null?JSONObject.NULL:invitation));
    }
    private void invite(JSONObject remote) throws Exception {
        TrustedGrant trust=new TrustedGrant(context);
        JSONObject local=trust.beginDelegatedInvitation(config,MultiAiProtocol.integer(remote,"invitation_sequence",true),actor,fence);
        if("canceled".equals(local.getString("phase"))) throw new IllegalStateException("invitation_canceled");
        if("ready".equals(local.getString("phase"))) { invitationResult(local);return; }
        String id=local.getString("invitation_id");long sequence=local.getLong("invitation_sequence");
        request("/v1/device/invitations","POST",RelayApi.json("invitation_id",id,"invitation_sequence",sequence,"ticket",local.getString("ticket")),body->{
            if(!id.equals(MultiAiProtocol.hex(body,"invitation_id"))||sequence!=MultiAiProtocol.integer(body,"invitation_sequence",false)
                    ||!body.has("expires_at")||!body.isNull("expires_at")) throw new IllegalArgumentException();
            if(!trust.completeInvitation(config,id,sequence,MultiAiProtocol.integer(body,"pairing_expires_at",false),body.getString("cli_sha256"),body.getString("agent_sha256")))
                throw new IllegalStateException("invitation_mismatch");
            invitationResult(trust.invitation(config));
        });
    }
    private void invitationResult(JSONObject local) throws Exception {
        if(!ours(local)) throw new IllegalStateException("invitation_mismatch");
        if(local.getLong("pairing_expires_at")<=System.currentTimeMillis()/1000) throw new IllegalStateException("invitation_expired");
        ok(RelayApi.json("invitation_id",local.getString("invitation_id"),"relay_url",config.url,"ticket",local.getString("ticket"),
                "pairing_expires_at",local.getLong("pairing_expires_at"),"cli_sha256",local.getString("cli_sha256"),"agent_sha256",local.getString("agent_sha256")));
    }
    private void approve(JSONObject remote,JSONObject request) throws Exception {
        String id=request.getString("invitation_id"),claim=request.getString("claim_id");TrustedGrant trust=new TrustedGrant(context);
        if(actor.equals(claim)) throw new IllegalStateException("self_approval_refused");
        JSONObject active=trust.grant(config,claim);
        if(active!=null&&actor.equals(active.optString("delegated_by"))&&id.equals(active.optString("invitation_id"))
                &&MultiAiProtocol.claims(remote).contains(claim)) { ok(RelayApi.json("state","approved","claim_id",claim));return; }
        JSONObject local=trust.invitation(config),actual=remoteInvitation(remote,local);
        if(!ours(local)||!id.equals(local.getString("invitation_id"))||"canceled".equals(local.optString("phase"))) throw new IllegalStateException("invitation_mismatch");
        if(actual==null||actual.isNull("candidate")) throw new IllegalStateException("candidate_mismatch");
        if(actual.getLong("pairing_expires_at")<=System.currentTimeMillis()/1000) throw new IllegalStateException("invitation_expired");
        JSONObject candidate=actual.getJSONObject("candidate");
        if(!claim.equals(candidate.getString("claim_id"))) throw new IllegalStateException("candidate_mismatch");
        if(!request.getString("verification_code").equals(candidate.getString("verification_code"))) throw new IllegalStateException("comparison_code_mismatch");
        trust.beginDelegatedApproval(config,claim,candidate.getString("name"),actor,fence,id);
        if(PreviewService.instance!=null) PreviewService.instance.restore();
        ok(RelayApi.json("state","approving","claim_id",claim,"next_step","Check connection_status; the new AI uses pair-status to confirm. Do not create a new invitation."));
    }
    private void cancel(JSONObject command) throws Exception {
        TrustedGrant trust=new TrustedGrant(context);JSONObject local=trust.invitation(config);String id=command.getString("invitation_id");
        if(local==null){ok(RelayApi.json("state","absent","invitation_id",id));return;}
        if(!id.equals(local.getString("invitation_id"))) throw new IllegalStateException("invitation_mismatch");
        long sequence=local.getLong("invitation_sequence");
        JSONObject pending=trust.pending(config);
        if(pending!=null) OwnerRevocations.queue(context,config,pending.getString("claim"));
        if(!trust.cancelInvitation(config,id,sequence)) throw new IllegalStateException("invitation_mismatch");
        OwnerRevocations.flush(context,config,null);
        request("/v1/device/invitations/cancel","POST",RelayApi.json("invitation_id",id,"invitation_sequence",sequence),body->{
            if(!ServiceProtocol.bool(body,"ok")||!trust.finishInvitationCancellation(config,id,sequence)) throw new IllegalStateException("invitation_mismatch");
            ok(RelayApi.json("state","canceled","invitation_id",id));
        });
    }
    private void revoke(JSONObject remote,JSONObject command) throws Exception {
        String claim=command.getString("claim_id");
        if(actor.equals(claim)) throw new IllegalStateException("use_self_revoke");
        TrustedGrant trust=new TrustedGrant(context);
        if(trust.grant(config,claim)==null&&!MultiAiProtocol.claims(remote).contains(claim)&&!OwnerRevocations.claimsPending(context,config).contains(claim)) {
            ok(RelayApi.json("state","absent","claim_id",claim));return;
        }
        OwnerRevocations.queue(context,config,claim);
        trust.revoke(config,claim);FileScopes.revoke(context,claim);
        if(PreviewService.instance!=null) PreviewService.instance.ownerGrantsChanged();
        OwnerRevocations.flush(context,config,null);
        ok(RelayApi.json("state","revoking","claim_id",claim,"next_step","Query connection-status until this claim is absent from both grants and pending_revocations."));
    }
}
