# vivo capture correction, 0.27

## Evidence and scope

The latest 0.26 phone session receives wireless configuration, joins ordinary WLAN,
completes pair-verify and RTSP SETUP, and negotiates 1344 × 474 at 30 fps. No video
frames are produced. The first host transactions return `Not yet implemented`.
The inspected host checks the calling package with `ComponentVerifier` before
dispatching AIDL, and uses that exception for a rejected component version/signature
check. Phone diagnostics do not identify the exact local/online verifier branch.

This correction preserves that authorization check. An explicitly selected Android
MediaProjection session is a separate source of user-authorized screen content; it
does not grant access to the native host API.

## Behavior mapping before implementation

| Original behavior | Equivalent behavior or deliberate correction |
| --- | --- |
| Component package, exported CarLife entry and real host launch fields | Retained; default connection still uses the real host or waits for its launch. |
| Service binding, package signatures, permissions and callback UID checks | Retained. A read-only Binder interface query distinguishes successful binding from a usable authorized interface. Empty/wrong descriptors cannot establish success. |
| CastConfig presence, width, height, density and Surface parcel; transactions 1–5 | Retained with the standard AIDL interface token. A verifier rejection never triggers a token-free retry. |
| Cast callback registration failure was swallowed | Mandatory cast callback failure now ends native startup. Optional audio/notifications remain optional for ordinary capability errors, but authorization failures are fatal. |
| Component always installed a native Surface source even with screen consent | An explicit valid consent result selects MediaProjection first. Native source selection remains unchanged without that result. |
| Standalone consent flow, one token and one virtual display | Retained. Component adds a separate consent button. Pending requests own the selected peer/session and consume their result once; cancellation or stale results cannot start another session. Activity recreation preserves only the pending identifiers, never a consumed token. |
| Connected-device foreground service and conditional mediaProjection type | Retained; mediaProjection is enabled only for the explicitly authorized source. |
| Host teardown stopped the transmitter unconditionally | Host teardown stops native capture sessions. It cannot stop an independently authorized system capture session. Explicit user Stop still stops either source. |
| Worker completion could race a new start when clearing the foreground notification | Close the owned connection on the worker; finalize its service state and notification on the main thread only if it is still the active connection. Upload stays on a cleanup executor. A duplicate start during old cleanup reports failure instead of silently consuming consent. |
| WLAN handoff, authentication, pairing, negotiation, deadlines and queues | Retained, including ordinary WLAN. No new protocol retry, fallback authentication or extended connection budget. |
| H.264, audio, input, negotiated geometry and resource cleanup | Reuse the existing ProjectionCapture implementations. System audio retains playback-capture restrictions; native audio/input retain actual host callbacks. No artificial frames or success counters. |
| Phone UI, signed uploads and sanitized session journal | Retained; source and host verification phase are added to diagnostics. Successful interface checks do not claim media success. |

## Validation

Regression checks cover capture selection, rejected consent, single result ownership,
stale sessions, native teardown versus system capture, host rejection propagation,
optional capabilities and no retry after authorization failure. Existing sender,
iAP2 and diagnostic tests must remain green. Build both app flavors and run Lint
for changed modules. Hardware evidence is still required for actual frames/audio.

Completed checks (2026-10-12): 253 distinct JVM cases pass (app 36 cases in each
flavor, core-airplay 57, core-iap2 160; 289 executions in total), including 23 new
capture/host cases. Both APK flavors build and changed-module Lint has zero errors
(component 50 warnings, standalone 49, adapter-vivo 5). The final incremental build
and app checks complete in 22 seconds. APK v2 signature verification passes.

The initial combined check encountered a JDK 25 native allocation failure on a
compiler thread during Lint. A process-only `-XX:TieredStopAtLevel=1` rerun succeeds
with the original 2048 MB heap and 24 build workers; Gradle settings are unchanged.
Only a Huawei device is currently attached, so no vivo 0.27 hardware run is claimed.

Source checkpoints before this repair: CarPlay `1b5e01c`; related mobile and log
backend work in iccoa-link `9c675b07f`. Raw APK/DEX files, private captures, credentials
and unrelated iccoa-link changes are outside these source commits.

MediaProjection requires a fresh system consent for each session and callback
registration before creating a virtual display, as documented in the Android
[media projection guide](https://developer.android.com/media/grow/media-projection).
