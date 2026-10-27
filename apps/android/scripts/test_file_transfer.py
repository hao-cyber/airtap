#!/usr/bin/env python3
"""Exercise the production file store on a real temporary filesystem, without Android or a device."""
import argparse,json,os,subprocess,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--output',type=Path);a=p.parse_args()
jdk=Path(os.environ.get('JAVA_HOME','/Applications/Android Studio.app/Contents/jbr/Contents/Home'))/'bin'
with tempfile.TemporaryDirectory(prefix='airtap-file-classes-') as d:
    subprocess.run([str(jdk/'javac'),'--release','17','-d',d,str(root/'app/src/main/java/io/airtap/preview/FileTransferStore.java'),str(root/'app/src/main/java/io/airtap/preview/SafPublicationStore.java'),str(root/'app/src/main/java/io/airtap/preview/SafStagingCleanup.java'),str(root/'app/src/main/java/io/airtap/preview/FileMaintenanceFence.java'),str(root/'app/src/main/java/io/airtap/preview/SharedFileIo.java'),str(root/'app/src/main/java/io/airtap/preview/FileGrantAttempt.java'),str(root/'app/src/main/java/io/airtap/preview/PrivateFileStorage.java'),str(root/'tests/files/FileTransferStoreTest.java'),str(root/'tests/files/SafPublicationStoreTest.java'),str(root/'tests/files/PrivateFileStorageTest.java')],check=True)
    all_tests=[]
    for suite in ['FileTransferStoreTest','SafPublicationStoreTest','PrivateFileStorageTest']:
        r=subprocess.run([str(jdk/'java'),'-Djava.io.tmpdir='+d,'-cp',d,'io.airtap.preview.'+suite],text=True,capture_output=True,timeout=45)
        print(r.stdout,end='')
        if r.returncode: print(r.stderr,end='');raise SystemExit(r.returncode)
        tests=[x[5:] for x in r.stdout.splitlines() if x.startswith('PASS ')];assert r.stdout.splitlines()[-1]=='TOTAL '+str(len(tests))
        all_tests+=tests
    tests=all_tests
if a.output:
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps({'passed':len(tests),'tests':tests,'limitations':['Production pure-Java filesystem helper tested unchanged; host filesystem is not Android storage.','SAF publication state machine uses a synthetic provider writing real local files; actual system grants, background cancellation and Android directory fsync require device/provider acceptance.','Crash windows injected at journal/publication boundaries; not a physical power-loss durability test.']},indent=2)+'\n')
