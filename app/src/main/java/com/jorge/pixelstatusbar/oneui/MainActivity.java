package com.jorge.pixelstatusbar.oneui;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private Switch masterSwitch;
    private TextView statusText;
    private boolean updatingUi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private LinearLayout buildUi() {
        int pad = dp(24);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        TextView title = new TextView(this);
        title.setText("Pixel Status Bar");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("One UI 8.5 · S25 · Control seguro");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        subtitle.setAlpha(0.70f);
        subtitle.setPadding(0, dp(6), 0, dp(30));
        root.addView(subtitle);

        masterSwitch = new Switch(this);
        masterSwitch.setText("Usar iconos Pixel");
        masterSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        masterSwitch.setMinHeight(dp(56));
        root.addView(masterSwitch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        statusText = new TextView(this);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        statusText.setPadding(0, dp(18), 0, 0);
        root.addView(statusText);

        Space spacer = new Space(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, dp(34)));

        TextView safety = new TextView(this);
        safety.setText("Seguro por diseño: no modifica /system, no reemplaza SystemUI.apk y no ejecuta comandos root. El estado ON sólo vale durante el arranque actual; después de reiniciar vuelve a OFF automáticamente.");
        safety.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        safety.setAlpha(0.72f);
        root.addView(safety);

        masterSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingUi) {
                return;
            }
            SettingsStore.setEnabledForCurrentBoot(this, isChecked);
            refresh();
        });

        return root;
    }

    private void refresh() {
        if (masterSwitch == null || statusText == null) {
            return;
        }

        boolean enabled = SettingsStore.isEnabledForCurrentBoot(this);
        updatingUi = true;
        masterSwitch.setChecked(enabled);
        updatingUi = false;

        if (enabled) {
            statusText.setText("ON · habilitado sólo para esta sesión de arranque");
        } else {
            statusText.setText("OFF · One UI permanece intacto");
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
