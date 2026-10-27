# Isolated native observation fixture

This fixture installs a separate no-network App with 300 overlapping native TextViews, one long EditText, one password editor and a native range metadata view. A test-only Instrumentation targets the debug Airtap process and constructs the unchanged production `CompanionUi` with explicit BooleanSupplier fixtures for authorization and transport. It reads actual Android nodes and changes a text suffix in the same native window. This checks the observation implementation; it does not test enrollment, personal AI, relay connectivity or a release signature.

Use only a disposable emulator, never a physical device or an emulator with needed state. The runner verifies the emulator serial and property, requires an explicit `--isolated-emulator` flag, temporarily rebinds its accessibility service after the instrumentation process restart, and restores the previous settings. APKs, generated classes and raw reports stay outside the checkout. No runtime test endpoint or instrumentation is added to the production manifest/APK.

```sh
# First build :app:assembleDebug using the repository Gradle wrapper.
python3 apps/android/scripts/test_observation_pages_emulator.py \
  --serial emulator-5592 --isolated-emulator \
  --apk apps/android/app/build/outputs/apk/debug/app-debug.apk \
  --output-dir /absolute/new/private/result-directory
```

Set `ANDROID_HOME` and `JAVA_HOME`; the default SDK platform is android-36 and build tools36.1.0. The fixture uses the ordinary local Android debug key and requires the target APK to have the same certificate. It does not accept the Airtap release certificate or install on a production device. These tests are opt-in, separate from `tools/check.py --suite core`.

For the narrow page-metadata regression, add `--metadata-only`. This uses 150 native text nodes and changes only the range view's `RangeInfo.current` from 10 to 11. It verifies normal continuation first, rejects the old cached page after the change, obtains 11 from a fresh snapshot, and compares every other exported node field to prove the fixture did not also alter text, bounds or ordinary action identity. It skips the broader previously covered text/expiry cases. The six actual checks and explicit test-authorization boundary are in [the result](../../../../../docs/results/paged-metadata-native-20261027.json).
