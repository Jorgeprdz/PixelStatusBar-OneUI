package com.jorge.pixelstatusbar.oneui;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** v0.18 RESCUE: cleanup-only UI. No activation controls exist. */
public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TextView rootStatus;
    private TextView omsStatus;
    private Button reconcileButton;
    private Button inspectButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        // Safe reconciliation only: never registers or enables an overlay.
        reconcileAsync("RECONCILIANDO AL ABRIR…");
    }

    @Override
    protected void onResume() {
        super.onResume();
        inspectAsync();
    }

    @Override
    protected void onDestroy() {
        // Let an already-started cleanup finish; never interrupt it mid-OMS transaction.
        worker.shutdown();
        super.onDestroy();
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(40));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(text("Pixel Status Bar", 28, true));

        TextView sub = text("v0.18 RESCUE · FAIL-CLOSED · One UI 8.5 · S25", 15, false);
        sub.setAlpha(.72f);
        sub.setPadding(0, dp(6), 0, dp(18));
        root.addView(sub);

        TextView warning = text(
                "Esta versión NO puede activar señal ni Wi‑Fi Pixel. Los FRRO antiguos con "
                        + "TYPE_REFERENCE 0x7e08 quedaron bloqueados. Su única función es consultar OMS "
                        + "y eliminar de forma verificada las identidades PixelStatus históricas de señal/Wi‑Fi.",
                14, false);
        warning.setAlpha(.82f);
        warning.setPadding(0, 0, 0, dp(18));
        root.addView(warning);

        rootStatus = text("ROOT: comprobando…", 14, true);
        rootStatus.setPadding(0, dp(6), 0, dp(8));
        root.addView(rootStatus);

        omsStatus = text("OMS: comprobando…", 14, false);
        omsStatus.setPadding(0, 0, 0, dp(18));
        root.addView(omsStatus);

        addBlockedElement(root, "Señal móvil Pixel");
        addBlockedElement(root, "Wi‑Fi Pixel");

        reconcileButton = new Button(this);
        reconcileButton.setText("Reconciliar / limpiar FRRO");
        reconcileButton.setAllCaps(false);
        reconcileButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        actionParams.setMargins(0, dp(22), 0, dp(8));
        root.addView(reconcileButton, actionParams);
        reconcileButton.setOnClickListener(v -> reconcileAsync("RECONCILIANDO…"));

        inspectButton = new Button(this);
        inspectButton.setText("Comprobar root / OMS");
        inspectButton.setAllCaps(false);
        inspectButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        root.addView(inspectButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        inspectButton.setOnClickListener(v -> inspectAsync());

        TextView note = text(
                "OFF SEGURO sólo se muestra cuando OMS confirma que no queda ninguna identidad allowlisted. "
                        + "Si la consulta, disable o unregister falla, la app conserva el estado como error/desconocido "
                        + "y no intenta ninguna reactivación.",
                13, false);
        note.setAlpha(.68f);
        note.setPadding(0, dp(22), 0, 0);
        root.addView(note);

        return scroll;
    }

    private void addBlockedElement(LinearLayout root, String label) {
        TextView name = text(label, 18, false);
        name.setPadding(0, dp(12), 0, 0);
        root.addView(name);

        TextView state = text("BLOQUEADO P0 · activación deshabilitada", 14, true);
        state.setAlpha(.68f);
        state.setPadding(0, dp(3), 0, dp(8));
        root.addView(state);
    }

    private void reconcileAsync(String pendingText) {
        setActionsEnabled(false);
        if (omsStatus != null) omsStatus.setText(pendingText);

        submitSafely(() -> RootOverlayController.reconcile(this), true);
    }

    private void inspectAsync() {
        setActionsEnabled(false);
        submitSafely(RootOverlayController::inspect, false);
    }

    private interface Operation {
        RootOverlayController.Result run();
    }

    private void submitSafely(Operation operation, boolean cleanupOperation) {
        worker.execute(() -> {
            RootOverlayController.Result result;
            try {
                result = operation.run();
            } catch (Throwable t) {
                result = new RootOverlayController.Result(
                        false,
                        RootOverlayController.OmsState.CLEANUP_FAILED,
                        "FALLO SEGURO · " + t.getClass().getSimpleName() + ": "
                                + String.valueOf(t.getMessage()));
            }

            RootOverlayController.RootInfo root = RootOverlayController.rootInfo();
            RootOverlayController.Result finalResult = result;
            runOnUiThread(() -> {
                if (isDestroyed()) return;

                rootStatus.setText(root.ok ? root.message : root.message);
                String prefix = cleanupOperation ? "LIMPIEZA: " : "OMS: ";
                omsStatus.setText(prefix + finalResult.message);
                setActionsEnabled(true);
            });
        });
    }

    private void setActionsEnabled(boolean enabled) {
        if (reconcileButton != null) reconcileButton.setEnabled(enabled);
        if (inspectButton != null) inspectButton.setEnabled(enabled);
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
