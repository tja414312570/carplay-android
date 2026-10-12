# Wireless control and route correction, 0.28

## Original behavior inventory and intended mapping

The 0.27 session `679574eb` sends real video, then ends exactly 15 seconds after
starting synchronization on a command tunnel inferred from a missing type-130
response. TCP feedback continues after cellular availability. The original receiver
in this repository explicitly accepts an iAP stream by returning type 130 and a
dataPort, and sends return iAP messages over the encrypted event channel. Its audio
advertisement places AAC media in type 102, with 44.1/48 kHz format bits; output
formats describe playback, while input formats describe microphone capture.

| Original field/behavior | Destination or deliberate correction |
| --- | --- |
| Bluetooth identity, certificate, fresh challenge, pinned/CA proof and 4301 endpoint | Unchanged. Existing authenticated Bluetooth state and its still-open owned channel are required for Bluetooth control retention. |
| Retained Bluetooth remains responsible for control lifetime | Lock the original verified identity before network join, check it and authenticated/open state during retained control, and preserve actual receive-thread failures. No stale successful proof can keep a closed or changed channel alive. |
| AirPlay pair-setup/verify, key matching, encrypted control/event sockets | Unchanged for every control mode, including retained Bluetooth. Pairing/authentication rejection never becomes a transport fallback. |
| type-130 SETUP UUID, seed, streamConnectionID | Preserved. Distinguish an accepted stream with a port, an accepted stream without a port, and no accepted stream. Log bounded response shape; malformed accepted fields are fatal. Do not invent port/stream IDs. |
| Every absent type-130 port created a command tunnel and a fresh proof timer | An explicitly accepted no-port type-130 stream retains command mode. No accepted type-130 stream retains the existing live authenticated Bluetooth control instead; it does not start or claim a nonexistent Wi-Fi authentication session. Without a live verified Bluetooth channel startup fails. An accepted Wi-Fi stream still requires independent proof; failure cannot fall back to Bluetooth. |
| Event-channel iAP bytes entered the bounded queue; request replies were ignored | Preserve event routing and queue ownership. Also parse binary iAP data returned by an iAP request's authenticated response. Empty ACKs never count as synchronization/authentication. Validate shape/length and propagate malformed data. |
| An early event could arrive before the tunnel pointer was installed | Hold only bounded copied iAP bytes while SETUP/opening is in flight, then drain in order into the accepted tunnel. Actual peer-initiated iAP commands can establish command transport even when the reply omits a stream; the full independent proof still applies. Close discards the owned pending queue. Typed iAP messages returned by later control responses, including feedback, share the same routing. |
| Dedicated TCP iAP writes and event returns | Preserved: the reference transport is asymmetric, not a guessed second TCP read cipher. Add real byte/packet/queue counters and phone-Wi-Fi link diagnostics, without logging payloads. |
| 15-second synchronization, 30-second proof, 45-second sender wait and provisional command-mode media | Preserved for an accepted Wi-Fi control path. Bluetooth-retained mode uses the already verified owned channel and normal lifetime errors. Preserve command-mode RECORD ordering and fatal proof failure. |
| Per-socket Network.bindSocket; default HTTPS upload | Retained. Pin TCP source addresses to actual Wi-Fi LinkProperties, bind timing/audio UDP to the matching local family, and keep external HTTPS on the default route. Never bind the process globally or introduce P2P. |
| Wi-Fi request callbacks read synchronous properties in onAvailable | Readiness now uses actual onLinkPropertiesChanged data. Track selected Wi-Fi separately from the default/cellular networks. Default changes are diagnostic only; selected Wi-Fi loss remains fatal. Retain request budgets, cancellation and owned callback cleanup. |
| Bonjour via a Wi-Fi-local JmDNS instance; Wi-Fi-local TCP invitation server | API 33+ publication/discovery uses NSD with the explicit selected Network. Retain JmDNS on older supported Android versions. Preserve TXT fields, endpoint/key/address filtering, HTTP/RTSP invitation semantics, discovery deadline, listener cancellation and actual local-address binding. |
| Java invitation listener had no explicit Network binding | Use a public android.system.Os listening descriptor bound by Network.bindSocket before bind/listen. Accepted connections inherit that network and retain the same 1-second accept/5-second read bounds. RtspWire gains stream suppliers with its Socket constructor preserved; framing, encryption, response protocol/CSeq, pending reads, write locking and session headers are unchanged. Close/shutdown owns every descriptor exactly once. |
| Fixed type-100 AAC48 stereo check | Select an advertised media/default playback AAC format from type 100 or 102, preferring 48 kHz then 44.1 kHz. Do not use microphone input bits as playback capabilities. Record actual choice before starting capture. |
| Capture/audio encoders and RTP timestamps fixed at 48 kHz | Carry the selected rate through playback capture, AAC configuration, sample counters and RTP. Native 48 kHz PCM is resampled only when required; matching-rate samples stay bit-identical. Preserve PCM bounds, stereo channels, queues, ownership and error reporting. |
| Codec PTS is quantized to microseconds | Reconstruct the nearest sample timestamp at the negotiated rate, preserving whole-second values and natural RTP wrap; avoid one-sample truncation errors for 1024-sample AAC packets. |
| KEY_FRAME_RATE described negotiated FPS but did not constrain Surface input | On API 29+ also apply KEY_MAX_FPS_TO_ENCODER using the actual negotiated FPS. H.264 framing, PTS, keyframes, queues and measured counters remain unchanged. |
| Native host ComponentVerifier rejection and system consent | Preserve 0.27 behavior and all host signature/permission/UID checks; no host authorization bypass or automatic screen consent. |

## Validation required

Use isolated peer sockets and fresh test identities for accepted dedicated/command
control, command responses, proof rejection, absent-stream Bluetooth retention,
closed Bluetooth rejection, malformed negotiation, cancellation and cleanup.
Exercise AAC type 100/102 and both rates, input/output direction, PCM chunk/state
equivalence, network/source selection and default-versus-Wi-Fi loss. Keep all existing
sender, iAP2, diagnostics and capture regressions green. Build both flavors, run Lint
for changed modules, and verify the component APK signature. Actual GM/vivo stability
with mobile data on/off still requires a new hardware session.

## Completed validation and artifact

- 2026-10-12: 281 distinct JVM cases pass, with zero failures/errors: app 41
  in each flavor, core-airplay 80 and core-iap2 160 (322 executions total).
  This includes 28 new cases for negotiation, proof rejection, retained/closed
  Bluetooth, command replies/feedback, PCM timing, route ownership, RTP wrap,
  and encrypted Socket/stream equivalence.
- Both debug flavors build. Lint has zero errors, with warnings remaining:
  component 50, standalone 49, core-airplay 8, core-iap2 10, adapter-generic 6.
  The public socket timeout API has an explicit Android 10 guard.
- Component: `artifacts/carplay-0.28.apk`, package `com.baidu.carlife.vivo`,
  version `8.8.4-carplay.0.28`, code `100034`, 9,749,382 bytes.
  SHA-256: `02fd21811c7617ea2115546dccba07a97c010711d46cf2dad3e2f215cbf1cdd5`.
  APK v2 verification passes with the existing debug signing certificate.
- JDK/native-allocation and VM-start failures required process-only C1/SerialGC
  parameters and separate test/package passes. The original 2048 MB Gradle heap
  and 24 command-line workers remain; performance settings/wrappers are unchanged.
- Concurrent PhoneBluetoothRecovery and test edits are preserved separately.
  The final build includes their current E2LB whitelist extension; this repair
  does not claim authorship of that change.
- ADB lists only Huawei, so no installation on another device or vivo 0.28
  hardware verification is claimed. Native host authorization remains required;
  use the system-consented component capture path for the next hardware test.

The platform choices use public [Network binding](https://developer.android.com/reference/android/net/Network),
[NSD network scoping](https://developer.android.com/reference/android/net/nsd/NsdServiceInfo#setNetwork(android.net.Network)),
and [Surface encoder FPS](https://developer.android.com/reference/android/media/MediaFormat#KEY_MAX_FPS_TO_ENCODER)
APIs. No private API, global network binding, P2P, or host-verifier bypass is introduced.
