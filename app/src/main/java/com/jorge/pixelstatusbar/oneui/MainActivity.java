package com.jorge.pixelstatusbar.oneui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Switch> switches = new LinkedHashMap<>();
    private final Map<String, TextView> statuses = new LinkedHashMap<>();
    private boolean updating;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refreshAsync(true);
    }

    @Override protected void onResume() { super.onResume(); refreshAsync(false); }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(40));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Pixel Status Bar", 28, true);
        root.addView(title);

        TextView sub = text("v0.15 SAFE CORE · One UI 8.5 · S25 · root temporal", 15, false);
        sub.setAlpha(.70f);
        sub.setPadding(0, dp(6), 0, dp(22));
        root.addView(sub);

        TextView intro = text(
                "Sólo quedan activos los dos cambios que ya demostraron funcionar: señal móvil y Wi‑Fi. "
                        + "Geometría, batería y reloj quedan retirados de prueba porque tocar dimensiones de SystemUI provocó un reboot completo. "
                        + "Wi‑Fi conserva limpieza de FRRO antiguos y watchdog independiente.",
                14, false);
        intro.setAlpha(.75f);
        intro.setPadding(0, 0, 0, dp(18));
        root.addView(intro);

        addElement(root, RootOverlayController.MOBILE, "Señal móvil Pixel");
        addElement(root, RootOverlayController.WIFI, "Wi‑Fi Pixel");

        Button restoreAll = new Button(this);
        restoreAll.setText("Restaurar todo Samsung");
        restoreAll.setAllCaps(false);
        restoreAll.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        restoreAll.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams restoreParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        restoreParams.setMargins(0, dp(22), 0, dp(8));
        root.addView(restoreAll, restoreParams);
        restoreAll.setOnClickListener(v -> {
            setAllSwitchesEnabled(false);
            restoreAll.setEnabled(false);
            worker.execute(() -> {
                RootOverlayController.Result r = RootOverlayController.restoreAll(this);
                runOnUiThread(() -> {
                    refreshUiStates();
                    restoreAll.setText(r.ok ? "Todo Samsung restaurado" : r.message);
                    restoreAll.setEnabled(true);
                    setAllSwitchesEnabled(true);
                });
            });
        });

        TextView retired = text(
                "Retirado por seguridad: Geometría Pixel, Batería Pixel y Reloj Pixel. No se pueden activar desde esta versión.",
                13, true);
        retired.setAlpha(.72f);
        retired.setPadding(0, dp(18), 0, 0);
        root.addView(retired);

        TextView safety = text(
                "Antes de desinstalar, usa ‘Restaurar todo Samsung’. Los FRRO pertenecen a com.android.shell, no a la APK. "
                        + "No toca /system, boot, vbmeta, SystemUI.apk, service.d ni LSPosed.",
                13, false);
        safety.setAlpha(.62f);
        safety.setPadding(0, dp(12), 0, 0);
        root.addView(safety);

        return scroll;
    }

    private void addElement(LinearLayout root, String key, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, 0);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView labelView = text(label, 18, false);
        textCol.addView(labelView);

        TextView status = text("Comprobando…", 14, false);
        status.setAlpha(.68f);
        status.setPadding(0, dp(4), 0, dp(8));
        textCol.addView(status);
        statuses.put(key, status);

        Switch sw = new Switch(this);
        sw.setSplitTrack(false);
        tintSwitch(sw);
        switches.put(key, sw);

        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(textCol, textParams);
        row.addView(sw, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sw.setOnCheckedChangeListener((buttonView, checked) -> onSwitchChanged(key, checked));
    }

    private void onSwitchChanged(String key, boolean checked) {
        if (updating) return;
        setAllSwitchesEnabled(false);
        TextView status = statuses.get(key);
        if (status != null) {
            if (!checked) status.setText("Restaurando Samsung y limpiando FRRO…");
            else if (RootOverlayController.WIFI.equals(key)) status.setText("PROBANDO… Wi‑Fi base + Wi‑Fi 6");
            else status.setText("PROBANDO… señal móvil");
        }

        worker.execute(() -> {
            RootOverlayController.Result r = checked
                    ? RootOverlayController.enable(this, key)
                    : RootOverlayController.disable(this, key);
            runOnUiThread(() -> {
                if (status != null) status.setText(r.message);
                refreshUiStates();
                setAllSwitchesEnabled(true);
            });
        });
    }

    private void refreshAsync(boolean cleanupOldWifi) {
        setAllSwitchesEnabled(false);
        worker.execute(() -> {
            if (cleanupOldWifi) RootOverlayController.cleanupObsoleteWifi(this);
            runOnUiThread(() -> {
                refreshUiStates();
                setAllSwitchesEnabled(true);
            });
        });
    }

    private void refreshUiStates() {
        updating = true;
        for (String key : switches.keySet()) {
            boolean on = RootOverlayController.isEnabled(key);
            Switch sw = switches.get(key);
            if (sw != null) sw.setChecked(on);
            TextView status = statuses.get(key);
            if (status == null) continue;

            String state = RootOverlayController.state(key);
            if ("ESTABLE".equals(state)) status.setText("ESTABLE · ON");
            else if ("PARCIAL".equals(state)) status.setText("PARCIAL · apaga y vuelve a activar Wi‑Fi Pixel");
            else if ("AUTO-REVERTIDO".equals(state)) status.setText("AUTO-REVERTIDO · Samsung restaurado");
            else if (on) status.setText("ON · activo");
            else status.setText("OFF · Samsung");
        }
        updating = false;
    }

    private void setAllSwitchesEnabled(boolean enabled) {
        for (CompoundButton sw : switches.values()) sw.setEnabled(enabled);
    }

    private void tintSwitch(Switch sw) {
        int[][] states = new int[][] {
                new int[] { android.R.attr.state_checked },
                new int[] { -android.R.attr.state_checked }
        };
        sw.setThumbTintList(new ColorStateList(states, new int[] { Color.WHITE, 0xffe3e3e3 }));
        sw.setTrackTintList(new ColorStateList(states, new int[] { 0xff8ab4f8, 0xff5f6368 }));
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
