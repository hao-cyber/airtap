#!/usr/bin/env python3
"""Native PendingIntent and real SIGKILL/sticky lifecycle in a fresh offline fixture package only."""
import argparse,datetime,hashlib,json,os,re,subprocess,time,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
PACKAGE='io.airtap.fixture.recovery'
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--serial',required=True);p.add_argument('--isolated-emulator',action='store_true',required=True);p.add_argument('--output-dir',type=Path,required=True);p.add_argument('--sdk',type=Path,default=os.environ.get('ANDROID_HOME'));p.add_argument('--jdk',type=Path,default=os.environ.get('JAVA_HOME'));p.add_argument('--build-tools',default='36.1.0');p.add_argument('--platform',default='android-36');args=p.parse_args()
 if not re.fullmatch(r'emulator-\d+',args.serial):p.error('An explicitly authorized emulator is required')
 if not args.sdk or not args.jdk:p.error('Set ANDROID_HOME and JAVA_HOME')
 out=args.output_dir.resolve();out.mkdir(mode=0o700,parents=True,exist_ok=False);fixture=ROOT/'tests/sessionrecovery/emulator';production=ROOT/'app/src/main/java/io/airtap/preview';bt=args.sdk/'build-tools'/args.build_tools;android=args.sdk/'platforms'/args.platform/'android.jar';adb=[str(args.sdk/'platform-tools/adb'),'-s',args.serial];env={**os.environ,'JAVA_HOME':str(args.jdk)}
 def run(*a,timeout=60):
  value=subprocess.run(list(map(str,a)),env=env,text=True,capture_output=True,timeout=timeout)
  if value.returncode:raise RuntimeError(value.stdout+value.stderr)
  return value.stdout
 def device(*a):return run(*adb,*a)
 if device('shell','getprop','ro.kernel.qemu').strip()!='1':raise RuntimeError('Not an emulator')
 if subprocess.run(adb+['shell','pm','path',PACKAGE],capture_output=True,text=True).stdout.strip():raise RuntimeError('Fixture already exists; refusing to overwrite data')
 inputs=[production/(n+'.java') for n in ['SessionService','TrustedGrant','EnrollmentStore','RelayAddress','ServiceProtocol']]
 for n in ('generated','classes','dex'):(out/n).mkdir()
 run(bt/'aapt2','link','-I',android,'--manifest',fixture/'AndroidManifest.xml','--java',out/'generated','-o',out/'unsigned.apk')
 run(args.jdk/'bin/javac','--release','17','-classpath',android,'-d',out/'classes',*inputs,*fixture.glob('src/**/*.java'))
 run(bt/'d8','--min-api','34','--lib',android,'--output',out/'dex',*out.glob('classes/**/*.class'))
 with zipfile.ZipFile(out/'unsigned.apk','a') as z:z.write(out/'dex/classes.dex','classes.dex')
 run(bt/'zipalign','-f','4',out/'unsigned.apk',out/'aligned.apk');run(bt/'apksigner','sign','--ks',Path.home()/'.android/debug.keystore','--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',out/'fixture.apk',out/'aligned.apk')
 report={'started_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'serial':args.serial,'package':PACKAGE,'package_absent_before':True,'source_sha256':{str(x.relative_to(ROOT)):hashlib.sha256(x.read_bytes()).hexdigest() for x in inputs},'checks':[],'scope':'Unmodified production SessionService, TrustedGrant, EnrollmentStore (native Android Keystore), RelayAddress and ServiceProtocol. Real Android PendingIntent, FGS and SIGKILL/sticky restart. Synthetic preconfigured accessibility gate and absent controller; no INTERNET permission or remote AI. No original Airtap, system accessibility or global settings changed.'}
 installed=False
 def pid():
  value=subprocess.run(adb+['shell','pidof',PACKAGE],capture_output=True,text=True);return value.stdout.strip()
 def logs():return device('logcat','-d','-v','threadtime','-t','2000','AirtapRecovery:I','FixtureRecovery:I','*:S')
 def phase(mode):
  device('shell','am','start','-n',PACKAGE+'/io.airtap.preview.MainActivity','--es','mode',mode)
  end=time.monotonic()+15
  while time.monotonic()<end:
   value=subprocess.run(adb+['shell','run-as',PACKAGE,'cat','files/result-'+mode+'.json'],capture_output=True,text=True)
   if value.returncode==0:
    result=json.loads(value.stdout);(out/(mode+'.json')).write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n');assert result['ok'],result;report['checks'].append(result);return result
   time.sleep(.25)
  raise RuntimeError('Fixture phase timeout: '+mode)
 try:
  device('install','--no-incremental',out/'fixture.apk');installed=True;device('shell','pm','grant',PACKAGE,'android.permission.POST_NOTIFICATIONS')
  original=phase('seed');device('shell','input','keyevent','KEYCODE_HOME');old=pid();assert old==str(original['pid'])
  killed_at=time.monotonic();device('shell','run-as',PACKAGE,'kill','-9',old)
  restarted=None
  # This bounded wait reports observation only, never an OEM restart deadline.
  while time.monotonic()-killed_at<90:
   time.sleep(1);candidate=pid()
   if candidate and candidate!=old and re.search(r'\s'+re.escape(candidate)+r'\s+.*event=restoring source=system_restart',logs()):restarted=candidate;break
  (out/'sticky-logcat.txt').write_text(logs());assert restarted,'No null-intent sticky reconstruction observed within 90s'
  report['sticky']={'killed_pid':int(old),'new_pid':int(restarted),'elapsed_seconds':round(time.monotonic()-killed_at,3),'signal':9,'force_stop_used':False}
  restored=phase('inspect-restart');assert str(restored['pid'])==restarted
  phase('old-stop');stopped=phase('current-stop');device('shell','input','keyevent','KEYCODE_HOME');old=pid();device('shell','run-as',PACKAGE,'kill','-9',old)
  time.sleep(5);report['no_restart_in_observed_5_seconds']=not bool(pid());assert report['no_restart_in_observed_5_seconds']
  phase('inspect-stopped');report['status']='passed'
 finally:
  (out/'final-logcat.txt').write_text(logs())
  if installed:
   device('uninstall',PACKAGE);device('shell','input','keyevent','KEYCODE_HOME');report['package_removed']=not bool(subprocess.run(adb+['shell','pm','path',PACKAGE],capture_output=True,text=True).stdout.strip())
  (out/'result.json').write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n')
  for path in out.rglob('*'):
   if path.is_file():path.chmod(0o600)
 print(json.dumps({'status':report.get('status'),'output':str(out/'result.json')}))
if __name__=='__main__':main()
