#!/usr/bin/env python3
"""Check production read-only battery observations against synthetic Android API boundaries."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--output", type=Path)
args = parser.parse_args()
spec = importlib.util.spec_from_file_location("battery_fakes", root / "tests/battery/platform_fakes.py")
fakes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fakes)
jdk = Path(os.environ.get("JAVA_HOME", "/Applications/Android Studio.app/Contents/jbr/Contents/Home")) / "bin"

with tempfile.TemporaryDirectory(prefix="airtap-battery-state-") as directory:
    directory = Path(directory)
    sources = []
    for name, source in fakes.SOURCES.items():
        path = directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source)
        sources.append(path)
    sources += [root / "app/src/main/java/io/airtap/preview/BatteryState.java", root / "tests/battery/BatteryStateTest.java"]
    subprocess.run([str(jdk / "javac"), "--release", "17", "-d", str(directory), *map(str, sources)], check=True, timeout=30)
    run = subprocess.run([str(jdk / "java"), "-cp", str(directory), "io.airtap.preview.BatteryStateTest"],
                         capture_output=True, text=True, timeout=30)
    print(run.stdout, end="")
    if run.returncode:
        print(run.stderr, end="")
        raise SystemExit(run.returncode)
    tests = [line[5:] for line in run.stdout.splitlines() if line.startswith("PASS ")]
    assert run.stdout.splitlines()[-1] == "TOTAL " + str(len(tests))

if args.output:
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({
        "passed": len(tests),
        "tests": tests,
        "limitations": [
            "Production helper compiled unchanged; sticky battery intent, BatteryManager and JSON are synthetic API fixtures.",
            "No real battery sensor, OEM charging policy, device dispatch or personal-AI result is validated here.",
            "The helper reads state only; it does not stop/resume charging or set a charge limit."
        ]
    }, ensure_ascii=False, indent=2) + "\n")
