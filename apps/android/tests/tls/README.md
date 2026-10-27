# Android TLS regression fixtures

These fixtures cover Airtap’s additional application-local rejection of the eight eMudhra intermediate public keys disclosed in October 2026. They are public certificates, never private keys or Airtap credentials.

- `impacted-intermediates.json` and `roots.json` were copied unchanged from the [incident site](https://emudhra.we-have-a-problem.com/) on 2026-10-27. The source site is Apache-2.0 licensed. The offline runner checks the exact downloaded SHA-256, every certificate DER fingerprint and every SPKI fingerprint before exercising the production policy. Six TLS keys and two code-signing keys, including all sixteen certificate representations, must match the production denylist exactly.
- `host/` substitutes only Android’s verified-path provider and OkHttp’s builder surface so the actual production policy can run on a JDK. It checks that an affected intermediate present only in the platform-verified path is rejected, not merely the peer’s submitted chain. This seam is not evidence that a particular Android trust store accepts a public chain.
- `emulator/` runs the production `TlsPolicy` inside the minified debug APK. It makes public, credential-free HTTPS and WebSocket requests, including fourteen official affected-chain endpoints, the two No CT endpoints, valid sites, a wrong hostname, an expired certificate, and an untrusted self-signed certificate. Network failure alone never counts as certificate rejection. It neither reads pairing preferences nor grants accessibility or any device-control authorization. Use only a dedicated disposable emulator.

Run the offline checks:

```sh
python3 apps/android/scripts/test_tls_policy.py
```

After building the Android debug APK with the repository’s configured SDK/JDK, run:

```sh
python3 apps/android/scripts/test_tls_emulator.py \
  --serial emulator-5594 --isolated-emulator \
  --apk apps/android/app/build/outputs/apk/debug/app-debug.apk \
  --output-dir /absolute/private/new-output-directory
```

The emulator probe needs internet access and matching debug signing certificates. It preserves failed probe evidence; reruns must use a new output directory. Public endpoint availability may change, and later Android trust-store updates may already reject the test chains. The report distinguishes the default platform result from Airtap’s additive rejection; it does not infer interception or certify other Android versions or agent runtimes.

Production policy retains Android’s default trust store, the socket/engine-aware system trust check, and OkHttp’s default hostname verifier. It then requests the actual verified chain through the host-aware Android extension and denies the affected SPKIs. Hostless/incomplete validation fails closed. A second host-aware check can be stricter on future platforms configured to require Certificate Transparency where SCT information is available only in the original TLS/OCSP context; it does not disable or bypass that platform requirement. No broad root removal, custom trust anchor, trust-all fallback, or system trust-store modification is used.

If the platform verifier cannot be initialized, the app receives a client with zero connection specs and an unconditional request interceptor failure. HTTPS, HTTP and WebSocket are rejected before DNS or socket I/O; the UI remains available. Native tests exercise this failure against the reserved `.invalid` domain. The offline native fixture also parses all sixteen real certificates and checks the Android host-aware extension with a synthetic verified-path provider, explicitly separate from the live system trust result.
