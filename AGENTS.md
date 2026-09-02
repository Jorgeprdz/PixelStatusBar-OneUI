# AGENTS.md — PixelStatusBar-OneUI

## Mission
Build a minimal, reversible LSPosed module for Samsung One UI that replaces selected status-bar visuals with Pixel/AOSP-style equivalents while leaving the rest of SystemUI behavior untouched.

## Target device / environment
- Samsung Galaxy S25 (SM-S931B)
- Android 16
- One UI 8.5
- Root is temporary/volatile
- KernelSU-based root environment
- Zygisk + LSPosed may be present only while rooted
- Primary hook target: `com.android.systemui`

## Non-negotiable safety rules
1. Never modify `/system`, `/product`, `/vendor`, `/system_ext`, boot images, vbmeta, init scripts, partitions, or firmware.
2. Never patch or replace `SystemUI.apk`.
3. Never require a Magisk/KernelSU module that mounts files over system partitions.
4. The module must be fail-open: if any Samsung class, method, field, resource, or layout differs from expectations, log the failure and skip that hook. Do not crash SystemUI.
5. Every hook must be scoped to `com.android.systemui` unless a tiny companion-app hook is strictly necessary. Prefer no extra scopes.
6. Do not hook broad framework APIs globally.
7. Do not use method replacements when an after/before hook is sufficient.
8. Wrap risky reflection/Xposed operations in isolated `try/catch` blocks so one failed feature cannot prevent the others from loading.
9. No reboot loops: the APK must remain uninstallable/disableable from LSPosed. A device reboot should remove the temporary root environment and therefore naturally disable hooks.
10. Do not add unrelated customization features.

## Functional scope for v0.1
Only these status-bar elements:
- Wi-Fi indicator: Pixel/AOSP-inspired glyphs for common signal levels and disconnected/limited states.
- Mobile signal indicator: Pixel/AOSP-inspired glyphs for common signal levels, including dual-SIM-safe behavior where possible.
- Battery indicator: Pixel-style battery outline/fill with charging state and optional percentage compatibility.
- Preserve Samsung clock, notification icons, privacy indicators, NFC/Bluetooth/etc. unless explicitly required for layout compatibility.

## Visual requirements
- Use vector drawables or programmatic drawing owned by this repository; do not copy proprietary Pixel binary assets.
- Match modern AOSP/Pixel geometry closely enough to be visually recognizable while keeping the implementation legally clean.
- Support light/dark icon tint supplied by SystemUI; do not hard-code white or black.
- Respect density and status-bar icon sizing.
- Do not cause icon clipping, excessive padding, or layout jumps.

## Compatibility strategy
Use runtime discovery and multiple candidate paths rather than assuming one class name forever.

Reference implementations worth studying for Samsung One UI behavior:
- `SoClear/OneUIX`, especially `app/src/main/java/io/github/soclear/oneuix/hook/systemui/StatusBar.kt`
- `mschiller890/paddington`, especially its SystemUI hooks for One UI 8.5 / Android 16

Do not copy GPL code into this repository unless license compatibility and attribution are explicitly handled. Prefer independently implementing the required hooks after studying public behavior.

## Architecture expectations
- Kotlin preferred.
- Small Android app that is also an LSPosed/Xposed module.
- A single hook entry point.
- Feature classes such as `WifiIconHook`, `MobileSignalHook`, `BatteryHook`.
- Central `SafeHook`/logging helpers.
- Minimal settings UI: master enable plus individual Wi-Fi/mobile/battery toggles is acceptable; avoid feature creep.
- Default state after install should be conservative. If preferences cannot be read from SystemUI, hooks must safely no-op rather than crash.

## Build requirements
- Gradle wrapper committed.
- Reproducible debug build from a clean checkout.
- GitHub Actions workflow that runs `./gradlew :app:assembleDebug` and uploads the resulting APK artifact.
- Do not make lint a blocker for the first functional prototype if it prevents artifact generation; build correctness is the priority.
- No signing secrets in the repository.

## Diagnostics
Log with a stable tag such as `PixelStatusBar-OneUI`.
At startup log:
- Android SDK
- manufacturer/model
- One UI version if obtainable safely
- package being hooked
- which feature hooks installed successfully
- which candidate hooks/resources were not found

Never log user data, network identifiers, phone numbers, notification content, or other sensitive content.

## Acceptance criteria
A change is not complete unless:
1. `:app:assembleDebug` succeeds.
2. The LSPosed module metadata is valid and the module is discoverable.
3. Scope documentation says to enable only `com.android.systemui`.
4. Failure to find any single hook target does not crash module initialization.
5. README contains install, enable, disable/recovery, and log collection instructions.
6. GitHub Actions uploads the APK.
7. No persistent system modifications are introduced.

## Recovery instructions that must appear in README
If SystemUI becomes unstable while testing:
1. Disable PixelStatusBar-OneUI in LSPosed if accessible.
2. Reboot the phone; with the target temporary-root setup, hooks should disappear when the root/Zygisk/LSPosed environment is no longer active.
3. Do not tell the user to factory-reset as a normal recovery step.

## Working style
- Make complete, coherent commits.
- Do not leave placeholder implementations presented as finished.
- Prefer a narrower feature that builds and fails safely over speculative hooks that can crash SystemUI.
- If exact One UI 8.5 internals cannot be confirmed from public references, implement candidate discovery + logging and clearly mark device validation as required.
