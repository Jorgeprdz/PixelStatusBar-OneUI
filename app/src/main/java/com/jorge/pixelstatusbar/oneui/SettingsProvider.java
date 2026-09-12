package com.jorge.pixelstatusbar.oneui;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public final class SettingsProvider extends ContentProvider {
    static final String AUTHORITY = "com.jorge.pixelstatusbar.oneui.settings";
    public static final Uri STATE_URI = Uri.parse("content://" + AUTHORITY + "/state");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[] {"enabled", "boot_count"}, 1);
        boolean enabled = getContext() != null && SettingsStore.isEnabledForCurrentBoot(getContext());
        int bootCount = getContext() != null ? SettingsStore.currentBootCount(getContext()) : -1;
        cursor.addRow(new Object[] {enabled ? 1 : 0, bootCount});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.pixelstatusbar.state";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new SecurityException("Read-only settings provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new SecurityException("Read-only settings provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        throw new SecurityException("Read-only settings provider");
    }
}
