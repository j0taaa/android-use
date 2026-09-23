# Validation record

Date: 22 September 2026. App: Android Use 0.6.0, signed release variant, package `dev.androiduse.app`.

| Check | Result |
| --- | --- |
| JVM core tests | 15 passed |
| Android 15 / API 35, Pixel 6 x86_64 emulator | 19 release tests passed on the final APK |
| Android 11 / API 30, Pixel 6 x86_64 emulator | 4 targeted release tests passed: drawer revocation/data retention, inference cancellation, automatic disconnection on completion, and automatic disconnection on Stop; notification checks wait for Android’s asynchronous removal |
| Android release lint | Passed, 0 errors; 24 non-blocking warnings (primarily localization, pinned dependency versions, application-context storage, and version-specific metadata) |
| Release APK signature | Verified; APK Signature Scheme v2, RSA 3072-bit signing key |
| Release debuggable flag | false |
| Test-server / instrumentation classes in app APK | Absent |
| Native UI | Phone-control off state and Settings visually inspected in this update; reasoning, attachments, chat, drawer and keyboard screenshots inspected in prior releases |

The instrumented tests exercise real Android accessibility operations:

1. Node-based text replacement and tapping; assertion of the saved text; actual screenshot capture; scroll; stale-reference rejection.
2. Long press, physical swipe, app discovery, launching Android Settings, home navigation, and reopening the practice notepad.
3. A complete foreground-service agent task against a scripted OpenAI-compatible HTTP endpoint: open practice, enter text, save, capture an image, finish. Every outgoing request is checked for identical previous messages and stable tools.
4. Cancellation of a delayed inference request, followed by verification that its late action never executes.
5. A scripted Anthropic task that asks a question, waits without sending more requests, receives a reply with an image and text attachment, and finishes. Encrypted attachment history is checked. Automatic caching request fields, prefix preservation, and usage parsing are checked.
6. Pausing while inference is pending: the action is held until resume, then executes in another app.
7. Exclusion of the app's own credential/control UI from observations, screenshots, and coordinate taps.

8. Reloading an encrypted chat for an explicit follow-up while preserving every prior request message and the conversation ID.
9. Cold launch to a blank chat, swipe-open history, selecting a saved conversation, new chat, draft restoration after rotation, and settings navigation.
10. Sending initial and follow-up messages through the actual UI, checking the keyboard does not cover the composer, displaying both replies, and dismissing the history drawer using system Back.

11. One-time upgrade of the old 100,000-token default to 10,000,000, preserving other custom budgets and later intentional edits.
12. Live assistant text and running/completed action cards in the chat, expandable persisted tool details, per-chat draft restoration, and swipe dismissal of the drawer.

13. Bounded image resizing and thumbnails, UTF-8 and DOCX content extraction, native PDF preparation, encrypted payload/metadata storage, attachment removal, four-file limits, and rejection of unsupported files, invalid PDF headers and oversized text.
14. Real Android Files and Photos picker navigation, file-card removal, image preview after rotation, image-only send through the chat UI, encrypted event restoration, attachment draft retention across chat switches, and a text-file follow-up with an unchanged earlier request prefix.

15. Selecting High in Settings, saving it to preferences, observing the actual outgoing effort/output ceiling, reloading the saved chat, preserving its effort and request prefix after changing Settings to Low, and confirming a new chat uses Low. Older session JSON without a reasoning field defaults correctly.

16. Turning off phone control through the drawer, verifying Android removes the enabled service, retaining encrypted chat/API configuration, rejecting stale service actions/reads/overlays, and confirming reopening does not re-enable access.
17. Turning off during delayed inference, verifying cancellation and preventing the late app-launch action; checking the foreground notification and overlay disappear.
18. Automatic disconnection after successful task completion, preserving the COMPLETE result.
19. Automatic disconnection after Stop interrupts a pending model request, preserving the STOPPED result.

Core tests cover immutable request prefixes for both provider formats, tool-result image placement, provider-reported cache accounting, argument validation, endpoint validation, authentication headers, error-body redaction, cancellation, rejection of truncated tool calls, and resolving interrupted calls without replaying actions or rewriting earlier context (both providers), plus validation of the 100× larger default token budget, OpenAI/Anthropic native attachment blocks unchanged attachment content on follow-ups, omitted default reasoning fields, explicit reasoning controls, bounded legacy Claude thinking, and verbatim retention of signed thinking blocks through tool calls without rendering them as chat text.

The test HTTP servers execute inside the emulator's instrumentation APK. They are not included in the application APK and do not create a runtime dependency on a computer or a development server. The signed app uses real HTTPS provider adapters.

**Not verified in this update’s automated checks:** compatibility with actual banking apps, installed-app risk checks, physical haptic feel, live paid inference, real provider cache-hit rates or dollar savings, reasoning quality on arbitrary tasks, physical ARM devices, OEM-specific background restrictions, and operation on every third-party app. No user API key was supplied. Cache numbers returned by the scripted servers are fixtures used to test accounting, not measured real-provider cache hits.

The API 30 and API 35 AVDs and SDK remain installed under `~/Android/Sdk` and `~/.android/avd`. XML reports and visual captures are retained locally in `artifacts/`. Tests may use ADB to provision the emulator; the app itself contains no ADB integration.

Final APK SHA-256: `5587f6450ff48c2499091c81cc5c1927e93e433978793fafd149b25ea3a30537`.

Signed APK size: 3,208,644 bytes. The release includes a SHA-256 checksum file for download verification.

Upgrade signing: v0.6.0, v0.5.0, v0.4.0, v0.3.0, v0.2.0 and v0.1.0 have the same signing certificate; package identity and Android Keystore alias are unchanged. The release can install over any earlier release. Old history remains readable; replies require a chat created in v0.2.0 or later.

Haptic feedback uses Android’s standard view feedback and respects system settings. The emulator verifies interaction behavior; it cannot establish tactile quality on physical phones. Drawer and conversation animations also respect the system animation setting.

Images and PDFs require provider/model support; this release validates both request formats with scripted endpoints. Live document interpretation and every third-party OpenAI-compatible service are not verified. DOCX support extracts main-document text only. No attachment is uploaded to a separate Android Use backend.

Reasoning validation uses scripted provider responses. Live model acceptance of every effort value, actual reasoning quality and provider cache-hit behavior are not measured. Existing chats retain their recorded effort; new global settings apply to new chats.

Android 11 testing in this update targets the four new disconnection flows. The full regression suite runs on Android 15. Two initial Android 11 assertions checked notification removal too early; they now wait for Android’s asynchronous removal and both reruns passed. The final app APK is unchanged by that test-only adjustment.
