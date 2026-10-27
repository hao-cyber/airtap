#!/usr/bin/env python3
"""Offline SPKI and additive platform-chain tests; native Android TLS has a separate probe."""
import argparse,hashlib,json,os,re,subprocess,tempfile
from pathlib import Path
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path);args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
fixture=root/'tests/tls';source=root/'app/src/main/java/io/airtap/preview'
expected={'impacted-intermediates.json':'f67b3abe9b4879f4e91874ed041beccd71257f1db41c588c724ccf12aa90c59e','roots.json':'119f32cd81a6b5a4e3c4c2df5fe07daa90dc43cafa123a62f0394f1895d92380'}
for name,digest in expected.items():assert hashlib.sha256((fixture/name).read_bytes()).hexdigest()==digest,name
with tempfile.TemporaryDirectory(prefix='airtap-tls-') as temporary:
 out=Path(temporary);rows=[]
 for i,item in enumerate(json.loads((fixture/'impacted-intermediates.json').read_text())):
  pem=out/f'{i}.pem';pem.write_text(item['pem']);rows.append('\t'.join([str(pem),item['sha256'].lower(),item['spki_sha256'].lower()]))
 (out/'certificates.tsv').write_text('\n'.join(rows));(out/'root.pem').write_text(json.loads((fixture/'roots.json').read_text())[0]['pem'])
 java=Path(os.environ['JAVA_HOME'])/'bin' if os.environ.get('JAVA_HOME') else Path('/usr/bin')
 subprocess.run([str(java/'javac'),'--release','17','-d',str(out),*[str(p) for p in (fixture/'host').rglob('*.java')],str(source/'CompromisedKeys.java'),str(source/'TlsPolicy.java')],check=True)
 result=subprocess.run([str(java/'java'),'-cp',str(out),'io.airtap.preview.TlsPolicyTest',str(out/'certificates.tsv'),str(out/'root.pem')],check=True,capture_output=True,text=True)
 print(result.stdout,end='')
clients={'RelayApi.java','PreviewService.java','FeedbackApi.java','UpdateNotice.java'}
actual={p.name for p in source.glob('*.java') if 'TlsPolicy.newClientBuilder()' in p.read_text()}
assert actual==clients,actual
for p in source.glob('*.java'):
 if p.name!='TlsPolicy.java':assert not re.search(r'new\s+OkHttpClient(?:\.Builder)?\s*\(',p.read_text()),f'unprotected TLS client: {p.name}'
assert 'hostnameVerifier(' not in (source/'TlsPolicy.java').read_text()
assert 'TlsPolicy$DenylistTrustManager' in (root/'app/tls-proguard-rules.pro').read_text()
assert (root/'app/build.gradle').read_text().count("'tls-proguard-rules.pro'")==2
print('All four production clients use the policy; default hostname verifier and reflection keep rule retained.')
if args.output:
 args.output.parent.mkdir(parents=True,exist_ok=True)
 args.output.write_text(json.dumps({'passed':int(re.search(r'TLS host checks passed: (\d+)',result.stdout).group(1)),
  'checks':result.stdout.splitlines(),'production_clients':sorted(clients),
  'source_sha256':{name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in ('TlsPolicy.java','CompromisedKeys.java')},
  'scope':'Offline real public certificates and deterministic platform/OkHttp fakes; not native device or deployed client acceptance.'},indent=2)+'\n')
