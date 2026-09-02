# Codex Task — Pixel-style status bar icons for One UI 8.5

Implement the first working prototype of **PixelStatusBar-OneUI** as a minimal LSPosed module.

## Goal
On a Samsung Galaxy S25 (SM-S931B), Android 16, One UI 8.5, replace only the visual representation of these status-bar items with Pixel/AOSP-style equivalents:

- Wi-Fi
- mobile signal
- battery

Do not alter unrelated SystemUI behavior.

## Hard constraints
Read and obey `AGENTS.md` before making changes.

The implementation must:
- target `com.android.systemui` only;
- avoid all persistent system modifications;
- never patch `SystemUI.apk`;
- never mount replacement resources into `/system`;
- be safe when the exact Samsung class/resource differs;
- isolate every feature hook so one failure cannot crash SystemUI or prevent other hooks from loading;
- use tint-aware vector/programmatic drawing instead of hard-coded icon colors;
- keep the module easy to disable from LSPosed.

## Research / reference points
Study current public Samsung-oriented LSPosed implementations, especially:

1. `SoClear/OneUIX`
   - `app/src/main/java/io/github/soclear/oneuix/hook/systemui/StatusBar.kt`
   - `BatteryMeterView`
   - `StatusBarIconControllerImpl`
   - Samsung status-bar layout/padding handling

2. `mschiller890/paddington`
   - One UI 8.5 / Android 16 SystemUI hook architecture
   - safe class/method discovery patterns

Do not blindly copy source code. Reimplement the required behavior cleanly and document any licensing-relevant inspiration.

## Preferred implementation approach
Start with the least invasive hook strategy that works.

### Wi-Fi
Investigate Samsung's Android 16 SystemUI Wi-Fi icon pipeline and identify the final `ImageView`/drawable assignment point or model-to-view binding point.

Preferred order:
1. Hook final drawable assignment for the Wi-Fi status-bar view and swap only Samsung Wi-Fi drawables for module-owned Pixel/AOSP-style drawables.
2. If the icon is represented by a custom view/model, hook the smallest view-binding method and set a module-owned drawable there.
3. Avoid replacing shared framework resources globally.

Support common states:
- signal 0–4 (or nearest Samsung levels)
- connected
- limited/no internet if distinguishable
- disabled/disconnected should remain hidden when Samsung would hide it

### Mobile signal
Use the same philosophy: alter only the final rendered signal glyph, keeping Samsung telephony state logic intact.

Must not interfere with:
- SIM selection
- carrier state
- dual-SIM ordering
- data type text/icons unless they are unavoidably part of the same drawable pipeline

Support common signal levels and gracefully pass through unknown special states.

### Battery
Prefer hooking `com.android.systemui.battery.BatteryMeterView` or its Android 16/One UI 8.5 equivalent.

The safest acceptable first implementation is a module-owned custom drawable/view that:
- reflects current battery level;
- supports charging state;
- follows SystemUI tint;
- respects icon size;
- does not break Samsung's battery percentage text preference.

Do not replace Samsung's battery state source; only replace rendering.

## Fail-open requirements
Create a small utility layer, e.g.:
- `SafeHook.run(featureName) { ... }`
- `findClassIfExists`
- candidate method lookup
- structured logging

For each feature:
- if target class missing: log and skip;
- if method signature changed: log and skip;
- if expected view/field type differs: log and skip;
- never throw from `handleLoadPackage` because of a feature failure.

Unknown states should use Samsung's original icon rather than displaying a wrong icon.

## Module metadata
Use a unique package such as:
`com.jorge.pixelstatusbar.oneui`

App/module name:
`Pixel Status Bar for One UI`

LSPosed scope documentation:
`com.android.systemui` only.

## UI
Keep the companion UI tiny.

Required controls:
- master enable
- Pixel Wi-Fi
- Pixel mobile signal
- Pixel battery

If cross-process preferences are not reliable in the chosen LSPosed API, it is acceptable for v0.1 to default all three features on and clearly document that a SystemUI restart/reboot is needed after changing settings. Do not introduce a risky preference mechanism just to avoid restart.

## Assets
Create clean vector drawables or programmatic paths inspired by modern AOSP/Pixel status icons.

Do not download or commit proprietary APK resources.

At minimum provide:
- Wi-Fi signal levels
- cellular signal levels
- Pixel-style battery outline/fill or a programmatic battery drawable
- charging indication

Use `android:tint` / runtime tint or equivalent so icons adapt to SystemUI color changes.

## Build system
Set up a clean Android Gradle project with:
- Kotlin
- Gradle wrapper
- LSPosed/Xposed API dependency as `compileOnly` where appropriate
- minSdk appropriate for the module but optimize for Android 16 target
- no unnecessary dependencies

## GitHub Actions
Add `.github/workflows/build.yml`:
- trigger on push, pull_request, workflow_dispatch
- use a supported JDK
- run `chmod +x gradlew`
- run `./gradlew :app:assembleDebug`
- upload `app/build/outputs/apk/debug/app-debug.apk`
- artifact name: `PixelStatusBar-OneUI-debug`

Build artifact upload must still run when non-build quality checks are absent. Keep the workflow focused on producing a test APK.

## README
Document:
- exact target environment;
- experimental status;
- install APK;
- enable module in LSPosed;
- scope only `System UI (com.android.systemui)`;
- restart SystemUI or reboot while rooted;
- how to disable/recover;
- how to collect LSPosed logs filtered by `PixelStatusBar-OneUI`;
- no guarantee for One UI versions other than 8.5 until tested.

## Validation
Before declaring completion:

1. Run `./gradlew :app:assembleDebug`.
2. Fix all compilation/resource errors.
3. Confirm the generated APK contains Xposed/LSPosed module metadata.
4. Review every `com.android.systemui` hook for uncaught exceptions.
5. Ensure no code writes to protected partitions or executes root shell commands.
6. Confirm GitHub Actions syntax and artifact path.

## Deliverable
Open a PR against `main` containing a complete buildable v0.1 prototype.

In the PR body include:
- architecture summary;
- exact SystemUI classes/methods targeted;
- fallback behavior when those hooks are unavailable;
- build result;
- what still requires validation on the physical S25;
- clear warning that no APK should be installed until the PR/build has been reviewed.
