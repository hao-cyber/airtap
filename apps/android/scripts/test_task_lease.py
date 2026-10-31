#!/usr/bin/env python3
"""Exercise the production phone task fence with monotonic time and delayed native completion."""
import argparse, json, datetime
import os
import subprocess
import tempfile
from pathlib import Path

parser=argparse.ArgumentParser();parser.add_argument("--output",type=Path);args=parser.parse_args()
root = Path(__file__).resolve().parents[1]
jdk = Path(os.environ.get("JAVA_HOME", "/Applications/Android Studio.app/Contents/jbr/Contents/Home")) / "bin"
with tempfile.TemporaryDirectory(prefix="airtap-task-lease-") as temp:
    subprocess.run([str(jdk / "javac"), "--release", "17", "-d", temp,
                    str(root / "app/src/main/java/io/airtap/preview/TaskLease.java"),
                    str(root / "tests/TaskLeaseTest.java")], check=True)
    run=subprocess.run([str(jdk / "java"), "-cp", temp, "io.airtap.preview.TaskLeaseTest"], check=True,capture_output=True,text=True)
    print(run.stdout,end="")
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps({"status":"passed","recorded_at":datetime.datetime.now(datetime.timezone.utc).isoformat(),"output":run.stdout,"scope":"Production TaskLease with deterministic monotonic clock and synthetic effects; not Android lifecycle or a physical gesture/provider acceptance."},ensure_ascii=False,indent=2)+"\n")
