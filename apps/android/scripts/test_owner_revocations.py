#!/usr/bin/env python3
"""Exercise real owner-revocation persistence with synthetic preferences and delayed HTTP."""
import argparse, datetime, hashlib, importlib.util, json, os, subprocess, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
PRODUCTION=ROOT/'app/src/main/java/io/airtap/preview'
BASE=ROOT/'tests/approvalrecovery/platform_fakes.py'
spec=importlib.util.spec_from_file_location('revocation_storage_fakes',BASE)
base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)
fakes=dict(base.SOURCES)
fakes['io/airtap/preview/EnrollmentStore.java']=fakes['io/airtap/preview/EnrollmentStore.java'].replace('Config read(){return FakeDisk.config;}', 'static boolean failRead;Config read(){if(failRead)throw new IllegalStateException("synthetic unreadable enrollment");return FakeDisk.config;}')
fakes['io/airtap/preview/RelayApi.java']='''package io.airtap.preview;import org.json.JSONObject;import java.util.*;
final class RelayApi {interface Reply {void done(JSONObject body,int status,String error);}static boolean failStart;static final class Request {final String url,token,path,method;final JSONObject body;final Reply reply;Request(String u,String p,String m,String t,JSONObject b,Reply r){url=u;path=p;method=m;token=t;body=b;reply=r;}void respond(JSONObject b,int s,String e){reply.done(b,s,e);}}
static final List<Request> requests=new ArrayList<>();static void requestStatus(String url,String path,String method,String token,JSONObject body,Reply reply){if(failStart)throw new IllegalStateException("synthetic request start refusal");boolean get="GET".equals(method)&&"/v1/device/grants".equals(path)&&body==null;boolean post="POST".equals(method)&&("/v1/device/revoke".equals(path)||"/v1/device/grants/revoke".equals(path));if(!get&&!post)throw new AssertionError("unexpected endpoint");requests.add(new Request(url,path,method,token,body==null?null:body.copy(),reply));}
static JSONObject json(Object... args){JSONObject value=new JSONObject();for(int i=0;i<args.length;i+=2)value.put((String)args[i],args[i+1]);return value;}}
'''
p=argparse.ArgumentParser();p.add_argument('--output',type=Path);args=p.parse_args()
sources=[PRODUCTION/'OwnerRevocations.java',PRODUCTION/'ServiceProtocol.java',ROOT/'tests/approvalrecovery/OwnerRevocationsTest.java']
jdk=Path(os.environ.get('JAVA_HOME','/Applications/Android Studio.app/Contents/jbr/Contents/Home'))/'bin'
with tempfile.TemporaryDirectory(prefix='airtap-owner-revocations-') as temporary:
    temporary=Path(temporary);generated=[]
    for name,value in fakes.items():
        file=temporary/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(value);generated.append(file)
    subprocess.run([str(jdk/'javac'),'--release','17','-d',str(temporary),*map(str,sources),*map(str,generated)],check=True,timeout=30)
    run=subprocess.run([str(jdk/'java'),'-cp',str(temporary),'io.airtap.preview.OwnerRevocationsTest'],text=True,capture_output=True,timeout=30)
print(run.stdout,end='')
if run.returncode:print(run.stderr,end='');raise SystemExit(run.returncode)
if args.output:
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps({'status':'passed','recorded_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'passed':run.stdout.count('PASS '),'checks':run.stdout.splitlines(),
        'source_sha256':{str(f.relative_to(ROOT)):hashlib.sha256(f.read_bytes()).hexdigest() for f in [*sources,BASE,Path(__file__)]},
        'limits':['Production OwnerRevocations and ServiceProtocol compile verbatim. Preferences commit faults, enrollment and HTTP responses are synthetic host fixtures.',
                  'No real HTTP, Android preferences durability, owner UI, PreviewService, device action or remote revoke confirmation was exercised.']},indent=2)+'\n')
