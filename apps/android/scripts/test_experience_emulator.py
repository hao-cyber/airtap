#!/usr/bin/env python3
"""Check real presentation components in an offline, disposable emulator-only package."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "io.airtap.experiencefixture"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--jdk", type=Path, default=os.environ.get("JAVA_HOME"))
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial) or not args.sdk or not args.jdk:
        parser.error("Provide an emulator serial, ANDROID_HOME and JAVA_HOME")
    os.umask(0o077)
    out = args.output_dir.resolve()
    out.mkdir(parents=True, exist_ok=False)
    adb = [str(args.sdk / "platform-tools/adb"), "-s", args.serial]

    def run(*cmd, timeout=60):
        result = subprocess.run(list(map(str, cmd)), capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout

    def device(*cmd):
        return run(*adb, *cmd)

    if device("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("Not an emulator")
    if subprocess.run(adb + ["shell", "pm", "path", PACKAGE], capture_output=True, text=True).stdout.strip():
        raise RuntimeError("Existing fixture found; refusing to overwrite its data")
    production = ROOT / "app/src/main/java/io/airtap/preview"
    fixture = ROOT / "tests/experience"
    sources = [production / (n + ".java") for n in ("AppUi", "FirstTask", "OwnerStatus", "RecoveryStatus")]
    sources += list((fixture / "src").rglob("*.java"))
    bt = args.sdk / "build-tools/36.1.0"
    android = args.sdk / "platforms/android-36/android.jar"
    for name in ("generated", "classes", "dex"):
        (out / name).mkdir()
    run(bt / "aapt2", "link", "-I", android, "--manifest", fixture / "AndroidManifest.xml", "--java", out / "generated", "-o", out / "unsigned.apk")
    run(args.jdk / "bin/javac", "--release", "17", "-classpath", android, "-d", out / "classes", *sources)
    run(bt / "d8", "--min-api", "34", "--lib", android, "--output", out / "dex", *out.glob("classes/**/*.class"))
    with zipfile.ZipFile(out / "unsigned.apk", "a") as archive:
        archive.write(out / "dex/classes.dex", "classes.dex")
    run(bt / "zipalign", "-f", "4", out / "unsigned.apk", out / "aligned.apk")
    run(bt / "apksigner", "sign", "--ks", Path.home() / ".android/debug.keystore", "--ks-key-alias", "androiddebugkey",
        "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", out / "fixture.apk", out / "aligned.apk")
    report = {"scope": "Unmodified production presentation components in offline emulator fixture; not full MainActivity, real pairing, phone recovery or personal-AI acceptance.",
              "sources": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}}
    installed = False
    try:
        device("install", "--no-incremental", out / "fixture.apk"); installed = True
        device("shell", "am", "instrument", "-w", PACKAGE + "/io.airtap.preview.ExperienceRunner")
        result = json.loads(device("shell", "run-as", PACKAGE, "cat", "files/result.json"))
        report.update(result)
        if result["status"] != "passed":
            raise RuntimeError("Native fixture failed: " + result.get("error", "unknown"))
        for state in ("CONNECTED", "NETWORK_RECONNECTING", "SYSTEM_AUTH_REQUIRED"):
            device("shell", "am", "start", "-S", "-W", "-n", PACKAGE + "/io.airtap.preview.ExperienceActivity", "--es", "state", state)
            time.sleep(.3)
            if not re.search(r"(?m)^\s*topResumedActivity=ActivityRecord\{[^\n]* " + re.escape(PACKAGE) + r"/", device("shell", "dumpsys", "activity", "activities")):
                raise RuntimeError("Fixture is not in foreground; refusing unrelated screenshot")
            image = subprocess.run(adb + ["exec-out", "screencap", "-p"], check=True, capture_output=True).stdout
            (out / (state.lower() + ".png")).write_bytes(image)
    except Exception as failure:
        report["status"] = "failed"
        report["error"] = str(failure)
        raise
    finally:
        if installed:
            device("uninstall", PACKAGE)
            report["fixture_removed"] = True
        (out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"status": report.get("status"), "checks": len(report.get("checks", [])), "output": str(out / "report.json")}))


if __name__ == "__main__":
    main()
