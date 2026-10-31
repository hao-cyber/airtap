# Approved-session recovery checks

`SessionService` is a visible carrier for an already persisted, unstopped grant. Its restoring notification does not authorize device control. Null-Intent system reconstruction checks local consent, notification permission, unlocked user storage, configured accessibility and explicit system user-stop history. It waits for Android to bind accessibility and for the existing remote approval check before entering ready state. It never enables accessibility, schedules an alarm, executes an old command or wakes the screen while restoring.

A local grant fence binds identity, claim and approval ID. Both the STOP payload and PendingIntent data URI contain this fence, because Intent extras do not distinguish PendingIntents. A cold STOP atomically compares and invalidates only that authorization. Storage-read failures preserve the encrypted record; notification failures use their own recovery path. If both stop-marker persistence and record deletion fail, durable stop cannot be promised and the error remains visible.

Host checks run with `python3 tools/check.py --suite core --install-deps`. They include complete production TrustedGrant/SessionService/SessionRecoveryGate plus extracted production PreviewService methods and explicitly synthetic platform/HTTP boundaries. The gate tests distinguish a SIGKILL exit from an explicit system user stop; the signal alone does not identify its cause.

The separate native check is opt-in and requires an explicitly authorized disposable emulator:

```sh
python3 apps/android/scripts/test_session_recovery_emulator.py \
  --serial emulator-5582 --isolated-emulator --output-dir "$AIRTAP_NATIVE_OUTPUT"
```

Set `JAVA_HOME` and `ANDROID_HOME`, and set `AIRTAP_NATIVE_OUTPUT` to a new private output directory. The script refuses to overwrite an existing `io.airtap.fixture.recovery`. It builds unchanged production SessionService, TrustedGrant and EnrollmentStore with Android Keystore inside this fresh UID. Its prerequisite gate is synthetic and the control service is absent. The package has no INTERNET or WAKE_LOCK permission; it requests only its own notification runtime permission. It does not alter original Airtap, accessibility, global settings or any real phone.

Native evidence covers actual PendingIntent isolation, a real SIGKILL followed by a system sticky null-Intent reconstruction, the restoring notification, retained consent/replay sequence, absence of ready execution/screen lock, and durable STOP after a subsequent cold process. The fixture is uninstalled and returns HOME. The observed restart delay and five-second post-stop observation are measurements, not deadlines or proof of indefinite OEM behavior. This does not test Android accessibility rebinding, relay approval, a personal AI, phone overnight recovery or boot recovery; no boot receiver was added.
