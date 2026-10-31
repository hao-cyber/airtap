#!/usr/bin/env python3
"""Run production durable migration with controlled storage and HTTP completion ordering."""
import argparse,datetime,hashlib,importlib.util,json,os,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];JAVA=ROOT/'app/src/main/java/io/airtap/preview'
p=argparse.ArgumentParser();p.add_argument('--output',type=Path);args=p.parse_args()
spec=importlib.util.spec_from_file_location('fakes',ROOT/'tests/approvalrecovery/platform_fakes.py');f=importlib.util.module_from_spec(spec);spec.loader.exec_module(f)
fixtures=dict(f.SOURCES)
fixtures['org/json/JSONArray.java']=fixtures['org/json/JSONArray.java'].replace('public JSONObject getJSONObject(int i)','public Object get(int i){return v.get(i);}public JSONObject getJSONObject(int i)')
fixtures['org/json/JSONObject.java']=fixtures['org/json/JSONObject.java'].replace('public JSONObject put(', 'public int length(){return fields.size();}public JSONObject put(')
fixtures['io/airtap/preview/RelayApi.java']='''package io.airtap.preview;import org.json.JSONObject;import java.util.*;
final class RelayApi{interface Reply{void done(JSONObject b,int s,String e);}static final class Request{final String path,method,token;final JSONObject body;final Reply reply;Request(String p,String m,String t,JSONObject b,Reply r){path=p;method=m;token=t;body=b;reply=r;}void respond(JSONObject b,int s,String e){reply.done(b,s,e);}}
static List<Request> requests=new ArrayList<>();static void requestStatus(String url,String path,String method,String token,JSONObject body,Reply reply){if(!((path.equals("/v1/device/pairing")&&method.equals("GET")&&body==null)||(path.equals("/v1/device/multi/enable")&&method.equals("POST"))))throw new AssertionError();requests.add(new Request(path,method,token,body,reply));}
static JSONObject json(Object...args){JSONObject r=new JSONObject();for(int i=0;i<args.length;i+=2)r.put((String)args[i],args[i+1]);return r;}}
'''
sources=[JAVA/(n+'.java') for n in ['TrustedGrant','MigrationRecovery','MultiAiProtocol','ServiceProtocol','TaskLease','OwnerRevocations']]
test=ROOT/'tests/multi/MigrationRecoveryTest.java';jdk=Path(os.environ['JAVA_HOME'])/'bin'
with tempfile.TemporaryDirectory(prefix='airtap-migration-') as directory:
 temp=Path(directory);generated=[]
 for name,value in fixtures.items():
  path=temp/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(value);generated.append(path)
 subprocess.run([str(jdk/'javac'),'--release','17','-d',str(temp),*map(str,sources),str(test),*map(str,generated)],check=True)
 run=subprocess.run([str(jdk/'java'),'-cp',str(temp),'io.airtap.preview.MigrationRecoveryTest'],capture_output=True,text=True);print(run.stdout,end='')
 if run.returncode:raise SystemExit(run.stderr)
if args.output:
 args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps({'status':'passed','passed':run.stdout.count('PASS '),'checks':run.stdout.splitlines(),'recorded_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Production migration recovery and TrustedGrant with synthetic Android storage/time/HTTP replies, no actual Android UI or network.','production_sha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}},indent=2)+'\n')
