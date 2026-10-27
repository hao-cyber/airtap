#!/usr/bin/env python3
"""Build an independent, no-network debug receiver. Requires local Android SDK/JDK."""
import os
from pathlib import Path
import subprocess
import zipfile


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def main():
    source = Path(__file__).resolve().parent
    sdk = Path(os.environ["ANDROID_HOME"])
    java = Path(os.environ["JAVA_HOME"]) / "bin"
    android = sdk / "platforms" / "android-36" / "android.jar"
    candidates = [p for p in (sdk / "build-tools").iterdir()
                  if all(x.isdigit() for x in p.name.split("."))]
    build_tools = max(candidates, key=lambda p: tuple(map(int, p.name.split("."))))
    output = source / "build"
    classes, dex = output / "classes", output / "dex"
    classes.mkdir(parents=True, exist_ok=True)
    dex.mkdir(exist_ok=True)
    key = output / "fixture-debug.keystore"
    if not key.exists():
        run(java / "keytool", "-genkeypair", "-keystore", key, "-alias", "fixture",
            "-storepass", "android", "-keypass", "android", "-keyalg", "RSA",
            "-validity", "3650", "-dname", "CN=Airtap local test fixture")
    run(java / "javac", "-source", "17", "-target", "17", "-classpath", android,
        "-d", classes, source / "ReceiverActivity.java")
    run(build_tools / "d8", "--lib", android, "--output", dex,
        *classes.rglob("*.class"))
    unsigned, aligned, apk = (output / n for n in
                              ("unsigned.apk", "aligned.apk", "receiver.apk"))
    run(build_tools / "aapt2", "link", "-o", unsigned,
        "--manifest", source / "AndroidManifest.xml", "-I", android)
    with zipfile.ZipFile(unsigned, "a") as archive:
        archive.write(dex / "classes.dex", "classes.dex")
    run(build_tools / "zipalign", "-f", "4", unsigned, aligned)
    run(build_tools / "apksigner", "sign", "--ks", key, "--ks-key-alias", "fixture",
        "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", apk, aligned)
    run(build_tools / "apksigner", "verify", apk)
    print(apk)


if __name__ == "__main__":
    main()
