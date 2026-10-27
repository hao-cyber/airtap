# Independent Android share receiver

This debug-only App is the independent recipient used for the [2026-10-27 emulator evidence](../../../../docs/results/file-sharing-cleanup-emulator-20261027.json). Its source is the source used for the recorded asynchronous 8 MiB, natural-expiry and concurrent-read checks. It is not part of the Airtap APK and has no INTERNET permission. Use synthetic content only: it stores a received URI and digest events in its own private debug data for local inspection.

Set `ANDROID_HOME` and `JAVA_HOME` to an installed Android SDK and JDK 17+. API 36 and Android build tools must already be installed. Nothing is downloaded by this helper.

```sh
python3 apps/android/tests/shares/build.py
adb -s <isolated-emulator> install -r apps/android/tests/shares/build/receiver.apk
```

The helper generates a disposable signing key under the ignored `build/` directory, signs the fixture and verifies its signature. It does not use the Airtap release key. Reusing the build directory preserves fixture update compatibility; do not install it over a fixture signed elsewhere without accounting for the signing difference. Build-only verification does not prove a receiver task passed.

1. Use a synthetic paired device and the production relay/CLI. Upload a small file with `file-upload`, then obtain its current `file-stat.version`.
2. Request `file-share --root workspace --path <file> --version <version> --mime-type text/plain --target-package io.airtap.fixture.receiver`.
3. Observe the receiver's `received` event, `ok:true`, complete byte count and SHA-256. The returned `chooser_requested` flag alone is not delivery evidence; Android can directly open a sole specified receiver.
4. `TRY WRITE` must fail. A third UID without a URI grant must not open the content URI. A test maintainer can inspect `files/uri` and `files/events.jsonl` using `run-as io.airtap.fixture.receiver`; these URI-bearing files must not enter public evidence.
5. Open Airtap and use the normal local stop control. Returning to the existing receiver activity, `READ HELD DESCRIPTOR` must fail on the previously-open descriptor and `READ NEW DESCRIPTOR` must fail. Inspect Android URI grants to confirm actual revocation. Data the receiver already copied cannot be recalled.
6. In a separate still-authorized share, wait over five real minutes without altering time, TTL, pairing or routing. Repeat both read checks and inspect OS grants. Do not count a process crash or intervening local stop as a natural-expiry check.
7. For bounded performance/competition tests, use an 8 MiB synthetic file and independently known SHA-256. The original run seeded it locally, so it is explicitly not an 8 MiB upload result. During the receiver's background initial read, run ordinary file reads and retain any `file_busy` outcomes as well as successes. The recipient must still read the complete expected content.

Buttons are intended for small post-stop/expiry denial checks; initial full-file reception runs on a background thread. This is a diagnostic fixture rather than a general file viewer. Do not share real private files into it, expose its stored URI, or use maintenance `run-as` actions as personal-AI task evidence. Record device/API, exact source, maintainer assistance, failures and recipient verification separately from relay acceptance.
