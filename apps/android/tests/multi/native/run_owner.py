#!/usr/bin/env python3
"""Opt-in native production-client/workerd checks. All identities and target content are synthetic."""
import argparse, concurrent.futures, hashlib, json, os, queue, re, secrets, shlex, subprocess, threading, time, uuid
from pathlib import Path
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[4]
CLIENT='io.airtap.fixture.multi';TARGET='io.airtap.fixture.multitarget'

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--serial',required=True);p.add_argument('--build',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--sdk',type=Path,required=True);p.add_argument('--reset-created',action='store_true');p.add_argument('--source-root',type=Path,required=True);p.add_argument('--suite',choices=['migration','invitation','experience'],default='migration');a=p.parse_args()
    if not a.serial.startswith('emulator-'):p.error('Only disposable emulator targets are accepted')
    out=a.output.resolve();out.mkdir(mode=0o700,parents=True,exist_ok=False);base=a.build.resolve()
    metadata=json.loads((base/'build-metadata.private.json').read_text());production=a.source_root.resolve()
    for relative,sha in metadata['production_java_sha256'].items():
        if hashlib.sha256((production/'apps/android'/relative).read_bytes()).hexdigest()!=sha:raise RuntimeError('production_java_changed_since_build')
    for relative in ['relay/service/service.mjs','relay/service/feedback.mjs','relay/worker/worker.mjs']:
        frozen=subprocess.check_output(['git','show',metadata['production_commit']+':'+relative],cwd=production)
        if (production/relative).read_bytes()!=frozen:raise RuntimeError('relay_changed_since_frozen_commit')
    adb=[str(a.sdk/'platform-tools/adb'),'-s',a.serial]
    def device(*v,check=True):
        r=subprocess.run(adb+list(map(str,v)),text=True,capture_output=True,timeout=60)
        if check and r.returncode:raise RuntimeError(r.stderr or r.stdout)
        return r.stdout
    if device('shell','getprop','ro.kernel.qemu').strip()!='1':raise RuntimeError('not_emulator')
    if 'Airtap_Multi_20261031' not in device('emu','avd','name'):raise RuntimeError('not_our_disposable_avd')
    if device('shell','getprop','sys.boot_completed').strip()!='1':raise RuntimeError('not_booted')
    marker=base/'installed.private.json'
    installed=device('shell','pm','list','packages')
    for pkg in [CLIENT,TARGET]:
        if 'package:'+pkg+'\n' in installed:
            if not a.reset_created or not marker.exists():raise RuntimeError('existing_package_not_overwritten')
            device('uninstall',pkg)
    for name in ['client','target']:device('install','--no-incremental',base/(name+'.apk'))
    marker.write_text(json.dumps({'serial':a.serial,'packages':[CLIENT,TARGET]}))
    device('shell','pm','grant',CLIENT,'android.permission.POST_NOTIFICATIONS')
    if device('shell','getprop','ro.debuggable').strip()!='1':raise RuntimeError('rootable_disposable_image_required_for_port443')
    device('root');device('wait-for-device')
    device('reverse','tcp:443','tcp:8443')
    device('shell','input','keyevent','KEYCODE_WAKEUP');device('shell','wm','dismiss-keyguard')
    device('shell','settings','put','secure','enabled_accessibility_services',CLIENT+'/io.airtap.preview.PreviewService')
    device('shell','settings','put','secure','accessibility_enabled','1')
    device('shell','am','start','-n',TARGET+'/test.multi.TargetActivity')
    log=(out/'relay-stderr.log').open('w')
    relay=subprocess.Popen(['node',str(HERE/'relay.mjs'),'--root',str(a.source_root.resolve()),'--key',str(base/'tls/key.pem'),'--cert',str(base/'tls/cert.pem'),'--port','8443','--state-dir',str(out/'relay-state')],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=log,text=True,bufsize=1)
    messages={};lock=threading.Lock();write_lock=threading.Lock();ready=threading.Event()
    def reader():
        for line in relay.stdout:
            try:value=json.loads(line)
            except Exception:continue
            if value.get('event')=='ready':ready.set()
            with lock:
                target=messages.get(value.get('id'))
            if target:target.put(value)
    threading.Thread(target=reader,daemon=True).start()
    if not ready.wait(30):raise RuntimeError('relay_not_ready')
    receipts=out/'receipts.private.jsonl'
    def admin(op,**kw):
        id=str(uuid.uuid4());q=queue.Queue()
        with lock:messages[id]=q
        with write_lock:relay.stdin.write(json.dumps({'id':id,'op':op,**kw})+'\n');relay.stdin.flush()
        result=q.get(timeout=50)
        with lock:messages.pop(id,None)
        if not result.get('ok'):raise RuntimeError('relay_admin:'+str(result))
        return result
    def request(path,token=None,body=None,method=None):
        kw={'path':path,'method':method or ('POST' if body is not None else 'GET')}
        if token:kw['token']=token
        if body is not None:kw['body']=body
        meta=admin('request',**kw);r=json.loads(Path(meta['response_file']).read_text())
        with receipts.open('a') as f:f.write(json.dumps({'at':time.time(),'path':path,'method':kw['method'],'request':body,'status':r['status'],'response':r.get('body')})+'\n')
        return r['status'],r.get('body')
    def ok(path,token=None,body=None,method=None):
        status,data=request(path,token,body,method);assert status==200,(path,status,data);return data
    def wait(fn,seconds=30):
        end=time.monotonic()+seconds;last=None
        while time.monotonic()<end:
            last=fn()
            if last:return last
            time.sleep(.3)
        raise TimeoutError('condition_not_met')
    def bridge(op,**kw):
        req={'request_id':str(uuid.uuid4()),'op':op,**kw}
        device('shell','am','broadcast','-n',CLIENT+'/io.airtap.preview.NativeBridge','--es','request',shlex.quote(json.dumps(req)))
        r=json.loads(device('shell','run-as',CLIENT,'cat','files/native-result.json'))
        assert r.get('request_id')==req['request_id'] and r.get('ok'),r
        with (out/'native.private.jsonl').open('a') as f:f.write(json.dumps({'at':time.time(),'op':op,'result':r})+'\n')
        return r
    checks=[];pool=concurrent.futures.ThreadPoolExecutor(3)
    def check(name,fn):
        start=time.monotonic();data=fn();checks.append({'name':name,'passed':True,'seconds':round(time.monotonic()-start,3),'evidence':data});print(json.dumps({'name':name,'passed':True}),flush=True)
    def status(c):return ok(d['base']+'/v1/status',c['token'])
    def cmd(c,action='ping',lease=None,**kw):
        c['seq']+=1;body={'id':str(uuid.uuid4()),'action':action,'sequence':c['seq'],**kw}
        if lease:body.update(lease_id=lease['lease_id'],lease_epoch=lease['lease_epoch'])
        return request(d['base']+'/v1/command',c['token'],body)
    def good(c,action='ping',lease=None,**kw):
        code,r=cmd(c,action,lease,**kw);assert code==200 and r.get('ok'),(code,r);return r['result']
    def acquire(c):return ok(d['base']+'/v1/tasks/acquire',c['token'],{'task_id':secrets.token_hex(16)})
    def release(c,t):return ok(d['base']+'/v1/tasks/release',c['token'],{k:t[k] for k in ('task_id','lease_id','lease_epoch')})
    def connect():return wait(lambda:bridge('inspect')['connected'],45)
    def invite(c):
        remote=ok(d['base']+'/v1/device/grants',d['token']);v=bridge('invitation',watermark=remote['invitation_sequence'])['invitation']
        body={k:v[k] for k in ('invitation_id','invitation_sequence','ticket')};r=ok(d['base']+'/v1/device/invitations',d['token'],body)
        bridge('invitation_ready',id=r['invitation_id'],sequence=r['invitation_sequence'],expiry=r['pairing_expires_at'],cli_sha=r['cli_sha256'],agent_sha=r['agent_sha256'])
        c['claim']=ok(d['base']+'/v1/pair',body={'ticket':v['ticket'],'controller_token':c['token'],'name':c['name'],'capabilities':['task_lease_v1']})['claim_id']
        bridge('approve',claim=c['claim'],name=c['name']);wait(lambda:any(g['claim']==c['claim'] for g in bridge('inspect')['grants']),25)
    def ui(scroll=None,click_label=None,recreate=False,full=False):
        rid=str(uuid.uuid4());args=['shell','am','broadcast','-n',CLIENT+'/io.airtap.preview.NativeOwnerUi','--es','request_id',rid]
        if scroll:args+=['--es','scroll',shlex.quote(scroll)]
        if click_label:args+=['--es','click',shlex.quote(click_label)]
        if recreate:args+=['--ez','recreate','true']
        device(*args);r=json.loads(device('shell','run-as',CLIENT,'cat','files/native-ui.json'));assert r.get('request_id')==rid and r.get('ok'),r
        return r if full else r['items']
    def find_button(label):
        return next((v for v in reversed(ui()) if v['text']==label and v['button'] and v['visible'] and v['enabled']),None)
    def click(label):
        ui(label);v=wait(lambda:find_button(label),10);left,top,right,bottom=v['bounds'];assert right>left and bottom>top
        result=ui(click_label=label,full=True);assert result['clicks']==1,result;time.sleep(.15)
        with (out/'ui-actions.private.jsonl').open('a') as f:f.write(json.dumps({'at':time.time(),'native_button_performClick':label,'bounds':v['bounds'],'actual_callbacks':result['clicks'],'owner_state':result.get('owner_state')},ensure_ascii=False)+'\n')
        return result
    def open_owner():
        device('shell','am','start','-n',CLIENT+'/io.airtap.preview.MainActivity');wait(lambda:find_button('设置'))
    def expand_management():
        label=next((v['text'] for v in ui() if v['button'] and re.fullmatch(r'我的 AI 连接（[1-4]）',v['text'])),None)
        if label:click(label)
    def capture_ui(name):
        value=ui();(out/(name+'.private.json')).write_text(json.dumps(value,ensure_ascii=False,indent=2));return value
    def assert_single(fence,seq):
        local=bridge('inspect');remote=status(A)
        assert local['multi'] is False and local['grant_fence']==fence and local['migration'] is None and local['connected'] and local['authorized'],local
        assert local['grants'][0]['claim']==A['claim'] and local['grants'][0]['sequence']==seq and remote['command_sequence']==seq,(local,remote)
        return {'same_claim':True,'same_fence':True,'sequence':seq,'connected':True}
    def new_single(reset=False):
        nonlocal d,A
        if reset:
            device('shell','settings','delete','secure','enabled_accessibility_services');device('shell','pm','clear',CLIENT)
            device('shell','pm','grant',CLIENT,'android.permission.POST_NOTIFICATIONS')
            device('shell','settings','put','secure','enabled_accessibility_services',CLIENT+'/io.airtap.preview.PreviewService');device('shell','settings','put','secure','accessibility_enabled','1')
        d={'id':secrets.token_hex(16),'token':secrets.token_hex(32)};d['base']='/d/'+d['id']
        A={'name':'Native UI synthetic A','token':secrets.token_hex(32),'seq':6}
        ok('/v1/enroll',body={'device_id':d['id'],'device_token':d['token']});bridge('seed',device_id=d['id'],token=d['token']);wait(lambda:bridge('inspect')['bound']);open_owner()
        invitation=ok(d['base']+'/v1/device/pairing',d['token'],method='POST')
        A['claim']=ok(d['base']+'/v1/pair',body={'ticket':invitation['ticket'],'controller_token':A['token'],'name':A['name']})['claim_id']
        bridge('approve',claim=A['claim'],name=A['name']);connect();good(A);return bridge('inspect')['grant_fence']
    def start_enable():
        click('连接多个自己的 AI');wait(lambda:find_button('开启并保留原连接'));click('开启并保留原连接')
    def wait_unknown_committed():
        wait(lambda:any(f['target']=='enable' and f['mode']=='drop_response' and f['commit_acknowledged']>0 for f in admin('fault_metrics')['metrics']['faults']),20)
        admin('fault_set',target='pairing',mode='disconnect')
        local=bridge('inspect');assert not local['multi'] and local['migration']['phase']=='unknown',local
        remote=ok(d['base']+'/v1/device/pairing',d['token']);assert remote['mode']=='multi';return local
    d=A=None;failure=None
    try:
        if a.suite=='experience':
            def has_text(text):return any(v['text']==text for v in ui())
            def collapsed(count):return find_button('我的 AI 连接（'+str(count)+'）')
            def single_first_task():
                fence=new_single();open_owner();wait(lambda:find_button('交给 AI 第一件事'))
                before=bridge('inspect');click('交给 AI 第一件事');wait(lambda:find_button('复制任务'))
                assert has_text('试着让 AI 跨 App 做件事')
                click('返回');after=bridge('inspect')
                assert before['grants']==after['grants'] and before['grant_fence']==after['grant_fence'] and not after['task_live']
                assert has_text('你的 AI 已连接')
                return {'real_single_activity':True,'dialog_open_cancel_preserves_grant':True,'example_does_not_start_task':True}
            check('single owner first-task dialog leaves connection and task state unchanged',single_first_task)
            def multi_default():
                start_enable();wait(lambda:bridge('inspect')['multi'],25);connect()
                task=acquire(A);release(A,task)
                wait(lambda:collapsed(1));assert not find_button('添加另一位自己的 AI')
                click('停止并解除所有 AI');wait(lambda:find_button('停止所有'));click('返回')
                assert len(bridge('inspect')['grants'])==1 and bridge('inspect')['authorized']
                click('我的 AI 连接（1）');wait(lambda:find_button('添加另一位自己的 AI'))
                assert find_button('收起 AI 连接管理')
                click('收起 AI 连接管理');wait(lambda:collapsed(1))
                assert not find_button('添加另一位自己的 AI')
                return {'actual_single_to_multi_preserved_A':True,'default_collapsed':True,'expand_collapse_callbacks':2,'stop_confirmation_cancel_keeps_connection':True}
            check('multi owner defaults to compact controls while management and stop remain usable',multi_default)
            B={'name':'Experience synthetic B','token':secrets.token_hex(32),'seq':0}
            def pending_visibility():
                click('我的 AI 连接（1）');click('添加另一位自己的 AI');wait(lambda:find_button('复制给我的 AI'),20)
                ui(recreate=True);wait(lambda:find_button('复制给我的 AI'),15)
                assert not collapsed(1) and has_text('连接自己的 AI')
                assert not has_text('交给 AI 第一件事')
                digits=wait(lambda:next((v['text'].replace(' ','') for v in ui() if re.fullmatch(r'[0-9]{4} [0-9]{4}',v['text'])),None),15)
                resolved=ok('/v1/invitations/resolve',body={'code':digits})
                candidate=ok(d['base']+'/v1/pair',body={'ticket':resolved['ticket'],'controller_token':B['token'],'name':B['name'],'capabilities':['task_lease_v1']});B['claim']=candidate['claim_id']
                wait(lambda:find_button('数字一致，连接这位 AI'),15)
                shown=candidate['verification_code'][:3]+' '+candidate['verification_code'][3:]
                assert has_text(shown) and not collapsed(1) and not has_text('交给 AI 第一件事')
                assert has_text('与自己的 AI 告诉你的六位数字核对。确认后它可持续查看和操作整台手机，也能按你的指令管理 AI 连接。AI 会自动核实连接，不用再回复“已点”。')
                click('数字一致，连接这位 AI');wait(lambda:len(bridge('inspect')['grants'])==2,20);connect();wait(lambda:collapsed(2),20)
                assert request('/v1/invitations/resolve',body={'code':digits})[0]==404
                return {'actual_invitation_and_resolve':True,'activity_recreation_does_not_hide_invitation':True,'matching_six_digit_candidate_visible':True,'actual_approval_callback':True,'approved_view_collapses_without_losing_A':True,'consumed_code_unusable':True}
            check('live invitation and matching approval remain visible through recreation and collapse only after completion',pending_visibility)
            def holder_handoff():
                for controller in [A,B]:
                    task=acquire(controller);good(controller,lease=task)
                    wait(lambda:has_text(controller['name']+' 已接手任务'),15)
                    assert not any('已完成任务' in v['text'] for v in ui())
                    release(controller,task);wait(lambda:has_text('你的 AI 已连接'),15)
                    assert not has_text(controller['name']+' 已接手任务')
                assert len(bridge('inspect')['grants'])==2
                return {'A_and_B_real_commands':True,'current_holder_displayed':True,'released_holder_removed':True,'no_task_completion_inferred':True}
            check('actual lease handoff changes owner title and release clears stale holder',holder_handoff)
            def stop_compact():
                wait(lambda:collapsed(2));click('停止并解除所有 AI');click('停止所有')
                wait(lambda:not bridge('inspect')['grants'],15);wait(lambda:not bridge('inspect')['all_stop_pending'],15)
                assert not bridge('inspect')['authorized'] and ok(d['base']+'/v1/device/grants',d['token'])['grants']==[]
                wait(lambda:not find_button('交给 AI 第一件事'),15)
                assert request(d['base']+'/v1/status',A['token'])[0] in [401,403]
                assert request(d['base']+'/v1/status',B['token'])[0] in [401,403]
                return {'actual_compact_stop_confirmation':True,'local_and_relay_grants_removed':True,'both_controllers_denied':True,'first_task_not_offered_without_grant':True}
            check('compact owner stop disables both controllers and removes the first-task affordance',stop_compact)
        elif a.suite=='migration':
            fence=new_single();open_owner();baseline_seq=A['seq']
            for mode in ['missing_caps','disconnect']:
                def refused(mode=mode):
                    before=bridge('inspect');admin('fault_set',target='service',mode=mode);click('连接多个自己的 AI');wait(lambda:find_button('知道了'),20);capture_ui('old-service-'+mode);click('知道了');fault=admin('fault_metrics')['metrics']['faults'][-1];assert fault['hits']>0 and fault['failures']==0 and (mode!='missing_caps' or fault['modified']==1),fault;admin('fault_clear')
                    evidence=assert_single(fence,A['seq']);good(A);return {**evidence,'real_command_after':A['seq']}
                check('actual Owner UI capability '+mode+' keeps original single usable',refused)
            def first_rejection():
                admin('fault_set',target='enable',mode='reject_404',times=1);start_enable();wait(lambda:any(f['mode']=='reject_404' and f['hits']>0 for f in admin('fault_metrics')['metrics']['faults']),20);wait(lambda:bridge('inspect')['migration'] is None,20);admin('fault_clear');e=assert_single(fence,A['seq']);good(A);return e
            check('actual Owner confirmation first POST404 reconciles original single without mode change',first_rejection)
            def lost_reply():
                admin('fault_set',target='enable',mode='drop_response',times=1);start_enable();local=wait_unknown_committed();admin('fault_clear');wait(lambda:bridge('inspect')['multi'],20);connect();now=bridge('inspect');assert now['grants'][0]['claim']==A['claim'] and now['grants'][0]['sequence']==A['seq'];good(A);return {'durable_unknown':local['migration']['phase'],'confirmed_by_read':True,'same_claim':True,'sequence_after_real_command':A['seq']}
            check('real unknown POST response is committed only after authenticated read',lost_reply)
            def stopped_unknown():
                admin('fault_clear');new_single(True);open_owner();admin('fault_set',target='enable',mode='drop_response',times=1);start_enable()
                # Counters are cumulative: read the actual remote mode as the second commit indicator.
                wait(lambda:ok(d['base']+'/v1/device/pairing',d['token']).get('mode')=='multi',20);admin('fault_set',target='pairing',mode='disconnect')
                local=bridge('inspect');assert not local['multi'] and local['migration']['phase']=='unknown',local
                bridge('notification_stop');wait(lambda:not bridge('inspect')['grants']);admin('fault_clear');wait(lambda:bridge('inspect')['multi'],20)
                local=bridge('inspect');assert not local['grants'] and not local['authorized'];wait(lambda:find_button('解除旧连接后重新准备'),15);capture_ui('stopped-remote-only-cleanup')
                click('解除旧连接后重新准备');click('停止所有');wait(lambda:not bridge('inspect')['all_stop_pending'],15);wait(lambda:find_button('添加另一位自己的 AI'),15)
                assert ok(d['base']+'/v1/device/grants',d['token'])['grants']==[]
                invitation_clicks=0
                for _ in range(3):
                    if find_button('复制给我的 AI'):break
                    click('添加另一位自己的 AI');invitation_clicks+=1;time.sleep(.8)
                wait(lambda:find_button('复制给我的 AI'),15)
                remote=ok(d['base']+'/v1/device/grants',d['token']);assert remote['invitation'] is not None and not bridge('inspect')['grants'];capture_ui('new-invitation-after-explicit-cleanup')
                return {'notification_pending_intent_sent':True,'empty_mode_no_local_consent':True,'explicit_generation_stop':True,'new_invitation_created':True,'invitation_button_clicks':invitation_clicks}
            check('notification STOP after unknown commit recovers empty mode then explicit cleanup allows new invite',stopped_unknown)
            def retry_old_target():
                admin('fault_clear');new_single(True);admin('fault_set',target='revoke',mode='disconnect')
                # Synthetic precondition models a previously persisted original-target revoke with lost network result.
                bridge('queue_revoke',claim=A['claim']);bridge('notification_stop');wait(lambda:not bridge('inspect')['grants']);ok(d['base']+'/v1/device/grants/revoke',d['token'],{'claim_id':A['claim']})
                old_claim=A['claim'];B={'name':'Native UI synthetic B','token':secrets.token_hex(32),'seq':0};inv=ok(d['base']+'/v1/device/pairing',d['token'],method='POST');B['claim']=ok(d['base']+'/v1/pair',body={'ticket':inv['ticket'],'controller_token':B['token'],'name':B['name']})['claim_id']
                bridge('approve',claim=B['claim'],name=B['name']);connect();good(B);local=bridge('inspect');Bfence=local['grant_fence'];open_owner();click('设置');click('重试解除 AI 连接');time.sleep(.8)
                later=bridge('inspect');assert later['grant_fence']==Bfence and later['connected'] and later['authorized'] and old_claim in later['pending_claim_revokes'];good(B);capture_ui('retry-keeps-later-B')
                click('返回');click('停止并解除 AI 连接');wait(lambda:not bridge('inspect')['grants']);later=bridge('inspect');assert set(later['pending_claim_revokes'])=={old_claim,B['claim']} and not later['authorized'];admin('fault_clear');bridge('flush')
                return {'old_claim_queue_seeded_as_precondition':True,'real_settings_retry_kept_B':True,'real_command_on_B_after_retry':True,'new_owner_stop_stopped_B':True,'two_targets_persisted':True}
            check('actual settings retry of old A does not stop new B while new STOP does',retry_old_target)
        else:
            def metrics():return admin('fault_metrics')['metrics']
            def fault_state(fid):return next(f for f in metrics()['faults'] if f['fault_id']==fid)
            def set_fault(target,mode):return admin('fault_set',target=target,mode=mode,times=1)['fault']['fault_id']
            def owner_state():return ui(full=True).get('owner_state',{})
            def native_invitation():return bridge('inspect')['invitation']
            def multi_setup(reset):
                admin('fault_clear');new_single(reset);start_enable();wait(lambda:bridge('inspect')['multi'],25);connect()
                task=acquire(A);release(A,task)
                wait(lambda:any(v['button'] and re.fullmatch(r'我的 AI 连接（[1-4]）',v['text']) for v in ui()),15);expand_management()
                wait(lambda:find_button('添加另一位自己的 AI'),15)
            def unknown_remote():
                fid=set_fault('grants','disconnect');ui(recreate=True)
                wait(lambda:fault_state(fid)['hits']==1,15)
                state=wait(lambda:(lambda s:s if s.get('visible') and not s.get('busy') and not s.get('remote_known') else None)(owner_state()),15)
                assert not state['invitation_read'] and fault_state(fid)['failures']==0
                expand_management()
                return state
            def hold_explicit(label='添加另一位自己的 AI'):
                unknown_remote();before=metrics()['request_counts']['invitation'];fid=set_fault('grants','hold_response')
                result=click(label);wait(lambda:fault_state(fid)['held']==1,10)
                state=owner_state();assert state['invitation_read'] and state['busy'] and not state['remote_known'],state
                f=fault_state(fid);assert f['hits']==f['forwarded']==f['upstream_completed']==1 and f['failures']==0,f
                return fid,before
            def released(fid,closed):
                admin('fault_release',fault_id=fid);f=fault_state(fid)
                assert f['released']==1 and f['hold_expired']==0 and f['failures']==0 and f['released_after_close']==int(closed),f
                return f
            def first_click():
                multi_setup(False);fid,before=hold_explicit();released(fid,False)
                wait(lambda:find_button('复制给我的 AI'),15);local=native_invitation();remote=ok(d['base']+'/v1/device/grants',d['token'])
                assert metrics()['request_counts']['invitation']==before+1 and local['phase']=='ready' and remote['invitation']['invitation_id']==local['invitation_id']
                digits=wait(lambda:next((v['text'].replace(' ','') for v in ui() if re.fullmatch(r'[0-9]{4} [0-9]{4}',v['text']) and v['visible']),None),15)
                resolved=ok('/v1/invitations/resolve',body={'code':digits});assert resolved['device_base_path']==d['base'] and resolved['ticket']==local['ticket'] and resolved['task_lease_required']
                ui(recreate=True);wait(lambda:find_button('复制给我的 AI'),15)
                again=next(v['text'].replace(' ','') for v in ui() if re.fullmatch(r'[0-9]{4} [0-9]{4}',v['text']))
                assert again==digits and metrics()['request_counts']['invitation']==before+1
                capture_ui('eight-digit-invitation-after-recreate')
                return {'explicit_add_callbacks':1,'held_real_get':fault_state(fid),'invitation_posts':1,'same_remote_id':True,'visible_eight_digits_resolved':True,'same_code_after_activity_recreate':True}
            check('one actual Owner add callback from unknown remote state creates exactly one invitation after real GET',first_click)
            def approve_short_code():
                digits=next(v['text'].replace(' ','') for v in ui() if re.fullmatch(r'[0-9]{4} [0-9]{4}',v['text']))
                resolved=ok('/v1/invitations/resolve',body={'code':digits})
                B={'name':'Short code synthetic B','token':secrets.token_hex(32),'seq':0}
                candidate=ok(d['base']+'/v1/pair',body={'ticket':resolved['ticket'],'controller_token':B['token'],'name':B['name'],'capabilities':['task_lease_v1']});B['claim']=candidate['claim_id']
                wait(lambda:find_button('数字一致，连接这位 AI'),15)
                shown=candidate['verification_code'][:3]+' '+candidate['verification_code'][3:]
                assert any(v['text']==shown for v in ui());click('数字一致，连接这位 AI')
                wait(lambda:any(g['claim']==B['claim'] for g in bridge('inspect')['grants']),20);connect()
                for agent in [A,B]:
                    task=acquire(agent);good(agent,lease=task);release(agent,task)
                assert request('/v1/invitations/resolve',body={'code':digits})[0]==404
                assert len(bridge('inspect')['grants'])==2
                return {'actual_phone_owner_button':True,'six_digit_comparison_matched':True,'independent_A_and_B_native_ping_and_release':True,'code_unusable_after_approval':True}
            check('short code claim reaches actual six-digit owner approval and both connections remain usable',approve_short_code)
            def late_stop():
                multi_setup(True);fid,before=hold_explicit();click('停止并解除所有 AI');click('停止所有')
                wait(lambda:not bridge('inspect')['grants'],10);released(fid,True);time.sleep(1)
                local=bridge('inspect');assert local['invitation'] is None and not local['authorized'] and metrics()['request_counts']['invitation']==before
                return {'real_stop_dialog_callbacks':2,'held_get_released_after_client_cancel':True,'invitation_posts':0,'no_local_consent':True}
            check('STOP while explicit add GET is held cancels the intent and never creates a late invitation',late_stop)
            def start_unknown_invitation():
                fid=set_fault('invitation','drop_response');before=metrics()['request_counts']['invitation'];click('添加另一位自己的 AI')
                wait(lambda:fault_state(fid)['dropped']==1,15);wait(lambda:not owner_state().get('busy',True),15)
                f=fault_state(fid);local=native_invitation();assert f['commit_acknowledged']==1 and f['hits']==1 and f['failures']==0 and local['phase']=='creating',(f,local)
                return fid,before,local
            def late_cancel():
                multi_setup(True);drop,unused,original=start_unknown_invitation();fid,before=hold_explicit('继续准备原邀请');click('取消本次邀请')
                wait(lambda:native_invitation() is None,10);released(fid,True);time.sleep(1)
                assert native_invitation() is None and metrics()['request_counts']['invitation']==before
                assert ok(d['base']+'/v1/device/grants',d['token'])['invitation'] is None
                return {'real_cancel_callback':1,'prior_unknown_real_commit':fault_state(drop),'held_get_released_after_client_cancel':True,'additional_invitation_posts':0}
            check('cancel original unknown invitation while its continuation GET is held cannot recreate it',late_cancel)
            def late_pause():
                multi_setup(True);fid,before=hold_explicit();device('shell','input','keyevent','KEYCODE_HOME');time.sleep(.3);released(fid,True);time.sleep(1)
                assert native_invitation() is None and metrics()['request_counts']['invitation']==before
                open_owner();time.sleep(1);assert native_invitation() is None and metrics()['request_counts']['invitation']==before
                return {'actual_activity_pause_and_resume':True,'held_get_released_after_client_cancel':True,'invitation_posts':0}
            check('actual HOME pause abandons held add GET and resume does not create an invitation',late_pause)
            def unknown_retry():
                multi_setup(True);fid,before,original=start_unknown_invitation();remote=ok(d['base']+'/v1/device/grants',d['token'])
                assert remote['invitation']['invitation_id']==original['invitation_id'];click('继续准备原邀请');wait(lambda:find_button('复制给我的 AI'),15)
                after=native_invitation();assert all(original[k]==after[k] for k in ('invitation_id','invitation_sequence','ticket'))
                assert metrics()['request_counts']['invitation']==before+2 and after['phase']=='ready'
                return {'dropped_real_commit':fault_state(fid),'explicit_retry_callbacks':1,'invitation_posts':2,'same_id_sequence_ticket':True}
            check('unknown committed invitation POST retry keeps its original ID sequence and ticket',unknown_retry)
    except Exception as e:
        import traceback
        failure={'type':type(e).__name__,'detail':str(e),'traceback':traceback.format_exc()};print(json.dumps({'failed':failure}),flush=True)
    finally:
        try:capture_ui('final-ui');(out/'final-native.private.json').write_text(json.dumps(bridge('inspect'),indent=2));(out/'fault-metrics.private.json').write_text(json.dumps(admin('fault_metrics'),indent=2))
        except Exception:pass
        pid=device('shell','pidof',CLIENT,check=False).strip()
        if pid:(out/'app-logcat.private.txt').write_text(device('logcat','-d','--pid='+pid,'-v','threadtime',check=False))
        try:admin('shutdown')
        except Exception:relay.terminate()
        relay.wait(timeout=15);log.close();pool.shutdown(wait=False)
        report={'suite':a.suite,'complete':failure is None,'checks':checks,'failure':failure,'serial':a.serial,'production_build_metadata':str(base/'build-metadata.private.json'),'owner_ui':'Actual production MainActivity and AlertDialog Button.performClick dispatched by test-UID-only WindowInspector driver; real listeners and async HTTP, not physical touch acceptance. No uiautomator, protected screen capture, UI replacement or production permission-gate substitution.','limits':['Isolated emulator, synthetic enrolled controllers and prior-revoke precondition, test-only driver and private CA','OS permissions configured by ADB only in disposable emulator; not first-user permission onboarding','No physical phone, public deployment, personal AI or unattended/OEM acceptance','Prior 12 native multi/gesture/IO checks were not repeated for this revision']}
        (out/'result.private.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        for path in out.rglob('*'):
            if path.is_file():path.chmod(0o600)
    if failure:raise SystemExit(1)
if __name__=='__main__':main()
