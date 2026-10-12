# Local CarLife component verification

The component flavor has two persisted modes. CarPlay remains the default. Component verification accepts the same real Jovi launch contract, then renders the host's native display into a phone overlay. It never creates a wireless connection, Bluetooth transport, Wi-Fi request, iAP2/AirPlay session or sender encoder. Opening Jovi is only a normal launcher action; it does not fabricate a host request.

## Behavior mapping before implementation

| Existing behavior | Destination |
| --- | --- |
| Original CarLife service/activity aliases and launch extras | Same entries; dispatch according to the saved mode before checking car selection or Bluetooth permissions |
| Host package signature, service permission, Binder descriptor and callback UID checks | Unchanged `NativeComponentState` and `VivoHostSurface` in both modes |
| Callback registration, prepareCast, display readiness and native audio callbacks | Same native source; verification supplies a real overlay Surface, with an explicit local 1280 × 720 preview size |
| Negotiated CarPlay capture size, codecs, media timing, queues, wireless security and recovery | Existing CarPlay path, unchanged |
| Native status=false, stopCast, death unlink and unbind | Same source close; complete before releasing the preview Surface and session ownership |
| System capture consent and host-only teardown isolation | Existing CarPlay route; verification cannot consume a screen-capture grant |
| Cancellation, duplicate host starts and late callbacks | One owned projection session; reject overlap, ignore stale completion, close resources once |
| Signed, redacted phone log uploads | Same journal/uploader; label verification sessions and record actual local display updates separately from transmitted frames/bytes |

## Device procedure

1. Install the component APK and select **组件验证模式**.
2. Allow **显示在其他应用上层**, then start verification. The overlay can wait for Jovi without a selected or nearby car.
3. Open **Jovi 智能车载** and use its component entry. A real host request is required. The overlay reports waiting, interface binding, native display readiness, first rendered frame and measured updates.
4. Close the overlay or stop verification. Switch back to **CarPlay 模式** to restore wireless behavior.

A binding callback alone is not success. Only actual TextureView buffer updates establish local picture output. Updates measure displayed buffers, not encoder frame rate. The preview does not play audio or send touch/microphone input; native callback diagnostics remain visible. Host signature rejection is reported without suppressing or changing verification.

Android requires [overlay permission](https://developer.android.com/reference/android/provider/Settings#canDrawOverlays(android.content.Context)) and a [hardware accelerated TextureView window](https://developer.android.com/reference/android/view/TextureView). The local diagnostic foreground service declares [specialUse](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use); it does not claim a MediaProjection grant.

## Validation

Regression tests cover mode isolation, exclusive session ownership, host/surface arrival order, duplicate starts, failures, cancellation, stale callbacks, actual first-frame evidence, timestamp deduplication and local metrics export. Device validation must separately record the vivo/Jovi version and an actual signed-host request and rendered picture. JVM tests and an APK build cannot establish that a particular Jovi version offers an off-car component launch.

Validated on 2026-10-12: component and standalone builds; 58 app tests per flavor, 160 iAP2 tests and 80 AirPlay tests, all passing (298 distinct tests, 356 executions, including 17 new tests). Both app Lint tasks and library `lintDebug` passed with no errors. The component APK passed APK v2 signature verification. Existing network and Bluetooth recovery changes in the working directory were retained. No vivo was attached; no device installation or actual Jovi picture output is claimed.

Artifact: `artifacts/carplay-0.31.apk`, package `com.baidu.carlife.vivo`, version `8.8.4-carplay.0.31` / `100036`, 9,801,137 bytes. SHA-256: `47f4d633826373f2f82094bfca2b20fd156958c5c1256d6ea2c9aa3537a29af1`. Extra local preview summary fields preserve schema version 1 and the existing backend's event reader; signed upload proofs and network counters are unchanged.
