#!/usr/bin/env python3
"""Build unchanged production Java with isolated test packaging; create a new disposable AVD."""
import argparse, hashlib, json, os, shutil, subprocess, zipfile
from pathlib import Path

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[4]
PACKAGE='io.airtap.fixture.multi'
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,required=True);p.add_argument('--avd-name',required=True)
    p.add_argument('--sdk',type=Path,required=True);p.add_argument('--jdk',type=Path,required=True)
    p.add_argument('--rootable-system-image',type=Path,help='Optional installed Google APIs arm64 system image; required for native port443 reverse')
    p.add_argument('--build-only',action='store_true',help='Build new isolated APKs without creating another AVD')
    args=p.parse_args();out=args.output.resolve();out.mkdir(mode=0o700,parents=True,exist_ok=False)
    env={**os.environ,'JAVA_HOME':str(args.jdk),'ANDROID_HOME':str(args.sdk)}
    def run(*a,input=None):return subprocess.run(list(map(str,a)),input=input,text=True,capture_output=True,check=True,env=env).stdout
    tls=out/'tls';tls.mkdir()
    run('openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',tls/'ca-key.pem','-out',tls/'ca.pem','-days','2','-subj','/CN=Airtap isolated native test CA')
    run('openssl','req','-newkey','rsa:2048','-nodes','-keyout',tls/'key.pem','-out',tls/'server.csr','-subj','/CN=localhost')
    (tls/'ext.cnf').write_text('subjectAltName=DNS:localhost\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n')
    run('openssl','x509','-req','-in',tls/'server.csr','-CA',tls/'ca.pem','-CAkey',tls/'ca-key.pem','-CAcreateserial','-out',tls/'cert.pem','-days','2','-extfile',tls/'ext.cnf')
    build=out/'build';build.mkdir()
    android=ROOT/'apps/android'
    for f in ['gradlew','gradlew.bat','settings.gradle','build.gradle','gradle.properties']:
        if (android/f).exists():shutil.copy2(android/f,build/f)
    shutil.copytree(android/'gradle',build/'gradle')
    shutil.copytree(android/'app',build/'app',ignore=shutil.ignore_patterns('build','.gradle'))
    (build/'local.properties').write_text('sdk.dir='+str(args.sdk)+'\n')
    hashes={}
    for src in (android/'app/src/main/java').rglob('*.java'):
        relative=src.relative_to(android);copy=build/relative
        assert src.read_bytes()==copy.read_bytes();hashes[str(relative)]=hashlib.sha256(src.read_bytes()).hexdigest()
    for src in (HERE/'client').rglob('*.java'):
        dst=build/'app/src/main/java'/src.relative_to(HERE/'client');dst.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(src,dst)
    gradle=build/'app/build.gradle';text=gradle.read_text().replace("applicationId 'io.airtap.preview'",f"applicationId '{PACKAGE}'").replace('minifyEnabled true','minifyEnabled false');gradle.write_text(text)
    manifest=build/'app/src/main/AndroidManifest.xml';text=manifest.read_text().replace('android:usesCleartextTraffic="false"','android:usesCleartextTraffic="false" android:networkSecurityConfig="@xml/native_network_security"')
    text=text.replace('</application>','<receiver android:name="io.airtap.preview.NativeBridge" android:exported="true"/><activity android:name="io.airtap.preview.NativeFolderActivity" android:exported="true"/>\n</application>');manifest.write_text(text)
    res=build/'app/src/main/res';(res/'raw').mkdir(exist_ok=True);shutil.copy2(tls/'ca.pem',res/'raw/native_ca.pem')
    (res/'xml/native_network_security.xml').write_text('<network-security-config><base-config cleartextTrafficPermitted="false"><trust-anchors><certificates src="@raw/native_ca"/></trust-anchors></base-config></network-security-config>')
    with (out/'build.log').open('w') as log:
        subprocess.run([str(build/'gradlew'),'--no-daemon',':app:assembleDebug'],cwd=build,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
    shutil.copy2(build/'app/build/outputs/apk/debug/app-debug.apk',out/'client.apk')
    target=out/'target';target.mkdir();(target/'classes').mkdir();(target/'dex').mkdir()
    bt=args.sdk/'build-tools/36.1.0';api=args.sdk/'platforms/android-36/android.jar'
    run(bt/'aapt2','link','-I',api,'--manifest',HERE/'target/AndroidManifest.xml','-o',target/'unsigned.apk')
    run(args.jdk/'bin/javac','--release','17','-classpath',api,'-d',target/'classes',*HERE.glob('target/**/*.java'))
    run(bt/'d8','--min-api','34','--lib',api,'--output',target/'dex',*target.glob('classes/**/*.class'))
    with zipfile.ZipFile(target/'unsigned.apk','a') as z:z.write(target/'dex/classes.dex','classes.dex')
    run(bt/'zipalign','-f','4',target/'unsigned.apk',target/'aligned.apk')
    run(bt/'apksigner','sign','--ks',Path.home()/'.android/debug.keystore','--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',out/'target.apk',target/'aligned.apk')
    if not args.build_only:run(args.sdk/'cmdline-tools/latest/bin/avdmanager','create','avd','-n',args.avd_name,'-p',out/'avd','-k','system-images;android-36.1;google_apis_playstore;arm64-v8a','-d','pixel_5',input='no\n')
    if args.rootable_system_image and not args.build_only:
        image=args.rootable_system_image.resolve()
        if not (image/'system.img').is_file():raise RuntimeError('missing_rootable_image')
        config=out/'avd/config.ini';rows=config.read_text().splitlines();updates={'image.sysdir.1':str(image)+'/', 'tag.id':'google_apis','tag.display':'Google APIs','PlayStore.enabled':'false'}
        config.write_text('\n'.join(k+'='+updates[k] if (k:=line.split('=',1)[0]) in updates else line for line in rows)+'\n')
    report={'production_java_sha256':hashes,'production_java_unchanged':True,'package':PACKAGE,'avd_name':args.avd_name,'build_differences':['test-only applicationId','no shrinking','test-only NativeBridge and NativeFolderActivity','private CA only; production TlsPolicy unchanged'],'client_sha256':hashlib.sha256((out/'client.apk').read_bytes()).hexdigest(),'target_sha256':hashlib.sha256((out/'target.apk').read_bytes()).hexdigest()}
    (out/'build-metadata.private.json').write_text(json.dumps(report,indent=2)+'\n')
    for f in tls.iterdir():f.chmod(0o600)
    print(json.dumps({'output':str(out),'built':True,'production_java_files':len(hashes)}))
if __name__=='__main__':main()
