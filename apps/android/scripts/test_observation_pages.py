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
companion=(root/'app/src/main/java/io/airtap/preview/CompanionUi.java').read_text()
begin=companion.index('    private JSONObject read(String expected, BooleanSupplier commandCurrent) throws Exception {')
read_method=companion[begin:companion.index('\n    private JSONObject readPage(',begin)]
sources['io/airtap/preview/InitialTreeHarness.java']='''package io.airtap.preview;
import java.util.*;import java.util.function.*;import org.json.*;
final class InitialTreeHarness {
 static final long TTL=30000;static final class SystemClock{static long elapsedRealtime(){return 100;}}
 static final class AccessibilityNodeInfo {final int window;AccessibilityNodeInfo(int w){window=w;}int getWindowId(){return window;}}
 long revision,snapshotDeadline;int snapshotWindow,captures;String snapshot,snapshotPackage;boolean changeWindow,invalidate,revoke,failScan,allowed=true;
 Map<String,Object> nodes=new HashMap<>();PagedTree<AccessibilityNodeInfo> pagedTree;
 AccessibilityNodeInfo root(String expected){return new AccessibilityNodeInfo(changeWindow&&captures>0?2:1);}String pkg(AccessibilityNodeInfo n){return "synthetic.app";}
 void clear(){revision++;nodes.clear();snapshot=null;pagedTree=null;}
 PagedTree<AccessibilityNodeInfo> scan(AccessibilityNodeInfo r,String p,int w,long started,long rev,boolean export,BooleanSupplier current){
  if(failScan)throw new ScopedUi.Refused("inspection_incomplete");captures++;final String text="tick-"+captures;
  var tree=PagedTree.capture(r,new PagedTree.Source<AccessibilityNodeInfo>(){
   public PagedTree.Description describe(AccessibilityNodeInfo n){return new PagedTree.Description(text,0,true,new JSONObject().put("node_id","1").put("text",text));}
   public AccessibilityNodeInfo child(AccessibilityNodeInfo n,int index){throw new AssertionError();}},current);
  if(invalidate)revision++;if(revoke)allowed=false;return tree;
 }
 JSONObject pageResult(int offset,int limit,AccessibilityNodeInfo r)throws Exception{return pagedTree.page(offset,limit);}
 JSONObject readNow()throws Exception{return read("synthetic.app",()->allowed);}
'''+read_method+'\n}\n'
jdk=Path(os.environ.get('JAVA_HOME','/Applications/Android Studio.app/Contents/jbr/Contents/Home'))/'bin'
with tempfile.TemporaryDirectory(prefix='airtap-observation-pages-') as directory:
    temp=Path(directory);files=[]
    for name,value in sources.items():
        path=temp/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(value);files.append(path)
    files += [root/'app/src/main/java/io/airtap/preview'/f'{x}.java' for x in ('PagedTree','TreeReadBudget','ObservedText')]
    files += [root/'tests/observationpages/ObservationPagesTest.java']
    files += [root/'tests/observationpages/InitialTreeReadTest.java']
    subprocess.run([str(jdk/'javac'),'--release','17','-d',str(temp),*map(str,files)],check=True,timeout=30)
    run=subprocess.run([str(jdk/'java'),'-cp',str(temp),'io.airtap.preview.ObservationPagesTest'],capture_output=True,text=True,timeout=30)
    print(run.stdout,end='')
    if run.returncode:print(run.stderr,end='');raise SystemExit(run.returncode)
    tests=[line[5:] for line in run.stdout.splitlines() if line.startswith('PASS ')];assert run.stdout.splitlines()[-1]=='TOTAL '+str(len(tests))
    initial=subprocess.run([str(jdk/'java'),'-cp',str(temp),'io.airtap.preview.InitialTreeReadTest'],capture_output=True,text=True,timeout=30)
    print(initial.stdout,end='')
    if initial.returncode:print(initial.stderr,end='');raise SystemExit(initial.returncode)
    initial_tests=[line[5:] for line in initial.stdout.splitlines() if line.startswith('PASS ')];assert initial.stdout.splitlines()[-1]=='TOTAL '+str(len(initial_tests))
    tests+=initial_tests
if args.output:
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps({'passed':len(tests),'tests':tests,'limitations':['Production PagedTree/ObservedText/TreeReadBudget compiled unchanged; CompanionUi.read extracted unchanged with synthetic native capture/window/authorization effects.','Does not replace actual Android window/permission or personal-AI acceptance.']},indent=2)+'\n')
