# Safe master-switch controller

This branch adds the tiny companion app requested for PixelStatusBar-OneUI.

## What the switch does

- Stores a single master-enable state for the **current boot session only**.
- Exposes that state read-only through `content://com.jorge.pixelstatusbar.oneui.settings/state` so the future LSPosed SystemUI hook can observe it without root shell commands or world-readable preferences.
- Automatically evaluates to OFF after a reboot by binding the stored state to Android's `Settings.Global.BOOT_COUNT`.

## What it deliberately does not do

- No `su` execution.
- No `cmd overlay` calls.
- No `/system`, `/product`, `/vendor`, `/system_ext`, boot image, vbmeta, init, or `service.d` changes.
- No replacement or patching of `SystemUI.apk`.
- No automatic enable on boot.

The visual Wi-Fi/mobile/battery hooks are intentionally not part of this controller-only change. The controller is the safety gate they will consume.
