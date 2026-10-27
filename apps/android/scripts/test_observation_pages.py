#!/usr/bin/env python3
"""Exercise production bounded snapshots and UTF-16 chunks with explicit synthetic native identities."""
import argparse, importlib.util, json, os, subprocess, tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--output',type=Path);args=p.parse_args()
spec=importlib.util.spec_from_file_location('fakes',root/'tests/atomicinput/platform_fakes.py');f=importlib.util.module_from_spec(spec);spec.loader.exec_module(f)
sources={k:v for k,v in f.SOURCES.items() if k in ('org/json/JSONObject.java','org/json/JSONArray.java','io/airtap/preview/ScopedUi.java')}
sources['org/json/JSONObject.java']=sources['org/json/JSONObject.java'].replace('public final Map', 'public String toString(){return fields.toString();}public final Map')
sources['org/json/JSONObject.java']=sources['org/json/JSONObject.java'].replace('public String toString()', 'public java.util.Iterator<String> keys(){return fields.keySet().iterator();}public String toString()')
sources['org/json/JSONArray.java']=sources['org/json/JSONArray.java'].replace('public int length()', 'public Object get(int i){return values.get(i);}public int length()')
jdk=Path(os.environ.get('JAVA_HOME','/Applications/Android Studio.app/Contents/jbr/Contents/Home'))/'bin'
with tempfile.TemporaryDirectory(prefix='airtap-observation-pages-') as directory:
    temp=Path(directory);files=[]
    for name,value in sources.items():
        path=temp/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(value);files.append(path)
    files += [root/'app/src/main/java/io/airtap/preview'/f'{x}.java' for x in ('PagedTree','TreeReadBudget','ObservedText')]
    files += [root/'tests/observationpages/ObservationPagesTest.java']
    subprocess.run([str(jdk/'javac'),'--release','17','-d',str(temp),*map(str,files)],check=True,timeout=30)
    run=subprocess.run([str(jdk/'java'),'-cp',str(temp),'io.airtap.preview.ObservationPagesTest'],capture_output=True,text=True,timeout=30)
    print(run.stdout,end='')
    if run.returncode:print(run.stderr,end='');raise SystemExit(run.returncode)
    tests=[line[5:] for line in run.stdout.splitlines() if line.startswith('PASS ')];assert run.stdout.splitlines()[-1]=='TOTAL '+str(len(tests))
if args.output:
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps({'passed':len(tests),'tests':tests,'limitations':['Production PagedTree/ObservedText/TreeReadBudget compiled unchanged; native nodes and JSON are explicit synthetic fixtures.','Does not replace actual Android CompanionUi/window/permission or personal-AI acceptance.']},indent=2)+'\n')
