#!/usr/bin/env python3
"""Exercise production encrypted record size checks with JCA and synthetic Android adapters."""
import argparse, datetime, hashlib, importlib.util, json, os, subprocess, tempfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
PRODUCTION = ROOT/'app/src/main/java/io/airtap/preview'
BASE = ROOT/'tests/approvalrecovery/platform_fakes.py'
spec = importlib.util.spec_from_file_location('storage_fakes', BASE)
base = importlib.util.module_from_spec(spec); spec.loader.exec_module(base)
FAKES = {key:value for key,value in base.SOURCES.items() if key.startswith('org/json/')}
FAKES['org/json/JSONObject.java'] = FAKES['org/json/JSONObject.java'].replace('public JSONObject put(', 'private String raw;public JSONObject(){}public JSONObject(String value){raw=value;}public String toString(){return raw==null?"{}":raw;}public JSONObject put(')
FAKES.update({
'android/content/Context.java': '''package android.content;public final class Context {private final java.io.File files;public Context(){try{files=java.nio.file.Files.createTempDirectory("airtap-record-size-").toFile();}catch(Exception e){throw new IllegalStateException(e);}}public java.io.File getFilesDir(){return files;}}''',
'android/util/AtomicFile.java': '''package android.util;public final class AtomicFile {private final java.io.File file,temp;public AtomicFile(java.io.File f){file=f;temp=new java.io.File(f+".new");}public java.io.File getBaseFile(){return file;}public byte[] readFully()throws java.io.IOException{return java.nio.file.Files.readAllBytes(file.toPath());}public java.io.FileOutputStream startWrite()throws java.io.IOException{return new java.io.FileOutputStream(temp);}public void finishWrite(java.io.FileOutputStream stream)throws java.io.IOException{stream.close();java.nio.file.Files.move(temp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}public void failWrite(java.io.FileOutputStream stream)throws java.io.IOException{stream.close();java.nio.file.Files.deleteIfExists(temp.toPath());}public void delete()throws java.io.IOException{java.nio.file.Files.deleteIfExists(file.toPath());}}''',
'android/security/keystore/KeyProperties.java': '''package android.security.keystore;public final class KeyProperties {public static final String KEY_ALGORITHM_AES="AES",BLOCK_MODE_GCM="GCM",ENCRYPTION_PADDING_NONE="NoPadding";public static final int PURPOSE_ENCRYPT=1,PURPOSE_DECRYPT=2;}''',
'android/security/keystore/KeyGenParameterSpec.java': '''package android.security.keystore;public final class KeyGenParameterSpec implements java.security.spec.AlgorithmParameterSpec {public static final class Builder {public Builder(String alias,int purpose){}public Builder setBlockModes(String mode){return this;}public Builder setEncryptionPaddings(String padding){return this;}public Builder setRandomizedEncryptionRequired(boolean required){return this;}public KeyGenParameterSpec build(){return new KeyGenParameterSpec();}}}''',
'io/airtap/preview/RelayAddress.java': '''package io.airtap.preview;final class RelayAddress {static String requireRoot(String s){return s;}static String deviceBase(String s,String id){return s+"/d/"+id;}static String root(String s){return s;}}''',
})
p=argparse.ArgumentParser();p.add_argument('--output',type=Path);args=p.parse_args()
sources=[PRODUCTION/'EnrollmentStore.java',PRODUCTION/'ServiceProtocol.java',ROOT/'tests/approvalrecovery/EnrollmentStoreBoundaryTest.java']
jdk=Path(os.environ.get('JAVA_HOME','/Applications/Android Studio.app/Contents/jbr/Contents/Home'))/'bin'
with tempfile.TemporaryDirectory(prefix='airtap-record-limit-') as temporary:
    temporary=Path(temporary);generated=[]
    for name,value in FAKES.items():
        file=temporary/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(value);generated.append(file)
    subprocess.run([str(jdk/'javac'),'--release','17','-d',str(temporary),*map(str,sources),*map(str,generated)],check=True,timeout=30)
    run=subprocess.run([str(jdk/'java'),'-Djava.io.tmpdir='+str(temporary),'-cp',str(temporary),'io.airtap.preview.EnrollmentStoreBoundaryTest'],text=True,capture_output=True,timeout=30)
print(run.stdout,end='')
if run.returncode: print(run.stderr,end='');raise SystemExit(run.returncode)
if args.output:
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps({'status':'passed','recorded_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'passed':run.stdout.count('PASS '),'checks':run.stdout.splitlines(),
        'source_sha256':{str(f.relative_to(ROOT)):hashlib.sha256(f.read_bytes()).hexdigest() for f in [*sources,BASE,Path(__file__)]},
        'limits':['Production EnrollmentStore and ServiceProtocol compile verbatim. Real JCA AES-GCM and filesystem IO use a synthetic in-memory AndroidKeyStore provider and minimal AtomicFile adapter.',
                  'JSONObject preserves test JSON bytes; JSON/schema semantics are tested separately. This does not validate Android Keystore, AtomicFile crash durability, device installation or an OEM.']},indent=2)+'\n')
