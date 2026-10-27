#!/usr/bin/env python3
"""Exercise real strict invitation validation and HTTP clock evidence, without adjusting device time."""
import importlib.util,os,subprocess,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('json_fakes',root/'tests/approvalrecovery/platform_fakes.py');f=importlib.util.module_from_spec(spec);spec.loader.exec_module(f)
jdk=Path(os.environ.get('JAVA_HOME','/Applications/Android Studio.app/Contents/jbr/Contents/Home'))/'bin'
with tempfile.TemporaryDirectory(prefix='airtap-invitation-test-') as temp:
    temp=Path(temp);sources=[]
    for name in ('org/json/JSONObject.java','org/json/JSONArray.java'):
        p=temp/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(f.SOURCES[name]);sources.append(p)
    sources += [root/'app/src/main/java/io/airtap/preview'/name for name in ('ServiceProtocol.java','InvitationFeedback.java')]
    sources.append(root/'tests/invitation/InvitationFeedbackTest.java')
    subprocess.run([str(jdk/'javac'),'--release','17','-d',str(temp),*map(str,sources)],check=True)
    subprocess.run([str(jdk/'java'),'-cp',str(temp),'io.airtap.preview.InvitationFeedbackTest'],check=True)
