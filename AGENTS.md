# Repository Guidelines

## Project Structure & Module Organization

- `app/` owns connection lifecycle, the phone dashboard, settings, and signed log uploads. The `standalone` flavor uses `com.carplay.app`; `vivoComponent` uses `com.baidu.carlife.vivo` and the CarLife entry points under `app/src/vivoComponent/`.
- `core-iap2/` implements framing, messages, sessions, and Bluetooth/USB transports.
- `core-airplay/` preserves receiver implementations and adds controller pairing, encrypted control, media queues, and sender protocols under `com/carplay/sender/`.
- `adapter-vivo/`, `adapter-xiaomi/`, and `adapter-generic/` implement device-specific projection. Keep vendor behavior here and register adapters in the app.

Sources use `src/main/java/com/carplay/`; tests use `src/test/java/`. Resources belong in `src/main/res/`. Native vivo capture uses `VivoHostSurface` and the original CarLife Binder ABI. Read `docs/behavior-inventory.md` before changing protocol or capture behavior.

## Build, Test, and Development Commands

Run from the repository root; use `./gradlew` instead of `.\gradlew.bat` on Unix.

- `.\gradlew.bat :app:assembleVivoComponentDebug` builds the vivo component APK.
- `.\gradlew.bat :app:assembleStandaloneDebug` builds the separate diagnostic sender.
- `.\gradlew.bat :app:testStandaloneDebugUnitTest :core-iap2:testDebugUnitTest :core-airplay:testDebugUnitTest` runs JVM regression tests.
- `.\gradlew.bat :app:lintVivoComponentDebug :app:lintStandaloneDebug lintDebug` checks app flavors and libraries.

See Android's [build](https://developer.android.com/build/building-cmdline) and [test](https://developer.android.com/studio/test/command-line) command references.

AGP 9.2.1 uses built-in Kotlin; Gradle is 9.4.1, compile SDK 36, minimum SDK 26, and JVM target 17. Wireless network requests require Android 10+. Use an explicit ADB device serial when installing. Keep SDK paths in ignored `local.properties`.

## Coding Style & Naming Conventions

Follow `kotlin.code.style=official` with four-space indentation. Use PascalCase for types/files, camelCase for functions/properties, and UPPER_SNAKE_CASE for constants. Preserve `com.carplay.*` package boundaries. Use Android Studio formatting and Android Lint; no ktlint or Detekt configuration exists.

## Testing Guidelines

Use JUnit 4.13.2 and name tests `*Test`. Cover malformed frames, identity changes, timing, queue overflow, disconnect recovery, and cleanup. No coverage threshold is configured. Record device model, Android version, host version, and actual session evidence for hardware tests; compilation does not establish car compatibility.

## Commit & Pull Request Guidelines

History contains one `feat:` commit. Continue with concise `type: summary` subjects, such as `fix: handle iAP2 timeout`. PRs should explain behavior changes, affected modules, related issues, and commands/results, including blocked checks. Include screenshots for UI changes and sanitized diagnostics for transport changes.

## Behavior Preservation & Configuration

Map original behavior to its replacement; preserve protocol semantics, timing, retries, negotiated values, security checks, ownership, and diagnostics. Keep CA validation available alongside explicit paired-peer key binding. Verify equivalence with regression tests and runtime evidence. Keep signing keys, credentials, device addresses, and private captures out of commits.
