package com.jorge.pixelstatusbar.oneui;

import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class RootOverlayController {
    private static final String OVERLAY = "com.android.shell:PixelStatusNative";

    static final class Result {
        final boolean ok;
        final String message;
        Result(boolean ok, String message) { this.ok = ok; this.message = message; }
    }

    private static final class ExecResult {
        final int code;
        final String out;
        ExecResult(int code, String out) { this.code = code; this.out = out == null ? "" : out; }
    }

    static Result enable(Context context) {
        try {
            ExecResult root = su("id");
            if (root.code != 0 || !root.out.contains("uid=0")) {
                return new Result(false, "No obtuve root. Autoriza Pixel Status Bar en tu gestor de root.");
            }

            disableLegacy();

            String apk = shellQuote(context.getApplicationInfo().sourceDir);
            String helper = "CLASSPATH=" + apk + " app_process /system/bin "
                    + "com.jorge.pixelstatusbar.oneui.FrroHelper register 2>&1";
            ExecResult reg = su(helper);
            if (reg.code != 0 || !reg.out.contains("REGISTERED")) {
                disableNative();
                return new Result(false, "No pude registrar el FRRO nativo:\n" + firstUseful(reg.out, 1200));
            }

            ExecResult en = su("cmd overlay enable --user 0 '" + OVERLAY + "' 2>&1");
            if (en.code != 0) {
                disableNative();
                return new Result(false, "El FRRO se creó pero Samsung rechazó activarlo:\n" + firstUseful(en.out, 900));
            }

            su("cmd overlay set-priority '" + OVERLAY + "' highest >/dev/null 2>&1 || true");

            if (!isEnabled()) {
                String d = diagnostics();
                disableNative();
                return new Result(false, "El FRRO se registró, pero no quedó activo:\n" + d);
            }

            return new Result(true,
                    "ON · FRRO de referencias activo en memoria de overlays. "
                            + "Propietario shell: Android lo elimina al reiniciar.");
        } catch (Throwable t) {
            try { disableNative(); } catch (Throwable ignored) {}
            return new Result(false, "Falló de forma segura: " + t.getClass().getSimpleName()
                    + ": " + String.valueOf(t.getMessage()));
        }
    }

    static Result disable() {
        disableNative();
        disableLegacy();
        if (!isEnabled()) return new Result(true, "OFF · iconos Samsung restaurados.");
        return new Result(false,
                "No pude desactivar el FRRO. Si el root ya se perdió, el reinicio elimina los overlays de shell.");
    }

    static boolean isEnabled() {
        ExecResult r = su("cmd overlay list --user 0 com.android.systemui 2>/dev/null "
                + "| grep -F '[x] " + OVERLAY + "' >/dev/null");
        return r.code == 0;
    }

    private static void disableNative() {
        su("cmd overlay disable --user 0 '" + OVERLAY + "' >/dev/null 2>&1 || true");
    }

    private static void disableLegacy() {
        su("for n in PSWifi0 PSWifi1 PSWifi2 PSWifi3 PSWifi4 "
                + "PSMobile4_0 PSMobile4_1 PSMobile4_2 PSMobile4_3 PSMobile4_4 "
                + "PSMobile5_0 PSMobile5_1 PSMobile5_2 PSMobile5_3 PSMobile5_4 PSMobile5_5 PixelStatus; do "
                + "cmd overlay disable --user 0 \"com.android.shell:$n\" >/dev/null 2>&1 || true; done");
    }

    private static String diagnostics() {
        ExecResult d = su("logcat -d -t 300 2>&1 | grep -iE "
                + "'OverlayManager|idmap|Fabricated|PixelStatusNative|SecurityException|overlayable|policy' | tail -24");
        return firstUseful(d.out, 1200);
    }

    private static ExecResult su(String command) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new ExecResult(124, "timeout");
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new ExecResult(p.exitValue(), out.trim());
        } catch (Throwable t) {
            if (p != null) p.destroyForcibly();
            return new ExecResult(127, t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
        }
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String firstUseful(String s, int max) {
        if (s == null || s.isBlank()) return "sin detalle";
        String x = s.replace('\r', ' ').trim();
        return x.length() > max ? x.substring(0, max) : x;
    }
}
