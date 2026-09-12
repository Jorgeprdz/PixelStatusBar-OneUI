package com.jorge.pixelstatusbar.oneui;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.concurrent.CopyOnWriteArrayList;

/** Cross-process master state. Any failure means OFF. */
final class ToggleState {
    private static volatile boolean enabled;
    private static volatile boolean started;
    private static final CopyOnWriteArrayList<WeakReference<PixelStatusDrawable>> DRAWABLES =
            new CopyOnWriteArrayList<>();

    private ToggleState() {}

    static void track(PixelStatusDrawable drawable) {
        cleanup();
        DRAWABLES.add(new WeakReference<>(drawable));
        drawable.setPixelEnabled(enabled);
    }

    static synchronized void ensureStarted(Context context) {
        if (started) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        enabled = readState(app);
        try {
            Context finalApp = app;
            app.getContentResolver().registerContentObserver(
                    SettingsProvider.STATE_URI,
                    false,
                    new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            refresh(finalApp);
                        }
                    });
        } catch (Throwable ignored) {
            enabled = false;
        }
        started = true;
    }

    private static void refresh(Context context) {
        enabled = readState(context);
        for (WeakReference<PixelStatusDrawable> ref : DRAWABLES) {
            PixelStatusDrawable d = ref.get();
            if (d != null) d.setPixelEnabled(enabled);
        }
        cleanup();
    }

    private static boolean readState(Context context) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    SettingsProvider.STATE_URI,
                    new String[] {"enabled"}, null, null, null);
            return cursor != null && cursor.moveToFirst() && cursor.getInt(0) == 1;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Throwable ignored) { }
            }
        }
    }

    private static void cleanup() {
        for (WeakReference<PixelStatusDrawable> ref : DRAWABLES) {
            if (ref.get() == null) DRAWABLES.remove(ref);
        }
    }
}
