package com.jorge.pixelstatusbar.oneui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private Switch mobileSwitch;
    private Switch wifiSwitch;
    private TextView mobileStatus;
    private TextView wifiStatus;
    private boolean updatingMobile;
    private boolean updatingWifi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refreshAsync();
    }

    @Override protected void onResume() { super.onResume(); refreshAsync(); }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }

    private LinearLayout buildUi() {
        int p = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(p, p, p, p);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = text("Pixel Status Bar", 28, true);
        root.addView(title);

        TextView sub = text("v0.10 SAFE · One UI 8.5 · S25 · root temporal · sin reinicio", 15, false);
        sub.setAlpha(.70f);
        sub.setPadding(0, dp(6), 0, dp(22));
        root.addView(sub);

        mobileSwitch = makeSwitch("Señal móvil Pixel");
        root.addView(mobileSwitch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mobileStatus = text("Comprobando señal…", 14, false);
        mobileStatus.setAlpha(.78f);
        mobileStatus.setPadding(0, dp(4), 0, dp(18));
        root.addView(mobileStatus);

        wifiSwitch = makeSwitch("Wi‑Fi Pixel · prueba Wi‑Fi 6");
        root.addView(wifiSwitch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        wifiStatus = text("Comprobando Wi‑Fi…", 14, false);
        wifiStatus.setAlpha(.78f);
        wifiStatus.setPadding(0, dp(4), 0, dp(22));
        root.addView(wifiStatus);

        TextView safety = text(
                "v0.10 mantiene la señal móvil estable de v0.9 y prueba Wi‑Fi en un FRRO completamente separado. "
                        + "Wi‑Fi tiene watchdog: si SystemUI no permanece estable, se apaga solo. "
                        + "Batería, reloj, layouts y dimensiones no se tocan. "
                        + "No escribe /system ni instala nada al arranque.",
                14, false);
        safety.setAlpha(.72f);
        root.addView(safety);

        mobileSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (updatingMobile) return;
            mobileSwitch.setEnabled(false);
            mobileStatus.setText(checked ? "Aplicando señal móvil…" : "Restaurando señal Samsung…");
            worker.execute(() -> {
                RootOverlayController.Result r = checked
                        ? RootOverlayController.enableMobile(this)
                        : RootOverlayController.disableMobileResult();
                runOnUiThread(() -> {
                    mobileStatus.setText(r.message);
                    updatingMobile = true;
                    mobileSwitch.setChecked(RootOverlayController.isMobileEnabled());
                    updatingMobile = false;
                    mobileSwitch.setEnabled(true);
                });
            });
        });

        wifiSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (updatingWifi) return;
            wifiSwitch.setEnabled(false);
            wifiStatus.setText(checked
                    ? "Probando Wi‑Fi… watchdog armado durante 15 s"
                    : "Restaurando Wi‑Fi Samsung…");
            worker.execute(() -> {
                RootOverlayController.Result r = checked
                        ? RootOverlayController.enableWifi(this)
                        : RootOverlayController.disableWifiResult();
                runOnUiThread(() -> {
                    wifiStatus.setText(r.message);
                    updatingWifi = true;
                    wifiSwitch.setChecked(RootOverlayController.isWifiEnabled());
                    updatingWifi = false;
                    wifiSwitch.setEnabled(true);
                });
            });
        });

        return root;
    }

    private Switch makeSwitch(String label) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        s.setMinHeight(dp(54));
        s.setSplitTrack(false);

        int[][] states = new int[][] {
                new int[] { android.R.attr.state_checked },
                new int[] { -android.R.attr.state_checked }
        };
        s.setThumbTintList(new ColorStateList(states,
                new int[] { Color.WHITE, 0xffe3e3e3 }));
        s.setTrackTintList(new ColorStateList(states,
                new int[] { 0xff8ab4f8, 0xff5f6368 }));
        return s;
    }

    private void refreshAsync() {
        if (mobileSwitch == null || wifiSwitch == null) return;
        mobileSwitch.setEnabled(false);
        wifiSwitch.setEnabled(false);
        worker.execute(() -> {
            boolean mobileOn = RootOverlayController.isMobileEnabled();
            boolean wifiOn = RootOverlayController.isWifiEnabled();
            runOnUiThread(() -> {
                updatingMobile = true;
                mobileSwitch.setChecked(mobileOn);
                updatingMobile = false;
                updatingWifi = true;
                wifiSwitch.setChecked(wifiOn);
                updatingWifi = false;

                mobileStatus.setText(mobileOn
                        ? "ON · señal móvil Pixel activa"
                        : "OFF · señal móvil Samsung");
                wifiStatus.setText(wifiOn
                        ? "ON · Wi‑Fi Pixel activo"
                        : "OFF · Wi‑Fi Samsung");
                mobileSwitch.setEnabled(true);
                wifiSwitch.setEnabled(true);
            });
        });
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
