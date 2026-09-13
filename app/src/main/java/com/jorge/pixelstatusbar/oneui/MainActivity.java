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
    private TextView rootStatus;
    private Button retryRoot;
    private Button restoreAll;

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

        root.addView(text("Pixel Status Bar", 28, true));

        TextView sub = text("v0.17 TRUTHFUL CORE · One UI 8.5 · S25 · root temporal", 15, false);
        sub.setAlpha(.70f);
        sub.setPadding(0, dp(6), 0, dp(14));
        root.addView(sub);

        rootStatus = text("ROOT: comprobando…", 14, true);
        rootStatus.setPadding(0, 0, 0, dp(8));
        root.addView(rootStatus);

        retryRoot = new Button(this);
        retryRoot.setText("Reintentar acceso root");
        retryRoot.setAllCaps(false);
        retryRoot.setOnClickListener(v -> refreshAsync(false));
        root.addView(retryRoot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView intro = text(
                "La app ya no llama ‘estable’ a un overlay sólo por estar habilitado. "
                        + "Muestra ROOT real y FRRO real por separado. La parte visual se marca como ‘verifica icono’. "
                        + "Sólo señal y Wi‑Fi; sin geometría, batería, reloj ni dimensiones.",
                14, false);
        intro.setAlpha(.75f);
        intro.setPadding(0, dp(14), 0, dp(18));
        root.addView(intro);

        addElement(root, RootOverlayController.MOBILE, "Señal móvil Pixel");
        addElement(root, RootOverlayController.WIFI, "Wi‑Fi Pixel");

        restoreAll = new Button(this);
        restoreAll.setText("Restaurar todo Samsung");
        restoreAll.setAllCaps(false);
        restoreAll.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        restoreAll.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams restoreParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        restoreParams.setMargins(0, dp(22), 0, dp(8));
        root.addView(restoreAll, restoreParams);
        restoreAll.setOnClickListener(v -> {
            setControlsEnabled(false);
            worker.execute(() -> {
                RootOverlayController.Result r = RootOverlayController.restoreAll(this);
                runOnUiThread(() -> {
                    restoreAll.setText(r.ok ? "Todo Samsung restaurado" : r.message);
                    refreshAsync(false);
                });
            });
        });

        TextView safety = text(
                "Si ROOT aparece como SIN ROOT EN APP, los switches quedan bloqueados y no se muestran estados viejos. "
                        + "No toca /system, boot, vbmeta, SystemUI.apk, service.d ni LSPosed.",
                13, false);
        safety.setAlpha(.62f);
        safety.setPadding(0, dp(14), 0, 0);
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

        textCol.addView(text(label, 18, false));

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
        setControlsEnabled(false);
        TextView status = statuses.get(key);
        if (status != null) status.setText(checked ? "Aplicando FRRO…" : "Restaurando Samsung…");

        worker.execute(() -> {
            RootOverlayController.Result r = checked
                    ? RootOverlayController.enable(this, key)
                    : RootOverlayController.disable(this, key);
            runOnUiThread(() -> {
                if (status != null) status.setText(r.message);
                refreshAsync(false);
            });
        });
    }

    private void refreshAsync(boolean cleanupOldWifi) {
        setControlsEnabled(false);
        worker.execute(() -> {
            RootOverlayController.RootInfo root = RootOverlayController.rootInfo();
            if (root.ok && cleanupOldWifi) RootOverlayController.cleanupObsoleteWifi(this);

            boolean mobileOn = false;
            boolean wifiOn = false;
            String mobileState = "SIN_ROOT";
            String wifiState = "SIN_ROOT";
            if (root.ok) {
                mobileOn = RootOverlayController.isEnabled(RootOverlayController.MOBILE);
                wifiOn = RootOverlayController.isEnabled(RootOverlayController.WIFI);
                mobileState = RootOverlayController.state(RootOverlayController.MOBILE);
                wifiState = RootOverlayController.state(RootOverlayController.WIFI);
            }

            final boolean fMobileOn = mobileOn;
            final boolean fWifiOn = wifiOn;
            final String fMobileState = mobileState;
            final String fWifiState = wifiState;
            runOnUiThread(() -> applyUi(root, fMobileOn, fWifiOn, fMobileState, fWifiState));
        });
    }

    private void applyUi(RootOverlayController.RootInfo root, boolean mobileOn, boolean wifiOn,
            String mobileState, String wifiState) {
        updating = true;
        rootStatus.setText(root.message);
        rootStatus.setAlpha(root.ok ? 1f : .72f);

        if (!root.ok) {
            for (Switch sw : switches.values()) {
                sw.setChecked(false);
                sw.setEnabled(false);
            }
            for (TextView status : statuses.values()) {
                status.setText("SIN ROOT EN APP · estado FRRO no comprobado");
            }
            retryRoot.setEnabled(true);
            restoreAll.setEnabled(false);
            updating = false;
            return;
        }

        setSwitch(RootOverlayController.MOBILE, mobileOn);
        setSwitch(RootOverlayController.WIFI, wifiOn);
        setStateText(RootOverlayController.MOBILE, mobileState);
        setStateText(RootOverlayController.WIFI, wifiState);

        retryRoot.setEnabled(true);
        restoreAll.setEnabled(true);
        for (Switch sw : switches.values()) sw.setEnabled(true);
        updating = false;
    }

    private void setSwitch(String key, boolean checked) {
        Switch sw = switches.get(key);
        if (sw != null) sw.setChecked(checked);
    }

    private void setStateText(String key, String state) {
        TextView status = statuses.get(key);
        if (status == null) return;
        if ("ACTIVO".equals(state)) status.setText("FRRO ACTIVO · verifica icono");
        else if ("AUTO-REVERTIDO".equals(state)) status.setText("AUTO-REVERTIDO · Samsung restaurado");
        else if ("ERROR".equals(state)) status.setText("ERROR · FRRO no activo");
        else status.setText("OFF · Samsung");
    }

    private void setControlsEnabled(boolean enabled) {
        for (CompoundButton sw : switches.values()) sw.setEnabled(enabled);
        if (retryRoot != null) retryRoot.setEnabled(enabled);
        if (restoreAll != null) restoreAll.setEnabled(enabled);
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
