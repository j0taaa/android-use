# Validation record

Date: 22 September 2026. App: Android Use 0.1.0, signed release variant, package `dev.androiduse.app`.

| Check | Result |
| --- | --- |
| JVM core tests | 9 passed |
| Android 15 / API 35, Pixel 6 x86_64 emulator | 7 instrumented release tests passed |
| Android 11 / API 30, Pixel 6 x86_64 emulator | 7 instrumented release tests passed |
| Android release lint | Passed, 0 errors; 15 non-blocking warnings (primarily localization, pinned dependency versions, application-context storage, and version-specific metadata) |
| Release APK signature | Verified; APK Signature Scheme v2, RSA 3072-bit signing key |
| Release debuggable flag | false |
| Test-server / instrumentation classes in app APK | Absent |
| Native UI | Home and settings screens visually inspected in emulator screenshots |

The instrumented tests exercise real Android accessibility operations:

1. Node-based text replacement and tapping; assertion of the saved text; actual screenshot capture; scroll; stale-reference rejection.
2. Long press, physical swipe, app discovery, launching Android Settings, home navigation, and reopening the practice notepad.
3. A complete foreground-service agent task against a scripted OpenAI-compatible HTTP endpoint: open practice, enter text, save, capture an image, finish. Every outgoing request is checked for identical previous messages and stable tools.
4. Cancellation of a delayed inference request, followed by verification that its late action never executes.
5. A scripted Anthropic task that asks a question, waits without sending more requests, receives a reply, and finishes. Automatic caching request fields, prefix preservation, and usage parsing are checked.
6. Pausing while inference is pending: the action is held until resume, then executes in another app.
7. Exclusion of the app's own credential/control UI from observations, screenshots, and coordinate taps.

Core tests cover immutable request prefixes for both provider formats, tool-result image placement, provider-reported cache accounting, argument validation, endpoint validation, authentication headers, error-body redaction, cancellation, and rejection of truncated tool calls.

The test HTTP servers execute inside the emulator's instrumentation APK. They are not included in the application APK and do not create a runtime dependency on a computer or a development server. The signed app uses real HTTPS provider adapters.

**Not verified:** live paid inference, real provider cache-hit rates or dollar savings, reasoning quality on arbitrary tasks, physical ARM devices, OEM-specific background restrictions, and operation on every third-party app. No user API key was supplied. Cache numbers returned by the scripted servers are fixtures used to test accounting, not measured real-provider cache hits.

The API 30 and API 35 AVDs and SDK remain installed under `~/Android/Sdk` and `~/.android/avd`. XML reports and visual captures are retained locally in `artifacts/`. Tests may use ADB to provision the emulator; the app itself contains no ADB integration.

Final APK SHA-256: `1cc4a1783f1e12eea2317d5a701dee89082ba1a3397d191c1eb8d615ebf111c9`.

Public download verification: HTTPS 200, 3,131,280 bytes. Downloaded bytes match the signed APK SHA-256 above.
