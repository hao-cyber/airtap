#!/usr/bin/env python3
"""Build a release with external signing inputs; optionally retain certificate lineage."""
import argparse,os,subprocess
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--relay',required=True);p.add_argument('--output',type=Path,required=True);args=p.parse_args()
root=Path(__file__).resolve().parent.parent
for name in ['JAVA_HOME','ANDROID_HOME','AIRTAP_RELEASE_KEYSTORE','AIRTAP_RELEASE_KEY_ALIAS','AIRTAP_RELEASE_STORE_PASSWORD','AIRTAP_RELEASE_KEY_PASSWORD']:
 if not os.environ.get(name):raise SystemExit('Missing external environment input: '+name)
if args.output.exists():raise SystemExit('Use a new output path; published APKs are immutable.')
subprocess.run(['./gradlew',':app:assembleRelease',':app:lintRelease','-PpublicRelayOrigin='+args.relay],cwd=root/'apps/android',check=True)
a=Path(os.environ['ANDROID_HOME'])/'build-tools/36.1.0/apksigner'
command=[str(a),'sign','--ks',os.environ['AIRTAP_RELEASE_KEYSTORE'],'--ks-key-alias',os.environ['AIRTAP_RELEASE_KEY_ALIAS'],'--ks-pass','env:AIRTAP_RELEASE_STORE_PASSWORD','--key-pass','env:AIRTAP_RELEASE_KEY_PASSWORD','--min-sdk-version','34','--v1-signing-enabled','false','--v2-signing-enabled','false','--v4-signing-enabled','false','--debuggable-apk-permitted','false']
if os.environ.get('AIRTAP_RELEASE_LINEAGE'):command += ['--lineage',os.environ['AIRTAP_RELEASE_LINEAGE'],'--rotation-min-sdk-version','28']
args.output.parent.mkdir(parents=True,exist_ok=True)
subprocess.run(command+['--out',str(args.output),str(root/'apps/android/app/build/outputs/apk/release/app-release.apk')],check=True)
subprocess.run([str(a),'verify','--verbose','--print-certs',str(args.output)],check=True)
