#!/usr/bin/env python3
"""Production CompanionUi with native Android views on an explicitly isolated emulator; never a physical phone."""
import argparse, hashlib, json, os, re, subprocess, time, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial',required=True);p.add_argument('--isolated-emulator',action='store_true',required=True)
    p.add_argument('--apk',type=Path,required=True,help='Production-source debug APK signed with the selected debug key')
    p.add_argument('--output-dir',type=Path,required=True)
    p.add_argument('--sdk',type=Path,default=os.environ.get('ANDROID_HOME'));p.add_argument('--jdk',type=Path,default=os.environ.get('JAVA_HOME'))
    p.add_argument('--build-tools',default='36.1.0');p.add_argument('--platform',default='android-36')
    p.add_argument('--debug-keystore',type=Path,default=Path.home()/'.android/debug.keystore')
    p.add_argument('--metadata-only',action='store_true',help='Only run the native range-only pagination regression')
    args=p.parse_args()
    if not re.fullmatch(r'emulator-\d+',args.serial):p.error('Only an explicitly isolated emulator serial is accepted')
    if not args.sdk or not args.jdk:p.error('Set ANDROID_HOME and JAVA_HOME')
    out=args.output_dir.resolve();out.mkdir(parents=True,exist_ok=False,mode=0o700)
    fixture=ROOT/'tests/observationpages/emulator';android=args.sdk/'platforms'/args.platform/'android.jar';bt=args.sdk/'build-tools'/args.build_tools
    env={**os.environ,'JAVA_HOME':str(args.jdk)};adb=[str(args.sdk/'platform-tools/adb'),'-s',args.serial]
    def run(*argv,timeout=60):
        value=subprocess.run(list(map(str,argv)),env=env,text=True,capture_output=True,check=True,timeout=timeout);return value.stdout
    def device(*argv):return run(*adb,*argv)
    if device('shell','getprop','ro.kernel.qemu').strip()!='1':raise RuntimeError('Target is not an emulator')
    for name in ('generated','classes','dex'):(out/name).mkdir()
    run(bt/'aapt2','compile','--dir',fixture/'res','-o',out/'res.zip')
    run(bt/'aapt2','link','-I',android,'--manifest',fixture/'AndroidManifest.xml','--java',out/'generated','-o',out/'unsigned.apk',out/'res.zip')
    run(args.jdk/'bin/javac','--release','17','-classpath',android,'-d',out/'classes',*fixture.glob('src/**/*.java'),*out.glob('generated/**/*.java'))
    run(bt/'d8','--min-api','34','--lib',android,'--output',out/'dex',*out.glob('classes/**/*.class'))
    with zipfile.ZipFile(out/'unsigned.apk','a') as z:z.write(out/'dex/classes.dex','classes.dex')
    run(bt/'zipalign','-f','4',out/'unsigned.apk',out/'aligned.apk')
    run(bt/'apksigner','sign','--ks',args.debug_keystore,'--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',out/'fixture.apk',out/'aligned.apk')
    def certificate(apk):
        value=run(bt/'apksigner','verify','--print-certs',apk);return re.search(r'Signer #1 certificate SHA-256 digest: ([a-f0-9]+)',value).group(1)
    if certificate(args.apk)!=certificate(out/'fixture.apk'):raise RuntimeError('Target APK must use the matching debug signature; release APK is not suitable')
    original={name:device('shell','settings','get',scope,name).strip() for scope,name in [('secure','enabled_accessibility_services'),('system','screen_off_timeout')]}
    original['accessibility_enabled']=device('shell','settings','get','secure','accessibility_enabled').strip()
    process=None
    try:
        device('install','--no-incremental','-r',args.apk);device('install','--no-incremental','-r',out/'fixture.apk')
        device('shell','input','keyevent','KEYCODE_WAKEUP');device('shell','wm','dismiss-keyguard')
        device('shell','settings','put','system','screen_off_timeout','1800000')
        with (out/'instrumentation.txt').open('w') as output:
            instrument=['shell','am','instrument','-w']+(['-e','metadata_only','true'] if args.metadata_only else [])+['io.airtap.fixture.pages/test.pages.PageRunner']
            process=subprocess.Popen(adb+instrument,stdout=output,stderr=subprocess.STDOUT,env=env)
            # Instrumentation restarts the target process. Rebind only this disposable emulator's service.
            time.sleep(1);device('shell','settings','put','secure','enabled_accessibility_services','null');time.sleep(.5)
            device('shell','settings','put','secure','enabled_accessibility_services','io.airtap.preview/io.airtap.preview.PreviewService')
            device('shell','settings','put','secure','accessibility_enabled','1');process.wait(timeout=90)
        raw=(out/'instrumentation.txt').read_text();line=next((x.split('report=',1)[1] for x in raw.splitlines() if x.startswith('INSTRUMENTATION_RESULT: report=')),None)
        report=json.loads(line or '{}')
        if report.get('complete') is not True or not all(c['passed'] for c in report['checks']) or 'INSTRUMENTATION_CODE: -1' not in raw:raise RuntimeError('Native fixture failed; inspect retained instrumentation.txt')
        report.update({'evidence':'Unchanged production CompanionUi/WindowInventory/ObservedText/PagedTree in the installed debug APK with native Android accessibility nodes. Private instrumentation injects test BooleanSuppliers for authorization and transport; it does not claim pairing/relay/personal-AI acceptance.',
                       'apk_sha256':hashlib.sha256(args.apk.read_bytes()).hexdigest(),'physical_phone':False,'personal_ai':False})
        (out/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'passed':len(report['checks']),'output':str(out/'result.json')}))
    finally:
        if process and process.poll() is None:process.terminate();process.wait(timeout=5)
        for scope,name in [('secure','enabled_accessibility_services'),('secure','accessibility_enabled'),('system','screen_off_timeout')]:
            value=original[name];device('shell','settings','delete',scope,name) if value=='null' else device('shell','settings','put',scope,name,value)
        device('shell','am','force-stop','io.airtap.fixture.pages');device('shell','input','keyevent','KEYCODE_HOME')
if __name__=='__main__':main()
