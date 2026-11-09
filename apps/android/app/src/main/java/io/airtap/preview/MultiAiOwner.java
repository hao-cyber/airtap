package io.airtap.preview;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.view.WindowManager;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.function.Consumer;

/** Protected owner surface. Adding an invitation never stops another approved AI. */
final class MultiAiOwner {
    private final Activity owner;
    private final Runnable changed;
    private final Consumer<String> folder;
    private final InvitationCode invitationCode;
    void closeInvitationCode() { invitationCode.close(); }
    private EnrollmentStore.Config identity;
    private JSONObject remote;
    private String message="";
    private boolean busy,visible;
    private int generation;
    private long nextPoll;
    private okhttp3.Call call;
    private AlertDialog enableCheck;
    private Object invitationRead;
    private String lastEnableStage="not_requested";
    MultiAiOwner(Activity owner,Runnable changed,Consumer<String> folder){this.owner=owner;this.changed=changed;this.folder=folder;this.invitationCode=new InvitationCode(owner,changed);}
    JSONObject diagnostics(EnrollmentStore.Config expected){
        boolean same=expected!=null&&identity!=null&&expected.url.equals(identity.url)&&expected.token.equals(identity.token);
        return RelayApi.json("visible",visible,"busy",busy,"enable_check_visible",enableCheck!=null&&enableCheck.isShowing(),
                "identity_matches",same,"last_enable_stage",lastEnableStage);
    }
    boolean enabled(EnrollmentStore.Config config){try{return new TrustedGrant(owner).multi(config);}catch(Exception e){return false;}}
    void bindIdentity(EnrollmentStore.Config config){
        if(identity==null||config==null||!identity.url.equals(config.url)||!identity.token.equals(config.token)){
            generation++;invitationRead=null;if(call!=null)call.cancel();call=null;busy=false;remote=null;nextPoll=0;dismissEnableCheck();
            lastEnableStage="not_requested";
        }
        identity=config;
    }
    void resume(EnrollmentStore.Config config){bindIdentity(config);visible=true;nextPoll=0;}
    void pause(){visible=false;generation++;invitationRead=null;busy=false;if(call!=null)call.cancel();call=null;dismissEnableCheck();}
    private void change(String text){message=text;changed.run();}
    private boolean current(int run,EnrollmentStore.Config config){return visible&&!owner.isDestroyed()&&run==generation&&identity!=null&&config.url.equals(identity.url)&&config.token.equals(identity.token);}
    private void dismissEnableCheck(){if(enableCheck!=null){enableCheck.dismiss();enableCheck=null;}}
    private void cancelEnable(int run){if(run!=generation)return;lastEnableStage="canceled";generation++;if(call!=null)call.cancel();call=null;busy=false;dismissEnableCheck();}
    private boolean enableCurrent(int run,EnrollmentStore.Config config,String fence){
        if(!current(run,config))return false;
        try{EnrollmentStore.Config saved=new EnrollmentStore(owner).read();TrustedGrant trust=new TrustedGrant(owner);
            return saved!=null&&saved.enrolled()&&saved.publicService()&&saved.url.equals(config.url)&&saved.token.equals(config.token)
                    &&!trust.multi(saved)&&fence!=null&&trust.isCurrent(saved,fence);
        }catch(Exception unreadable){return false;}
    }
    private void enableUnavailable(){new AlertDialog.Builder(owner).setTitle("暂未开启多 AI").setMessage("当前服务尚未确认支持多 AI，原 AI 连接保持不变。请联网后重试，或等待服务更新。").setPositiveButton("知道了",null).show();}
    void enable(EnrollmentStore.Config config){
        bindIdentity(config);if(busy||!visible){lastEnableStage="guard_blocked";return;}
        try{if(new TrustedGrant(owner).migration(config)!=null){lastEnableStage="migration_requested";if(PreviewService.instance!=null)PreviewService.instance.restore();change("正在继续核实多 AI 连接，原授权已保留。");return;}}
        catch(Exception unreadable){lastEnableStage="storage_unavailable";change("无法读取原连接，请检查手机存储。");return;}
        final String fence;
        try{fence=new TrustedGrant(owner).sessionFence(config);}catch(Exception unavailable){lastEnableStage="storage_unavailable";enableUnavailable();return;}
        final int run=++generation;if(!enableCurrent(run,config,fence)){lastEnableStage="guard_blocked";return;}
        // Discovery is read-only: the current single grant and socket survive every failed check.
        busy=true;lastEnableStage="checking_service";
        enableCheck=new AlertDialog.Builder(owner).setTitle("正在核实服务支持")
          .setMessage("正在只读检查当前服务，原 AI 连接继续可用。")
          .setNegativeButton("取消",(d,w)->cancelEnable(run)).setOnCancelListener(d->cancelEnable(run)).show();
        try{call=RelayApi.requestStatus(config.origin(),"/v1/service","GET",null,null,(body,status,error)->{
            if(!current(run,config))return;busy=false;call=null;dismissEnableCheck();
            if(!enableCurrent(run,config,fence)){lastEnableStage="guard_blocked";return;}
            try{if(error!=null||status!=200)throw new IllegalArgumentException();MultiAiProtocol.migrationDiscovery(body);}
            catch(Exception unsupported){cancelEnable(run);lastEnableStage="rejected";enableUnavailable();return;}
            confirmEnable(run,config,fence);
        });}catch(Exception unavailable){cancelEnable(run);lastEnableStage="request_failed";enableUnavailable();}
    }
    private void confirmEnable(int run,EnrollmentStore.Config config,String fence){
        lastEnableStage="awaiting_owner_confirmation";
        new AlertDialog.Builder(owner).setTitle("连接多个自己的 AI")
          .setMessage("会保留现有 AI 的连接。每位 AI 分别核码授权，按完整任务轮流使用同一部手机。它们使用同一套手机账号和画面，没有账号隔离。支持任务接管的新版接入工具完成准备后，才能添加下一位 AI。")
          .setNegativeButton("返回",(d,w)->cancelEnable(run)).setOnCancelListener(d->cancelEnable(run)).setPositiveButton("开启并保留原连接",(d,w)->{
            if(!enableCurrent(run,config,fence)){if(current(run,config))lastEnableStage="guard_blocked";return;}generation++;
            try{
                if(new TrustedGrant(owner).beginMigration(config,fence)==null){lastEnableStage="guard_blocked";return;}
                lastEnableStage="migration_requested";
                if(PreviewService.instance!=null)PreviewService.instance.restore();
                change("正在核实多 AI 连接，确认服务端完成后再切换。原授权已保留。");
            }catch(Exception failure){lastEnableStage="request_failed";change("未能保存设置，原连接记录已保留，请检查手机存储。");}
          }).show();
    }
    String statusMessage(){return message;}
    boolean migrationPending(EnrollmentStore.Config config){try{return new TrustedGrant(owner).migration(config)!=null;}catch(Exception unreadable){return false;}}
    String rememberSingleStop(EnrollmentStore.Config config){
        try{
            TrustedGrant trust=new TrustedGrant(owner);JSONObject grant=trust.read(config);
            if(grant==null)grant=trust.pending(config);if(grant==null)return null;
            String claim=grant.getString("claim");OwnerRevocations.queue(owner,config,claim);return claim;
        }catch(Exception unavailable){return null;}
    }
    void confirmSingleStop(EnrollmentStore.Config config,String claim){
        if(claim==null)return;try{OwnerRevocations.confirmed(owner,config,claim);}catch(Exception unavailable){ /* Keep the original targeted retry. */ }
    }
    void retryRevocations(EnrollmentStore.Config config){
        try{
            if(!OwnerRevocations.allPending(owner,config)&&OwnerRevocations.claimsPending(owner,config).isEmpty()){
                change("没有可核实的原解除请求，未停止当前连接。请检查原连接状态后再操作。");return;
            }
            bindIdentity(config);flushRevokes();change("正在继续确认已保存的解除请求。");
        }catch(Exception unreadable){change("无法读取原解除请求，请检查手机存储后重试。");}
    }
    boolean stopPendingMigration(EnrollmentStore.Config config){
        final String claim;
        try{
            TrustedGrant trust=new TrustedGrant(owner);
            if(trust.migration(config)==null&&OwnerRevocations.claimsPending(owner,config).isEmpty())return false;
            // This is a new STOP, not the separate retry action. Stop a later active or pending
            // consent explicitly; with neither there is only the saved revoke to retry.
            JSONObject target=trust.read(config);if(target==null)target=trust.pending(config);
            if(target==null){retryRevocations(config);return true;}
            claim=target.getString("claim");
        }catch(Exception unreadable){
            // Unknown retry metadata cannot justify an unscoped network revoke.
            boolean stopped=false;
            try{if(PreviewService.instance==null)new TrustedGrant(owner).stop();else PreviewState.stop("已停止本机连接");stopped=true;}catch(Exception failure){ /* Independent local fence may also be unavailable. */ }
            change(stopped?"本机已停止，但无法读取原解除请求；请检查存储后重试。":"停止尚未确认，请检查手机存储后重试。");return true;
        }
        bindIdentity(config);boolean queued=false,stopped=false;
        try{OwnerRevocations.queue(owner,config,claim);queued=true;}catch(Exception unavailable){ /* Local stop remains independent. */ }
        try{if(PreviewService.instance==null)new TrustedGrant(owner).stop();else PreviewState.stop("已停止原 AI 连接");stopped=true;}
        catch(Exception unavailable){ /* PreviewService closes execution even if persistence fails. */ }
        try{FileScopes.revoke(owner);}catch(Exception unavailable){ /* Grant fences still deny access. */ }
        if(queued)flushRevokes();
        change(stopped&&queued?"本机已停止，正在确认原 AI 的服务端解除。":stopped?"本机已停止，但解除请求未保存，请检查存储后重试。":"停止尚未确认，请检查手机存储后重试。");
        return true;
    }
    void tick(){
        if(!visible||identity==null||busy||android.os.SystemClock.elapsedRealtime()<nextPoll)return;
        if(enabled(identity))poll(false);
        else{nextPoll=android.os.SystemClock.elapsedRealtime()+5000;flushRevokes();checkEmptyMode();}
    }
    private void checkEmptyMode(){
        if(busy||identity==null)return;
        final String fence;final EnrollmentStore.Config config=identity;
        try{fence=new TrustedGrant(owner).emptyModeFence(config);if(fence==null)return;}catch(Exception unreadable){return;}
        final int run=generation;busy=true;
        try{call=RelayApi.requestStatus(config.url,"/v1/device/pairing","GET",config.token,null,(body,status,error)->{
            if(!current(run,config))return;busy=false;call=null;
            if(error!=null||status!=200)return;
            try{
                if(!body.has("mode"))return;MultiAiProtocol.claims(body);
                if(new TrustedGrant(owner).completeEmptyMulti(config,fence)){remote=body;change("已核实服务端连接模式，本机没有恢复任何 AI 授权。");}
            }catch(Exception unknown){ /* A failed or malformed read cannot create consent or change mode. */ }
        });}catch(Exception unavailable){busy=false;call=null;}
    }
    private void cancelInvitationRead(){
        if(invitationRead==null)return;invitationRead=null;generation++;
        if(call!=null)call.cancel();call=null;busy=false;
    }
    private Map<String,String> invitationContext(EnrollmentStore.Config config)throws Exception{
        EnrollmentStore.Config saved=new EnrollmentStore(owner).read();TrustedGrant trust=new TrustedGrant(owner);
        if(saved==null||!saved.enrolled()||!saved.publicService()||!saved.url.equals(config.url)||!saved.token.equals(config.token)||!trust.multi(config))throw new IllegalStateException();
        Map<String,String> context=new TreeMap<>();
        // STOP must invalidate this intent even when the multi grant set was already empty.
        context.put("stop",owner.getSharedPreferences("trust-state",0).getString("stop_nonce",""));
        for(JSONObject grant:trust.grants(config)){String claim=grant.getString("claim");context.put(claim,trust.grantFence(config,claim));}
        JSONObject invite=trust.invitation(config),pending=trust.pending(config);
        if(invite!=null)context.put("invitation",invite.getString("invitation_id")+":"+invite.getLong("invitation_sequence")+":"+invite.getString("phase"));
        if(pending!=null)context.put("pending",pending.getString("approval_id"));
        return context;
    }
    private void poll(boolean createAfterRead){
        if(busy||identity==null||!visible)return;
        final Object intent=createAfterRead?new Object():null;
        final EnrollmentStore.Config config=identity;final int run=generation;
        try{
            final Map<String,String> context=createAfterRead?invitationContext(config):null;
            busy=true;invitationRead=intent;
            call=RelayApi.requestStatus(config.url,"/v1/device/grants","GET",config.token,null,(body,status,error)->{
                if(!current(run,config)||(createAfterRead&&invitationRead!=intent))return;
                // Consume once before continuing: a duplicate GET reply cannot issue another POST.
                invitationRead=null;busy=false;call=null;nextPoll=android.os.SystemClock.elapsedRealtime()+3000;
                if(error!=null||status!=200){change("暂时无法核实服务状态，原授权和邀请已保留，可稍后重试。");return;}
                try{
                    MultiAiProtocol.claims(body);
                    if(createAfterRead&&!context.equals(invitationContext(config))){change("连接状态已变化，请重新选择是否添加自己的 AI。");return;}
                    remote=body;
                    // Clear only the old migration notice after both sides confirm multi mode.
                    if(("正在核实多 AI 连接，确认服务端完成后再切换。原授权已保留。".equals(message)
                            ||"正在继续核实多 AI 连接，原授权已保留。".equals(message))
                            &&new TrustedGrant(owner).multi(config))message="";
                    restoreInvitation();
                    if(createAfterRead&&!busy)create(false);else changed.run();
                }catch(Exception malformed){change("服务返回的连接状态无法核实，请检查版本或稍后重试。");}
            });
        }catch(Exception unavailable){invitationRead=null;busy=false;call=null;change("暂时无法核实服务状态，原授权和邀请已保留，可稍后重试。");}
    }
    private Button button(String text,Runnable action){
        Button b=new Button(owner){
            @Override public boolean onFilterTouchEventForSecurity(android.view.MotionEvent event){
                boolean accepted=(event.getFlags()&(android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED|android.view.MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED))==0&&super.onFilterTouchEventForSecurity(event);
                if(!accepted&&event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN
                        &&(event.getFlags()&(android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED|android.view.MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED))!=0
                        &&owner instanceof MainActivity)((MainActivity)owner).showObscuredTouchFeedback();
                return accepted;
            }
        };
        b.setText(text);b.setFilterTouchesWhenObscured(true);b.setEnabled(!busy);AppUi.button(b,AppUi.SECONDARY);
        b.setOnClickListener(v->{if(owner instanceof MainActivity)((MainActivity)owner).clearObscuredTouchFeedback();action.run();});return b;
    }
    private Button primaryButton(String label,Runnable action){Button b=button(label,action);AppUi.button(b,AppUi.PRIMARY);return b;}
    private void text(LinearLayout box,String value,int size){box.addView(AppUi.text(owner,value,size));AppUi.space(box,12);}
    void render(LinearLayout box){
        text(box,"我的 AI 连接",28);text(box,"分别授权，同一时间由一位 AI 完成一个任务。手机账号与画面由这些 AI 共同使用。",16);
        if(!message.isEmpty())text(box,message,15);
        try{
            TrustedGrant trust=new TrustedGrant(owner);List<JSONObject> grants=trust.grants(identity);
            if(OwnerRevocations.allPending(owner,identity)){text(box,"所有 AI 已在本机停止，正在确认服务端解除。确认前暂不新建邀请。",16);box.addView(button("继续确认解除",()->flushRevokes()));return;}
            if(!OwnerRevocations.claimsPending(owner,identity).isEmpty()){text(box,"部分 AI 已在本机停止，服务端解除还在确认。",15);box.addView(button("继续确认解除",()->flushRevokes()));}
            JSONObject pending=trust.pending(identity),invitation=trust.invitation(identity),candidate=null;
            boolean expiredInvitation=pending==null&&invitation!=null&&("ready".equals(invitation.getString("phase"))||"canceled".equals(invitation.getString("phase")))
                    &&invitation.getLong("pairing_expires_at")>0&&invitation.getLong("pairing_expires_at")<=System.currentTimeMillis()/1000;
            if(!expiredInvitation&&remote!=null&&!remote.isNull("invitation")){JSONObject r=remote.getJSONObject("invitation");if(invitation!=null&&invitation.getString("invitation_id").equals(r.getString("invitation_id"))&&invitation.getLong("invitation_sequence")==r.getLong("invitation_sequence")&&!r.isNull("candidate")&&!"canceled".equals(invitation.getString("phase")))candidate=r.getJSONObject("candidate");}
            if(pending!=null){text(box,"正在完成你已核码确认的连接，其他 AI 的授权保持有效。",16);box.addView(button("继续核实",()->{if(PreviewService.instance!=null)PreviewService.instance.restore();poll(false);}));}
            else if(candidate!=null){String claim=MultiAiProtocol.hex(candidate,"claim_id"),code=candidate.getString("verification_code"),name=candidate.getString("name");if(!code.matches("[0-9]{6}"))throw new IllegalArgumentException();text(box,name+" 请求连接（名称由对方提供）",18);text(box,code.substring(0,3)+" "+code.substring(3),44);text(box,"有效至 "+new java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(new Date(invitation.getLong("pairing_expires_at")*1000)),14);text(box,"与自己的 AI 告诉你的六位数字核对。确认后它可持续查看和操作整台手机，AI 会自动核实连接，不用再回复“已点”。",16);box.addView(primaryButton("数字一致，连接这位 AI",()->approve(claim,name)));}
            else if(expiredInvitation){text(box,"这次邀请已过期",18);text(box,"重新生成后，把新邀请码交给自己的 AI。已有 AI 的连接不受影响。",16);box.addView(primaryButton("重新生成邀请码",()->cancel(true)));}
            else if(invitation!=null){String phase=invitation.getString("phase");text(box,"canceled".equals(phase)?"正在确认取消这次邀请":"邀请已保存，交给另一位自己的 AI",18);
                if("ready".equals(phase)&&invitation.getLong("pairing_expires_at")>System.currentTimeMillis()/1000){invitationCode.render(box,identity,invitation.getString("ticket"),invitation.getLong("pairing_expires_at"));box.addView(button("完整邀请与校验信息",()->show(invitation)));}
                else if(!"canceled".equals(phase))box.addView(button("继续准备原邀请",()->create(false)));
            }
            if((invitation!=null||pending!=null)&&!expiredInvitation)box.addView(button("取消本次邀请",this::cancel));
            else if(expiredInvitation){ /* Regenerate is the only next action for the expired invitation. */ }
            else if(needsStoppedGrantCleanup()){
                text(box,"这部手机已停止原连接，服务端仍保存旧授权。先解除旧连接，再重新邀请；不会恢复旧 AI 的手机权限。",16);
                box.addView(button("解除旧连接后重新准备",this::stopAll));
            }
            else if(grants.size()<4)box.addView(primaryButton("添加另一位自己的 AI",()->create(true)));
            else text(box,"已连接 4 位 AI。解除不再使用的一位后，可添加新的 AI。",15);
            if(!grants.isEmpty())text(box,"已连接的 AI",20);
            for(JSONObject grant:grants){String claim=grant.getString("claim");String name=grant.optString("name","我的 AI");text(box,name,18);text(box,grant.optBoolean("lease_ready",false)?"已连接 · 可继续使用":"已连接 · AI 正在准备工具",14);
                box.addView(button("文件夹权限",()->folder.accept(claim)));box.addView(button("解除 "+name+" 的连接",()->revoke(claim,name)));
            }
            box.addView(button("刷新连接状态",()->poll(false)));
            box.addView(button("停止并解除所有 AI",this::stopAll));
        }catch(Exception unreadable){text(box,"暂时无法读取本机授权，原记录已保留。请检查手机存储。",16);}
    }
    private void flushRevokes(){try{OwnerRevocations.flush(owner,identity,()->{if(visible)changed.run();});}catch(Exception failure){change("解除结果尚未确认，请检查网络或存储后重试。");}}
    private void restoreInvitation()throws Exception {
        flushRevokes();
        JSONObject invite=new TrustedGrant(owner).invitation(identity);
        if(invite!=null&&"canceled".equals(invite.getString("phase"))){cancel();return;}
    }
    private boolean needsStoppedGrantCleanup()throws Exception{
        return remote!=null&&new TrustedGrant(owner).grants(identity).isEmpty()&&remote.getJSONArray("grants").length()>0;
    }
    private void create(boolean fresh){
        if(busy||identity==null||!visible)return;
        try{
            if(OwnerRevocations.allPending(owner,identity)){change("请先确认全部 AI 已解除，再创建新的邀请。");return;}
            if(remote==null){poll(true);return;}
            if(needsStoppedGrantCleanup()){change("请先解除服务端保存的旧连接，再重新邀请自己的 AI。");return;}
            JSONObject invitation=new TrustedGrant(owner).beginInvitation(identity,MultiAiProtocol.integer(remote,"invitation_sequence",true));
            if("canceled".equals(invitation.getString("phase"))){cancel();return;}
            busy=true;int run=generation;EnrollmentStore.Config config=identity;
            JSONObject body=RelayApi.json("invitation_id",invitation.getString("invitation_id"),"invitation_sequence",invitation.getLong("invitation_sequence"),"ticket",invitation.getString("ticket"));
            call=RelayApi.requestStatus(config.url,"/v1/device/invitations","POST",config.token,body,(reply,status,error)->{
                if(!current(run,config))return;busy=false;call=null;
                if(error!=null){change(status==409?error:"邀请结果尚未确认。原邀请已保存，请继续准备，不会更换票据。");return;}
                try{ServiceProtocol.version(reply);String id=MultiAiProtocol.hex(reply,"invitation_id");long sequence=MultiAiProtocol.integer(reply,"invitation_sequence",false),expiry=MultiAiProtocol.integer(reply,"pairing_expires_at",false);
                    if(!id.equals(invitation.getString("invitation_id"))||sequence!=invitation.getLong("invitation_sequence")||!reply.has("expires_at")||!reply.isNull("expires_at"))throw new IllegalArgumentException();
                    new TrustedGrant(owner).completeInvitation(config,id,sequence,expiry,reply.getString("cli_sha256"),reply.getString("agent_sha256"));change("邀请已准备好，把邀请码告诉另一位自己的 AI，回来核对六位数字。");poll(false);
                }catch(Exception invalid){change("邀请尚未核实，请继续准备原邀请。原有 AI 不受影响。");}
            });
        }catch(Exception failure){
            String reason=failure instanceof IllegalStateException?RelayErrors.invitationPrecondition(failure.getMessage()):null;
            change(reason!=null?reason:"暂时无法保存新邀请。原有 AI 授权未更换，请检查存储或取消已结束的邀请后重试。");
        }
    }
    private String prompt(JSONObject invite)throws Exception {
        long expiry=invite.getLong("pairing_expires_at");String ticket=invite.getString("ticket");if(expiry<=System.currentTimeMillis()/1000)throw new IllegalArgumentException();
        InvitationArtifacts artifacts=new InvitationArtifacts(identity.url,InvitationArtifacts.fingerprint(identity.token),ticket,expiry,invite.getString("cli_sha256"),invite.getString("agent_sha256"));
        return "请接入我的 Airtap 手机，使用支持 task_lease_v1 的新版工具（Python CLI 申请时使用 pair-start --task-lease）。\n"+artifacts.verificationInstructions()+"\n接入说明：\n"+identity.url+"/agent.md\nPython CLI：\n"+identity.url+"/cli/airtap.py\n手机服务地址：\n"+identity.url+"\n接入票据（不要公开）：\nAIRTAP_PAIRING_TICKET="+ticket+"\n申请后把六位核对码告诉我，等我在手机确认。保存私有连接配置；每个完整任务先取得任务租约，必要时续租，完成后释放任务，不撤销授权。重新取得任务后先观察，未知操作不要重放。";
    }
    private void show(JSONObject invite){try{TextView t=AppUi.text(owner,prompt(invite),14);t.setTextIsSelectable(true);ScrollView scroll=new ScrollView(owner);scroll.addView(t);AlertDialog dialog=new AlertDialog.Builder(owner).setTitle("我的 AI 接入邀请").setView(scroll).setPositiveButton("返回",null).create();dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.show();}catch(Exception failure){change("邀请已过期或尚未核实。");}}
    private void approve(String claim,String name){try{if(OwnerRevocations.allPending(owner,identity))return;new TrustedGrant(owner).beginApproval(identity,claim,name);if(PreviewService.instance!=null)PreviewService.instance.restore();change("正在完成你已核码确认的连接，原有 AI 授权保持不变。");}catch(Exception failure){change("未能保存确认，请检查存储；原有 AI 授权保持不变。");}}
    private void cancel(){ cancel(false); }
    private void cancel(boolean regenerate){
        cancelInvitationRead();
        if(busy)return;try{
            TrustedGrant trust=new TrustedGrant(owner);JSONObject invitation=trust.invitation(identity);if(invitation==null)return;
            if(regenerate&&(trust.pending(identity)!=null||invitation.getLong("pairing_expires_at")<=0||invitation.getLong("pairing_expires_at")>System.currentTimeMillis()/1000))return;
            String id=invitation.getString("invitation_id");long seq=invitation.getLong("invitation_sequence");
            EnrollmentStore.Config config=identity;Map<String,String> intent=regenerate?invitationContext(config):null;
            if(intent!=null)intent.remove("invitation");
            trust.cancelInvitation(config,id,seq);busy=true;int run=generation;
            call=RelayApi.requestStatus(config.url,"/v1/device/invitations/cancel","POST",config.token,RelayApi.json("invitation_id",id,"invitation_sequence",seq),(body,status,error)->{
                if(!current(run,config))return;busy=false;call=null;
                try{
                    if(error!=null||status!=200){change("旧邀请的结束状态尚未确认，原 AI 连接保留，请重试。");return;}
                    ServiceProtocol.version(body);if(!ServiceProtocol.bool(body,"ok"))throw new IllegalArgumentException();
                    trust.finishInvitationCancellation(config,id,seq);
                    if(regenerate){
                        Map<String,String> present=invitationContext(config);present.remove("invitation");
                        if(!intent.equals(present)||trust.invitation(config)!=null){change("连接状态已变化，请重新选择是否添加自己的 AI。");return;}
                        change("正在准备新邀请码，已有 AI 连接保留。");poll(true);
                    }else{change("已取消本次邀请，已连接的 AI 不受影响。");poll(false);}
                }catch(Exception failure){change("邀请状态尚未核实，请刷新后重试。已有 AI 连接保留。");}
            });
        }catch(Exception failure){change("暂时无法保存邀请状态，请检查手机存储。");}
    }
    private void revoke(String claim,String name){cancelInvitationRead();new AlertDialog.Builder(owner).setTitle("解除 "+name+"？").setMessage("这位 AI 将不能继续使用手机。其他 AI 的授权保留；已复制到其他 App 的内容无法追回。").setNegativeButton("返回",null).setPositiveButton("解除",(d,w)->{try{OwnerRevocations.queue(owner,identity,claim);if(PreviewService.instance!=null)PreviewService.instance.ownerGrantsChanged();new TrustedGrant(owner).revoke(identity,claim);FileScopes.revoke(owner,claim);if(PreviewService.instance!=null)PreviewService.instance.ownerGrantsChanged();requestRevoke(claim);}catch(Exception failure){change("暂时无法确认本机已停止，请检查存储。");}}).show();}
    private void requestRevoke(String claim){change("这位 AI 已在本机停止，正在确认服务端解除。");flushRevokes();}
    boolean serviceChangeReady(){
        try{TrustedGrant trust=new TrustedGrant(owner);return remote!=null&&remote.getJSONArray("grants").length()==0&&remote.isNull("invitation")&&trust.grants(identity).isEmpty()&&trust.pending(identity)==null&&trust.invitation(identity)==null&&!OwnerRevocations.allPending(owner,identity)&&OwnerRevocations.claimsPending(owner,identity).isEmpty();}
        catch(Exception unreadable){return false;}
    }
    void stopAll(){
        cancelInvitationRead();
        try{if(OwnerRevocations.allPending(owner,identity)){flushRevokes();change("正在继续确认原来那次全部解除。");return;}}
        catch(Exception unreadable){ /* Local stop remains available when retry metadata cannot be read. */ }
        Long knownGeneration=null;
        try{if(remote!=null)knownGeneration=MultiAiProtocol.integer(remote,"generation",true);}
        catch(Exception invalid){remote=null;}
        final Long expectedGeneration=knownGeneration;
        new AlertDialog.Builder(owner).setTitle("停止所有 AI？")
            .setMessage("全部本机授权、邀请和当前任务都会停止。以后使用需要重新邀请并核码。")
            .setNegativeButton("返回",null).setPositiveButton("停止所有",(d,w)->{
                boolean intentSaved=false,localStopped=false;
                try{if(expectedGeneration==null)OwnerRevocations.queueAllUnknown(owner,identity);else OwnerRevocations.queueAll(owner,identity,expectedGeneration);intentSaved=true;}
                catch(Exception failure){ /* Still attempt the independent local stop below. */ }
                try{
                    if(PreviewService.instance==null)new TrustedGrant(owner).stop();else PreviewState.stop("已停止所有 AI");
                    localStopped=true;
                }catch(Exception failure){ /* PreviewService.stop always closes execution, even on persistence failure. */ }
                try{FileScopes.revoke(owner);}catch(Exception failure){ /* Grant and task fences deny future reads independently. */ }
                remote=null;
                if(intentSaved)flushRevokes();
                change(localStopped&&intentSaved?"本机全部 AI 已停止，服务端解除正在确认。":localStopped?"本机全部 AI 已停止，但未能保存服务端解除请求。请检查存储后重试。":"暂时无法确认停止已持久保存，请检查手机存储后重试。");
            }).show();
    }
}
