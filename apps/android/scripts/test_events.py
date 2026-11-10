#!/usr/bin/env python3
"""Exercise production event retention, cursor gaps and deduplication without Android."""
import argparse,json,os,subprocess,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--output',type=Path);a=p.parse_args()
jdk=Path(os.environ.get('JAVA_HOME','/Applications/Android Studio.app/Contents/jbr/Contents/Home'))/'bin'
with tempfile.TemporaryDirectory(prefix='airtap-event-classes-') as d:
    subprocess.run([str(jdk/'javac'),'--release','17','-d',d,str(root/'app/src/main/java/io/airtap/preview/EventWindow.java'),str(root/'tests/events/EventWindowTest.java')],check=True)
    all_tests=[]
    for suite in ['EventWindowTest']:
        r=subprocess.run([str(jdk/'java'),'-Djava.io.tmpdir='+d,'-cp',d,'io.airtap.preview.'+suite],text=True,capture_output=True,timeout=45)
        print(r.stdout,end='')
        if r.returncode: print(r.stderr,end='');raise SystemExit(r.returncode)
        tests=[x[5:] for x in r.stdout.splitlines() if x.startswith('PASS ')];assert r.stdout.splitlines()[-1]=='TOTAL '+str(len(tests))
        all_tests+=tests
    tests=all_tests
if a.output:
    a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text(json.dumps({'passed':len(tests),'tests':tests,'limitations':['Pure event window checked; Android notification permissions, encrypted persistence and OEM background delivery require separate platform acceptance.']},indent=2)+'\n')
