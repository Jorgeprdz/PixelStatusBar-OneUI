package com.jorge.pixelstatusbar.oneui;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

final class SettingsStore {
    private static final String PREFS = "pixel_status_controller";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_BOOT_COUNT = "boot_count";

    private SettingsStore() {}

    static int currentBootCount(Context context) {
        try {
            return Settings.Global.getInt(
                    context.getContentResolver(),
                    Settings.Global.BOOT_COUNT
            );
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static boolean isEnabledForCurrentBoot(Context context) {
        try {
            Context deviceContext = context.createDeviceProtectedStorageContext();
            SharedPreferences prefs = deviceContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int currentBoot = currentBootCount(context);
            if (currentBoot < 0) {
                return false;
            }
            return prefs.getBoolean(KEY_ENABLED, false)
                    && prefs.getInt(KEY_BOOT_COUNT, -1) == currentBoot;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void setEnabledForCurrentBoot(Context context, boolean enabled) {
        Context deviceContext = context.createDeviceProtectedStorageContext();
        SharedPreferences prefs = deviceContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int currentBoot = currentBootCount(context);

        if (enabled && currentBoot >= 0) {
            prefs.edit()
                    .putBoolean(KEY_ENABLED, true)
                    .putInt(KEY_BOOT_COUNT, currentBoot)
                    .apply();
        } else {
            prefs.edit()
                    .putBoolean(KEY_ENABLED, false)
                    .putInt(KEY_BOOT_COUNT, -1)
                    .apply();
        }

        try {
            context.getContentResolver().notifyChange(SettingsProvider.STATE_URI, null);
        } catch (Throwable ignored) {
            // Preference change already succeeded; notification is best-effort.
        }
    }
}
