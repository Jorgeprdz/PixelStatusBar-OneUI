package com.jorge.pixelstatusbar.oneui;

import android.app.Activity;
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
    private Switch masterSwitch;
    private TextView statusText;
    private boolean updating;

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
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = text("Pixel Status Bar", 28, true);
        root.addView(title);
        TextView sub = text("One UI 8.5 · S25 · root temporal · sin reinicio", 15, false);
        sub.setAlpha(.70f); sub.setPadding(0, dp(6), 0, dp(28)); root.addView(sub);

        masterSwitch = new Switch(this);
        masterSwitch.setText("Iconos Pixel 11");
        masterSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        masterSwitch.setMinHeight(dp(56));
        root.addView(masterSwitch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusText = text("Comprobando…", 16, false);
        statusText.setPadding(0, dp(18), 0, dp(28)); root.addView(statusText);

        TextView safety = text("ON usa root sólo para crear un overlay temporal de recursos. No toca /system, boot, vbmeta, SystemUI.apk ni service.d. OFF desactiva el overlay. Si reinicias, Android elimina este overlay de shell durante el arranque.", 14, false);
        safety.setAlpha(.72f); root.addView(safety);

        masterSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (updating) return;
            masterSwitch.setEnabled(false);
            statusText.setText(checked ? "Activando de forma segura…" : "Restaurando Samsung…");
            worker.execute(() -> {
                RootOverlayController.Result r = checked ? RootOverlayController.enable(this) : RootOverlayController.disable();
                runOnUiThread(() -> {
                    statusText.setText(r.message);
                    updating = true;
                    masterSwitch.setChecked(r.ok ? checked : RootOverlayController.isEnabled());
                    updating = false;
                    masterSwitch.setEnabled(true);
                });
            });
        });
        return root;
    }

    private void refreshAsync() {
        if (masterSwitch == null) return;
        masterSwitch.setEnabled(false);
        worker.execute(() -> {
            boolean on = RootOverlayController.isEnabled();
            runOnUiThread(() -> {
                updating = true; masterSwitch.setChecked(on); updating = false;
                statusText.setText(on ? "ON · overlay temporal activo" : "OFF · One UI original");
                masterSwitch.setEnabled(true);
            });
        });
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
