#!/usr/bin/env python3
"""Opt-in native production-client/workerd checks. All identities and target content are synthetic."""
import argparse, concurrent.futures, hashlib, json, os, queue, secrets, shlex, subprocess, threading, time, uuid
from pathlib import Path
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[4]
CLIENT='io.airtap.fixture.multi';TARGET='io.airtap.fixture.multitarget'

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--serial',required=True);p.add_argument('--build',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--sdk',type=Path,required=True);p.add_argument('--reset-created',action='store_true');p.add_argument('--suite',choices=['full','leases','lease-idle'],default='full');p.add_argument('--source-root',type=Path,default=ROOT);a=p.parse_args()
    if not a.serial.startswith('emulator-'):p.error('Only disposable emulator targets are accepted')
    out=a.output.resolve();out.mkdir(mode=0o700,parents=True,exist_ok=False);base=a.build.resolve()
    metadata=json.loads((base/'build-metadata.private.json').read_text());production=a.source_root.resolve()
    for relative,sha in metadata['production_java_sha256'].items():
        if hashlib.sha256((production/'apps/android'/relative).read_bytes()).hexdigest()!=sha:raise RuntimeError('production_java_changed_since_build')
    for name in ['client','target']:
        if hashlib.sha256((base/(name+'.apk')).read_bytes()).hexdigest()!=metadata[name+'_sha256']:raise RuntimeError('built_apk_changed')
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
    relay=subprocess.Popen(['node',str(HERE/'relay.mjs'),'--root',str(production),'--key',str(base/'tls/key.pem'),'--cert',str(base/'tls/cert.pem'),'--port','8443','--state-dir',str(out/'relay-state')],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=log,text=True,bufsize=1)
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
    failure=None
    try:
        d={'id':secrets.token_hex(16),'token':secrets.token_hex(32)};d['base']='/d/'+d['id']
        A={'name':'Native synthetic A','token':secrets.token_hex(32),'seq':6};B={'name':'Native synthetic B','token':secrets.token_hex(32),'seq':0}
        ok('/v1/enroll',body={'device_id':d['id'],'device_token':d['token']});bridge('seed',device_id=d['id'],token=d['token'])
        wait(lambda:bridge('inspect')['bound'])
        invitation=ok(d['base']+'/v1/device/pairing',d['token'],method='POST')
        A['claim']=ok(d['base']+'/v1/pair',body={'ticket':invitation['ticket'],'controller_token':A['token'],'name':A['name']})['claim_id']
        bridge('approve',claim=A['claim'],name=A['name']);connect()
        check('real TLS/WS single approval and command sequence 7',lambda:(good(A),status(A)['command_sequence']))
        def migrate():
            bridge('enable');connect();good(A);s=status(A);local=bridge('inspect');assert s['command_sequence']==8 and local['grants'][0]['sequence']==8 and local['grants'][0]['claim']==A['claim'];return {'sequence':8,'same_claim':True,'multi':local['multi']}
        check('explicit local single-to-multi migration preserves identity and sequence',migrate)
        tA=acquire(A)
        def required():
            code,r=cmd(A);assert code==409 and r['error']=='lease_required';good(A,lease=tA);assert bridge('inspect')['grants'][0]['lease_required'];return {'unleased_rejected':True}
        check('real task fence permanently requires lease',required)
        if a.suite in ('leases','lease-idle'):
            # Real elapsed time and real Android TaskLease, not a shortened TTL
            # or fake phone. Admin calls exercise the production relay protocol;
            # automatic CLI renewal has its own separate cross-process suite.
            def native_lease():
                current=bridge('inspect')
                return {k:current[k] for k in ('task_epoch','task_live','task_idle','task_remaining_ms','task_renewal_sequence')}
            def sustained():
                started=time.monotonic();initial=native_lease();events=[];renewed=tA
                assert initial['task_live'] and initial['task_epoch']==tA['lease_epoch'] and initial['task_renewal_sequence']==0,initial
                previous_deadline=tA['expires_at_ms']
                for sequence in (1,2):
                    target=started+sequence*70
                    while time.monotonic()<target:time.sleep(max(0,min(10,target-time.monotonic())))
                    before=native_lease();assert before['task_live'] and before['task_epoch']==tA['lease_epoch'],before
                    body={k:tA[k] for k in ('task_id','lease_id','lease_epoch')};body.update(renewal_id=secrets.token_hex(16),renewal_sequence=sequence)
                    renewed=ok(d['base']+'/v1/tasks/renew',A['token'],body)
                    assert all(renewed[k]==tA[k] for k in ('task_id','lease_id','lease_epoch')),renewed
                    assert renewed['renewal_sequence']==sequence and renewed['expires_at_ms']>previous_deadline
                    after=native_lease();assert after['task_live'] and after['task_epoch']==tA['lease_epoch'] and after['task_renewal_sequence']==sequence and after['task_remaining_ms']>110000,after
                    retry=ok(d['base']+'/v1/tasks/renew',A['token'],body);after_retry=native_lease()
                    assert retry['expires_at_ms']==renewed['expires_at_ms'] and after_retry['task_remaining_ms']<=after['task_remaining_ms'],(retry,after_retry)
                    good(A,lease=tA)
                    events.append({'elapsed_seconds':round(time.monotonic()-started,3),'sequence':sequence,'before':before,'after':after,'retry':after_retry,'same_identity':True})
                    previous_deadline=renewed['expires_at_ms'];print(json.dumps({'lease_progress':events[-1]}),flush=True)
                assert time.time()*1000>tA['expires_at_ms'],'must operate beyond original deadline'
                good(A,lease=tA);release(A,tA);closed=native_lease()
                assert not closed['task_live'] and closed['task_idle'] and closed['task_epoch']>tA['lease_epoch'],closed
                code,result=cmd(A,lease=tA);assert code==410 and result['error']=='lease_expired' and result.get('admitted') is False,(code,result)
                return {'actual_seconds':round(time.monotonic()-started,3),'renewals':events,'released_native':closed,'old_lease_command_status':code}
            if a.suite=='leases':
                check('real Android same-epoch renewals survive the original TTL and release rejects the old lease',sustained)
                idle=acquire(A)
            else:idle=tA
            def idle_expiry():
                first=native_lease();before=status(A);before_sequence=before['command_sequence'];assert first['task_live'],first
                # Status/bridge inspection is not a device command and does not
                # renew. No command or renewal is sent during this idle window.
                deadline=time.monotonic()+max(0,idle['expires_at_ms']/1000-time.time())+2
                started=time.monotonic()
                while time.monotonic()<deadline:time.sleep(max(0,min(10,deadline-time.monotonic())))
                # A status request also runs relay expiration. First prove the
                # native authority is expired without allowing status to repair
                # it. A clear fence and a retired connection are both valid;
                # idle() specifically means ready/null-holder, not all expiry.
                def native_expired():
                    value=native_lease()
                    return value if not value['task_live'] and value['task_remaining_ms']==0 else None
                local=wait(native_expired,10);remote=status(A)
                assert remote['task']['state']=='idle' and remote['command_sequence']==before_sequence,remote
                code,result=cmd(A,lease=idle);assert code==410 and result['error']=='lease_expired' and result.get('admitted') is False,(code,result)
                body={k:idle[k] for k in ('task_id','lease_id','lease_epoch')};body.update(renewal_id=secrets.token_hex(16),renewal_sequence=1)
                renewed,refusal=request(d['base']+'/v1/tasks/renew',A['token'],body);assert renewed==410 and refusal['error']=='lease_expired',(renewed,refusal)
                final=status(A);assert final['command_sequence']==before_sequence and final['task']['state']=='idle',final
                return {'idle_seconds':round(time.monotonic()-started,3),'native_before_status':local,'native_authority_expired_before_status':True,
                        'clear_fence_observed':local['task_idle'] and local['task_epoch']>idle['lease_epoch'],
                        'connection_changed':remote.get('connection_id')!=before.get('connection_id'),'no_command_consumed':True,'command_status':code,'renew_status':renewed}
            check('real Android idle expires native authority before status and rejects late commands or renewals',idle_expiry)
            local_before=bridge('inspect');assert local_before['ui_quiescent'] and local_before['file_quiescent'],local_before
            fresh=acquire(A);assert fresh['lease_epoch']>idle['lease_epoch'];good(A,lease=fresh);release(A,fresh)
            def later_task():
                remote=status(A);local=native_lease()
                assert remote['task']['state']=='idle' and local['task_idle'] and not local['task_live'],(remote,local)
                return {'remote':'idle','local_idle':True,'original_pairing_retained':True}
            check('explicit later task still works with the original approved pairing',later_task)
            return
        def independent():
            invite(B);code,r=request(d['base']+'/v1/tasks/acquire',B['token'],{'task_id':secrets.token_hex(16)})
            assert code==429 and r['error']=='device_busy';assert status(A)['task']['lease_id']==tA['lease_id']
            return {'B_busy':True,'A_task_preserved':True,'local_grants':len(bridge('inspect')['grants'])}
        check('independent B approval retains A and refuses busy task',independent)
        def renewal():
            body={k:tA[k] for k in ('task_id','lease_id','lease_epoch')};body.update(renewal_id=secrets.token_hex(16),renewal_sequence=1)
            one=ok(d['base']+'/v1/tasks/renew',A['token'],body);retry=ok(d['base']+'/v1/tasks/renew',A['token'],body);assert one['expires_at_ms']==retry['expires_at_ms'];return {'renewal_sequence':one['renewal_sequence']}
        check('real renewal exact retry is idempotent',renewal)
        staleTree=good(A,'read_tree',tA,package=TARGET)
        release(A,tA);tB=acquire(B)
        def isolation():
            code,r=cmd(A,lease=tA);assert code==409;good(B,lease=tB);return {'old_lease_rejected':True,'B_sequence':status(B)['command_sequence']}
        check('handoff invalidates A lease and admits B independently',isolation)
        def reconnect():
            admin('close_ws');wait(lambda:not bridge('inspect')['connected'],10);connect();code,r=cmd(B,lease=tB);assert code in (409,410,503) and r.get('admitted') is False,(code,r);return {'old_task_rejected':True,'error':r['error'],'status':code,'local_grants':len(bridge('inspect')['grants'])}
        check('network reconnect preserves grants but rejects old task',reconnect)
        tB=acquire(B)
        def gesture():
            observation=good(B,'observe',tB);f=pool.submit(cmd,B,'swipe',tB,package=TARGET,observation_id=observation['observation_id'],x=.25,y=.65,end_x=.75,end_y=.75,duration_ms=5000)
            wait(lambda:not bridge('inspect')['ui_quiescent'],4)
            start=time.monotonic();r=request(d['base']+'/v1/tasks/release',B['token'],{k:tB[k] for k in ('task_id','lease_id','lease_epoch')});during=bridge('inspect');f.result(timeout=15)
            wait(lambda:bridge('inspect')['ui_quiescent'],8)
            if r[0]!=200:release(B,tB)
            events=device('shell','run-as',TARGET,'cat','files/events.jsonl');touch=[json.loads(x) for x in events.splitlines()]
            assert r[0]==503 and r[1]['error']=='task_fence_pending' and during['ui_quiescent'] is False
            assert any(x.get('action')==0 for x in touch) and touch[-1].get('action') in (1,3)
            return {'first_release_status':r[0],'first_release_body':r[1],'elapsed':time.monotonic()-start,'quiescent_after_first':during.get('ui_quiescent'),'events':events}
        check('real Accessibility gesture drain before task handoff',gesture)
        tA=acquire(A)
        def saf():
            device('shell','am','start','-n',CLIENT+'/io.airtap.preview.NativeFolderActivity','--es','claim',A['claim']);time.sleep(1)
            r=json.loads(device('shell','run-as',CLIENT,'cat','files/native-folder.json'));assert r['ok'];device('shell','am','start','-n',TARGET+'/test.multi.TargetActivity');time.sleep(.5)
            roots=good(A,'file_roots',tA)['roots'];saf=next(r['root'] for r in roots if r['kind']=='saf')
            device('shell','am','broadcast','-n',TARGET+'/test.multi.ConfigureReceiver','--ei','delay_ms','16000')
            f=pool.submit(cmd,A,'file_list',tA,root=saf,path='',offset=0,limit=8)
            wait(lambda:not bridge('inspect')['file_quiescent'],4);time.sleep(12.5)
            after_timeout=bridge('inspect');assert after_timeout['file_quiescent'] is False
            r=request(d['base']+'/v1/tasks/release',A['token'],{k:tA[k] for k in ('task_id','lease_id','lease_epoch')})
            assert r[0]!=200,(r,after_timeout)
            wait(lambda:bridge('inspect')['file_quiescent'],15);command_result=f.result(timeout=5);assert command_result[0]==200 and command_result[1].get('error',{}).get('code')=='file_provider_timeout',command_result;release(A,tA)
            return {'after_response_timeout_still_inflight':True,'first_release_status':r[0],'command_status':command_result[0],'provider_events':device('shell','run-as',TARGET,'cat','files/provider-events.jsonl')}
        check('actual Binder provider remains in flight after HTTP response timeout',saf)
        tB=acquire(B)
        def revoke():
            bridge('revoke',claim=A['claim']);wait(lambda:len(bridge('inspect')['grants'])==1);good(B,lease=tB);code,r=request(d['base']+'/v1/status',A['token']);assert code in (401,410) and r.get('error') in ('unauthorized','expired_or_revoked'),(code,r);return {'A_revoked':True,'B_still_operates':True,'revoked_status':code,'revoked_error':r['error']}
        check('single grant revoke preserves other task and controller',revoke)
        generation=ok(d['base']+'/v1/device/grants',d['token'])['generation']
        def stop():
            bridge('stop',generation=generation);wait(lambda:not bridge('inspect')['all_stop_pending'],15);local=bridge('inspect');assert not local['grants'] and not local['authorized'];return {'local_stopped':True,'remote_grants':len(ok(d['base']+'/v1/device/grants',d['token'])['grants'])}
        check('native durable full stop clears local and remote grants',stop)
        C={'name':'Native synthetic C','token':secrets.token_hex(32),'seq':0};invite(C);connect();tC=acquire(C)
        def oldStop():
            ok(d['base']+'/v1/device/revoke',d['token'],{'expected_generation':generation});good(C,lease=tC);return {'new_grant_survives_old_generation_stop':True}
        check('old full-stop retry cannot revoke later approved grant',oldStop)
    except Exception as e:
        failure={'type':type(e).__name__,'detail':str(e)};print(json.dumps({'failed':failure}),flush=True)
    finally:
        try:(out/'final-native.private.json').write_text(json.dumps(bridge('inspect'),indent=2))
        except Exception:pass
        pid=device('shell','pidof',CLIENT,check=False).strip()
        if pid:(out/'app-logcat.private.txt').write_text(device('logcat','-d','--pid='+pid,'-v','threadtime',check=False))
        try:admin('shutdown')
        except Exception:relay.terminate()
        relay.wait(timeout=15);log.close();pool.shutdown(wait=False)
        unchanged=all(hashlib.sha256((production/'apps/android'/relative).read_bytes()).hexdigest()==sha for relative,sha in metadata['production_java_sha256'].items())
        if not unchanged and failure is None:failure={'type':'source_changed','detail':'Production Java changed during run'}
        report={'complete':failure is None,'suite':a.suite,'checks':checks,'failure':failure,'serial':a.serial,'physical_phone':False,'personal_ai':False,'production_java_unchanged':unchanged,'owner_actions':'Synthetic driver invokes production owner persistence/restore methods; does not validate human-facing confirmation UI. Real OS accessibility and notification grants were configured by ADB in this new disposable emulator.','limits':['No normal-user first-run or real personal AI acceptance','No OEM/physical lifecycle claim','Test-only package ID, CA and owner bridge; no control/permission BooleanSupplier substitution']}
        (out/'result.private.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        for path in out.rglob('*'):
            if path.is_file():path.chmod(0o600)
        if failure:raise SystemExit(1)
    if failure:raise SystemExit(1)
if __name__=='__main__':main()
