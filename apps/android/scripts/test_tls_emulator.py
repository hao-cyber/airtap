#!/usr/bin/env python3
"""Production Android TLS on an explicitly isolated emulator, no pairing/account/device credentials."""
import argparse,hashlib,json,os,re,subprocess,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--serial',required=True);p.add_argument('--isolated-emulator',action='store_true',required=True)
 p.add_argument('--offline-only',action='store_true');p.add_argument('--apk',type=Path,required=True);p.add_argument('--output-dir',type=Path,required=True);p.add_argument('--sdk',type=Path,default=os.environ.get('ANDROID_HOME'));p.add_argument('--jdk',type=Path,default=os.environ.get('JAVA_HOME'));p.add_argument('--debug-keystore',type=Path,default=Path.home()/'.android/debug.keystore');p.add_argument('--dependency-cache',type=Path,default=Path.home()/'.gradle/caches/modules-2/files-2.1');args=p.parse_args()
 if not re.fullmatch(r'emulator-\d+',args.serial):p.error('Only an explicitly isolated emulator is accepted')
 if not args.sdk or not args.jdk:p.error('Set JAVA_HOME and ANDROID_HOME')
 out=args.output_dir.resolve();out.mkdir(parents=True,exist_ok=False,mode=0o700);fixture=ROOT/'tests/tls/emulator';bt=args.sdk/'build-tools/36.1.0';android=args.sdk/'platforms/android-36/android.jar';env={**os.environ,'JAVA_HOME':str(args.jdk)};adb=[str(args.sdk/'platform-tools/adb'),'-s',args.serial]
 def run(*argv,timeout=60):
  value=subprocess.run(list(map(str,argv)),env=env,text=True,capture_output=True,timeout=timeout)
  if value.returncode:raise RuntimeError(value.stderr or value.stdout)
  return value.stdout
 def device(*argv):return run(*adb,*argv)
 if device('shell','getprop','ro.kernel.qemu').strip()!='1':raise RuntimeError('Not an emulator')
 deps=[]
 for pattern in ['com.squareup.okhttp3/okhttp/4.12.0/*/*.jar','com.squareup.okio/okio-jvm/3.6.0/*/*.jar','org.jetbrains.kotlin/kotlin-stdlib/1.9.10/*/*.jar','org.jetbrains/annotations/13.0/*/*.jar']:
  matches=list(args.dependency_cache.glob(pattern))
  if len(matches)!=1:raise RuntimeError('Build the Android app to populate the pinned compile dependency: '+pattern)
  deps.extend(matches)
 for name in ['classes','dex','assets']:(out/name).mkdir()
 for name in ['impacted-intermediates.json','roots.json']:(out/'assets'/name).write_bytes((ROOT/'tests/tls'/name).read_bytes())
 run(bt/'aapt2','link','-I',android,'--manifest',fixture/'AndroidManifest.xml','-A',out/'assets','-o',out/'unsigned.apk')
 run(args.jdk/'bin/javac','--release','17','-classpath',os.pathsep.join(map(str,[android,*deps])),'-d',out/'classes',*fixture.glob('src/**/*.java'))
 run(bt/'d8','--min-api','34','--lib',android,*[v for dep in deps for v in ['--classpath',dep]],'--output',out/'dex',*out.glob('classes/**/*.class'))
 with zipfile.ZipFile(out/'unsigned.apk','a') as z:z.write(out/'dex/classes.dex','classes.dex')
 run(bt/'zipalign','-f','4',out/'unsigned.apk',out/'aligned.apk');run(bt/'apksigner','sign','--ks',args.debug_keystore,'--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',out/'fixture.apk',out/'aligned.apk')
 def certificate(apk):return re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]+)',run(bt/'apksigner','verify','--print-certs',apk)).group(1)
 if certificate(args.apk)!=certificate(out/'fixture.apk'):raise RuntimeError('Only matching debug APK is allowed')
 device('install','--no-incremental','-r',args.apk);device('install','--no-incremental','-r',out/'fixture.apk')
 try:
  raw=run(*adb,'shell','am','instrument','-w',*(['-e','offline_only','true'] if args.offline_only else []),'io.airtap.fixture.tls/test.tls.TlsRunner',timeout=600);(out/'instrumentation.txt').write_text(raw)
  line=next((x.split('report=',1)[1] for x in raw.splitlines() if x.startswith('INSTRUMENTATION_RESULT: report=')),None);report=json.loads(line or '{}')
  report.update({'apk_sha256':hashlib.sha256(args.apk.read_bytes()).hexdigest(),'physical_phone':False,'personal_ai':False,'system_trust_modified':False,'evidence':'No-credential requests from actual production TlsPolicy/OkHttp in debug APK. Only this isolated emulator app and test package were installed; no accessibility or pairing grants.'});(out/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
  if report.get('complete') is not True or not all(c['passed'] for c in report['checks']) or 'INSTRUMENTATION_CODE: -1' not in raw:raise RuntimeError('Native TLS probe failed; inspect retained instrumentation.txt/result.json')
  print(json.dumps({'passed':len(report['checks']),'output':str(out/'result.json')}))
 finally:device('shell','am','force-stop','io.airtap.fixture.tls');device('shell','am','force-stop','io.airtap.preview')
if __name__=='__main__':main()
