#!/usr/bin/env python3
"""Fault-inject real approval recovery classes with synthetic storage/process/HTTP boundaries."""
import argparse, datetime, hashlib, importlib.util, json, os, subprocess, tempfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
PRODUCTION = ROOT/'app/src/main/java/io/airtap/preview'
SOURCES = [PRODUCTION/(name+'.java') for name in ('TrustedGrant', 'ApprovalRecovery', 'ConnectionFeedback', 'ServiceProtocol')]
TEST = ROOT/'tests/approvalrecovery/ApprovalRecoveryTest.java'
FAKES = ROOT/'tests/approvalrecovery/platform_fakes.py'
p = argparse.ArgumentParser()
p.add_argument('--output', type=Path, default=ROOT/'results/approval-recovery.json')
args = p.parse_args()
spec = importlib.util.spec_from_file_location('approval_fakes', FAKES)
fakes = importlib.util.module_from_spec(spec); spec.loader.exec_module(fakes)
jdk = Path(os.environ.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home'))/'bin'
with tempfile.TemporaryDirectory(prefix='airtap-approval-recovery-') as temp:
    temp = Path(temp); generated=[]
    for name, value in fakes.SOURCES.items():
        file = temp/name; file.parent.mkdir(parents=True, exist_ok=True); file.write_text(value); generated.append(file)
    subprocess.run([str(jdk/'javac'), '--release', '17', '-d', str(temp), *map(str,SOURCES),str(TEST),*map(str,generated)],check=True,timeout=30)
    run = subprocess.run([str(jdk/'java'), '-cp', str(temp),'io.airtap.preview.ApprovalRecoveryTest'],text=True,capture_output=True,timeout=30)
    print(run.stdout,end='')
    if run.returncode: print(run.stderr,end='');raise SystemExit(run.returncode)
    tests = [line[5:] for line in run.stdout.splitlines() if line.startswith('PASS ')]
    assert run.stdout.splitlines()[-1]=='TOTAL '+str(len(tests))
record={'status':'passed','recorded_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'passed':len(tests),'tests':tests,
        'source_sha256':{str(x.relative_to(ROOT)):hashlib.sha256(x.read_bytes()).hexdigest() for x in [*SOURCES,TEST,FAKES,Path(__file__)]},
        'limits':['Production TrustedGrant, ApprovalRecovery, ConnectionFeedback and ServiceProtocol compiled verbatim. Storage, clock, process loss and HTTP callbacks are explicit synthetic fault fixtures.',
                  'This does not validate Android SharedPreferences/AtomicFile/Keystore, Activity or service scheduling, real HTTP, relay state transitions, notifications or device recovery. Those require Android integration tests.']}
args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
