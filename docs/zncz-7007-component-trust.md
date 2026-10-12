# zncz 7.0.0.7 component trust investigation

Date: 2026-10-12 (Asia/Taipei). Scope: identify the native CarLife trust failure using the actual vivo host APK and public signing metadata. No vendor code, signing identity, registry, licensing enforcement or security property was changed.

## Actual device and APK evidence

The supplied remote ADB endpoint initially exposed the expected vivo V2408A, Android 16 / API 36. Package Manager reported `com.vivo.car.networking` version `7.0.0.7` / `7007`, a system application with signing-certificate history. Its pulled APK is 96,934,628 bytes, SHA-256 `97212a97d1df0b5de48aaa56744ac6a600cef2744dc347933505fd648ef6c37e`. APK signature verification passed.

The host and extracted Java sources are retained outside this repository in `C:/Users/Administrator/AppData/Local/Temp/carplay-host-7007-20261012/`. Sources examined: `service/sources/com/vivo/vivo_car_service/CarLifeMixingService.java`, `verifier/sources/com/vivo/car/networking/base/util/g.java`, and `registry/sources/ac/c.java`.

The installed sender was initially 0.31 / 100036. During the session it changed to 0.30 / 100035, with a recorded update time of 14:44:16. Both observed package signing identities were the same. The device briefly disconnected and was restored. No sender or host APK was installed or uninstalled in this investigation. Current UI changed between the app and other phone applications, so no new completed interface-check reproduction is claimed.

## Exact host behavior

`CarLifeMixingService.onTransact` (line 1529) resolves the calling UID's package and invokes `ComponentVerifier.g.c().l(...)` before delegating **any** transaction to the AIDL stub. The last accepted calling UID is cached only after verification succeeds. The extra descriptor query in our client differs from the original CarLife proxy, but removing it does not bypass this common guard: ordinary callback and prepare transactions encounter the same check.

`g.k` (line 192) obtains `ScreenCpInfo.sign`; `g.j` (line 178) compares a nonempty registered sign directly with the installed package's certificate MD5. Only an empty registered sign enters the cache/online query path. `g.l` (line 208) throws `UnsupportedOperationException("Not yet implemented")` on rejection. This confirms that the exception observed in the uploaded 12:34:55 0.31 session is consistent with this exact host version, rather than relying on the old 6.0.8.3 implementation.

The host's `assets/vendor_sdk_default_pro.json` explicitly registers `com.baidu.carlife.vivo` with certificate MD5 `e4cebec6e83e2817aaae40de3a2275cb`, minimum version `99`. Registry initialization in `ac.c.N` combines the default normal and SDK vendor lists. This establishes the built-in expectation; the current live in-memory list could be replaced, so it is not a substitute for runtime signer logging.

| Artifact | Verified certificate MD5 |
| --- | --- |
| Original CarLife component 8.8.4 | `e4cebec6e83e2817aaae40de3a2275cb` |
| Current local vivo v11 reference | `6b4deb7e178e3317d50e3785ed04d27a` |
| Our 0.31 component | `6b4deb7e178e3317d50e3785ed04d27a` |
| Existing September coexistence test APK | `e4cebec6e83e2817aaae40de3a2275cb` |

The September test APK passed both v1 and v2 verification; its package is `com.baidu.carlifevehicle.beta`. Its signing provenance is unknown: the user has only the APK, and `D:/git/carlife_20260914` is a decompiled project with no signing configuration. A valid certificate in an APK does not supply its private signing key or permission to reuse the issuer's identity.

## Public documentation and current limit

Context7 did not provide a matching CarLife signing guide. The [official CarLife integration page](https://carlife.baidu.com/carlife/caroem/start) describes applying, receiving SDK/integration documents, and certification. No official public configuration authorizing third parties to sign with the observed Baidu certificate was located in this search.

A [2026 signing-key protection study](https://arxiv.org/abs/2606.21487) discusses compromised Android signing credentials and automotive impact, including CarLife in the full text. Public exposure of a key is not an authorized signing channel. No leaked key, password or impersonation signer was retrieved or used; no assertion is made that the existing test APK used that source.

The native trust objective remains unresolved. A viable authorized next step is a documented signing/issuance arrangement for this component, or vendor registration of our own package and certificate. Changing a version string, drawing Surface, Wi-Fi policy or client-side error message cannot make a different installed certificate satisfy the host's registered certificate comparison.

## Alternative integration investigation

Follow-up on 2026-10-12 used read-only inspection of the same verified 7.0.0.7 APK and the authorized remote device. No Binder probes, vendor network requests, registry writes, host patches, property changes, or APK installations were performed. Package Manager now reports our component as 0.31 / 100036, updated at 15:22:35; this investigation did not perform that update.

### Signature query is not component registration

`QuerySignArg` has only `packageName` and `version` request fields. It asks the vendor service for an expected signing fingerprint; it does not submit an applicant's certificate for registration. `ComponentVerifier.g.j` uses this route only when the registered sign is empty. A nonempty registered sign is compared locally first, so a different version code or an available network does not register a new signer for the existing CarLife identity.

`ScreenCpInfo` stores the manufacturer, package, expected sign, minimum version, channel type, and SDK flag. The inspected `ac.c.N` initializes the in-memory list from bundled vendor assets. `com.vivo.MainActivity` (lines 332–333) also has an internal load from `screenCpInfoDao` when that list is null. These are host-owned initialization paths, not an exposed registration API. Neither the current live list nor the private database was read, so the bundled expectation remains distinct from a verified live registry snapshot.

### Other inspected entry points

| Entry point | Evidence in 7.0.0.7 | Implication for this sender |
| --- | --- | --- |
| `CarLifeMixingService` | Exported; verifies caller identity before AIDL dispatch and has a separate CarLife signing check. | Binding alone does not authorize callback registration or native capture. |
| `CastScreenService` | Exported with `com.vivo.car.networking.CAR_PERMISSION`; the permission's manifest protection level is `0x3` (`signatureOrSystem`). Its manager must also already be initialized. | It is a protected SDK endpoint, not an ordinary runtime permission that our current APK can request from the user. |
| `CarProxyService` | Returns `xr.b` (`CarInfoStub`), which dispatches String/Bundle commands. The inspected stub has no direct Surface transfer. | A separate service name does not establish an authorized frame-export contract; routed handlers were not exhaustively audited. |
| `CarLinkKitService` | Exposes application/control callbacks and enforces system UID for some operations. | No supported contract for exporting the host's native projection stream to this sender was established. Exported status alone is insufficient evidence. |
| `ScreenProvider` | Exposes connection/app/settings queries; `insert` returns null and `delete` returns zero. | The inspected provider does not supply a component-signing registration or raw frame-export API. |

Additional sources are retained under the same external APK evidence directory: `querysign/`, `screen-model/`, `registry-activity/`, `proxy-service/`, `proxy-binder/`, `carlink-kit/`, `cast-screen-service/`, and `manifest-tree.txt`. No vendor sources or binaries were copied into this repository.

### Official SDK route and missing contract

The [official vivo CastKit page](https://developers.vivo.com/product/d/castKit) describes integration through Jovi InCar SDK and lists CarLife and Carbit cooperation examples. This establishes an official integration route, but the page does not document permission for an arbitrary third-party CarPlay transport to receive the host's raw video/audio stream. Application integration into the car UI and supplying a new projection transport are different capabilities and require separate confirmation.

Context7 returned a generic vivo developer-platform index rather than a matching signing/capture contract; an alternate SDK lookup returned an unrelated project. These results are not evidence of an available Jovi capture API. The publicly indexed product and [contact pages](https://developers.vivo.com/doc/d/a74d0ce7896bd3a9a143691aecfe2fa9) identify the official cooperation/support route. No third party was contacted.

An integration contract for this project needs to answer:

1. Can a projection transport register its **own package and signing certificate**, rather than replace the Baidu identity?
2. Does the SDK expose an authorized virtual-display Surface/video stream, audio callbacks, and input delivery, or only navigation/media application integration?
3. What authorization or permission provisioning is required on Android 16 with host 7.0.0.7?
4. What are the supported formats, callback identities, lifecycle, teardown rules, and SDK distribution/version requirements?

Until that contract is available, the existing system-consent capture path can exercise the wireless sender, while native-component verification must continue to report the actual authorization failure. No native trust success is claimed.

### Runtime evidence freshness

The newest 40 uploaded ZIPs were checked for component-verification mode and the known host rejection. The newest matching verification session remains `d2ecfe40-193a-4167-aec3-50f9842bddbe`, uploaded at 12:34:57: `VERIFY_COMPONENT`, `FAILED`, 205 ms elapsed, zero displayed frames, rejection during descriptor query. More recent uploads through 13:14:24 lack the verification-mode field and do not establish a new native-capture result.

The retained main/system logcat tail did not contain matching signature-rejection records during this read-only check. Absence from that bounded buffer is not evidence of acceptance; no fresh native capture attempt was triggered in this follow-up.
