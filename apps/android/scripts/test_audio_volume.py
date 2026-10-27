#!/usr/bin/env python3
"""Validate production explicit volume dispatch and native readback using synthetic widget boundaries."""
import argparse, importlib.util, json, os, subprocess, tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path);args=parser.parse_args()
spec=importlib.util.spec_from_file_location('audio_fakes',root/'tests/volume/platform_fakes.py');fakes=importlib.util.module_from_spec(spec);spec.loader.exec_module(fakes)
jdk=Path(os.environ.get('JAVA_HOME','/Applications/Android Studio.app/Contents/jbr/Contents/Home'))/'bin'
with tempfile.TemporaryDirectory(prefix='airtap-audio-volume-') as temp:
    temp=Path(temp);files=[]
    for name,value in fakes.SOURCES.items():
        path=temp/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(value);files.append(path)
    files += [root/'app/src/main/java/io/airtap/preview/AudioVolume.java',root/'tests/volume/AudioVolumeTest.java']
    subprocess.run([str(jdk/'javac'),'--release','17','-d',str(temp),*map(str,files)],check=True,timeout=30)
    run=subprocess.run([str(jdk/'java'),'-cp',str(temp),'io.airtap.preview.AudioVolumeTest'],capture_output=True,text=True,timeout=30);print(run.stdout,end='')
    if run.returncode:print(run.stderr,end='');raise SystemExit(run.returncode)
    tests=[line[5:] for line in run.stdout.splitlines() if line.startswith('PASS ')];assert run.stdout.splitlines()[-1]=='TOTAL '+str(len(tests))
if args.output:
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps({'passed':len(tests),'tests':tests,'limitations':['Production helper compiled unchanged; AudioManager state/policy and JSON boundaries are explicit synthetic fixtures.','OEM audio output, DND/call/ringer policies, full lock/owner/authorization integration and personal-AI acceptance require device tests.']},ensure_ascii=False,indent=2)+'\n')
