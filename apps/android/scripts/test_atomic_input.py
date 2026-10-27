#!/usr/bin/env python3
"""Compile production editor/system helpers against explicit platform fault fixtures."""
import argparse, importlib.util, json, os, subprocess, tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path);args=parser.parse_args()
spec=importlib.util.spec_from_file_location('atomic_fakes',root/'tests/atomicinput/platform_fakes.py')
fakes=importlib.util.module_from_spec(spec);spec.loader.exec_module(fakes)
jdk=Path(os.environ.get('JAVA_HOME','/Applications/Android Studio.app/Contents/jbr/Contents/Home'))/'bin'
with tempfile.TemporaryDirectory(prefix='airtap-atomic-input-') as tmp:
    temp=Path(tmp); files=[]
    for name,value in fakes.SOURCES.items():
        p=temp/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(value);files.append(p)
    files += [root/'app/src/main/java/io/airtap/preview'/f'{x}.java' for x in ('AtomicInput','GuardedInputMethod','SystemActions','CommandFence','ObservedText','TreeReadBudget')]
    files += [root/'tests/atomicinput/AtomicInputTest.java']
    subprocess.run([str(jdk/'javac'),'--release','17','-d',str(temp),*map(str,files)],check=True,timeout=30)
    run=subprocess.run([str(jdk/'java'),'-cp',str(temp),'io.airtap.preview.AtomicInputTest'],capture_output=True,text=True,timeout=30)
    print(run.stdout,end='')
    if run.returncode:print(run.stderr,end='');raise SystemExit(run.returncode)
    tests=[line[5:] for line in run.stdout.splitlines() if line.startswith('PASS ')]
    assert run.stdout.splitlines()[-1]=='TOTAL '+str(len(tests))
if args.output:
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps({'passed':len(tests),'tests':tests,'limitations':['Production helpers compiled unchanged; Android accessibility, current input connection and nodes are synthetic boundaries.','Actual app behavior, service flag activation, authorization/window integration and remote personal-AI acceptance still require Android device tests.']},ensure_ascii=False,indent=2)+'\n')
