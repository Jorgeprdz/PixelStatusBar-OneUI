package com.jorge.pixelstatusbar.oneui;

import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class RootOverlayController {
    private static final String[] IDS = {
            "PSWifi0", "PSWifi1", "PSWifi2", "PSWifi3", "PSWifi4",
            "PSMobile4_0", "PSMobile4_1", "PSMobile4_2", "PSMobile4_3", "PSMobile4_4",
            "PSMobile5_0", "PSMobile5_1", "PSMobile5_2", "PSMobile5_3", "PSMobile5_4", "PSMobile5_5"
    };

    // Existing drawables inside this exact SM-S931B SystemUI.apk. Using TYPE_REFERENCE
    // avoids Samsung's failing file-backed fabricated-overlay path entirely.
    private static final long[] WIFI_REFS = {
            0x7e080e33L, // ic_wifi_0
            0x7e080e35L, // ic_wifi_1
            0x7e080e37L, // ic_wifi_2
            0x7e080e39L, // ic_wifi_3
            0x7e080e39L  // strongest state -> ic_wifi_3
    };

    private static final long[] MOBILE4_REFS = {
            0x7e080c20L, // ic_mobile_0_4_bar
            0x7e080c24L, // ic_mobile_1_4_bar
            0x7e080c28L, // ic_mobile_2_4_bar
            0x7e080c2cL, // ic_mobile_3_4_bar
            0x7e080c30L  // ic_mobile_4_4_bar
    };

    private static final long[] MOBILE5_REFS = {
            0x7e080c22L, // ic_mobile_0_5_bar
            0x7e080c26L, // ic_mobile_1_5_bar
            0x7e080c2aL, // ic_mobile_2_5_bar
            0x7e080c2eL, // ic_mobile_3_5_bar
            0x7e080c32L, // ic_mobile_4_5_bar
            0x7e080c34L  // ic_mobile_5_5_bar
    };

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

    static Result enable(Context ignored) {
        try {
            ExecResult root = su("id");
            if (root.code != 0 || !root.out.contains("uid=0")) {
                return new Result(false, "No obtuve root. Autoriza Pixel Status Bar en tu gestor de root.");
            }

            ExecResult help = su("cmd overlay help 2>&1 | grep -q 'fabricate'");
            if (help.code != 0) {
                return new Result(false, "Este build no expone cmd overlay fabricate.");
            }

            disableAll();

            int id = 0;
            for (int i = 0; i <= 4; i++) {
                Result r = fabricateReference(
                        IDS[id++],
                        "stat_sys_wifi_signal_" + i,
                        WIFI_REFS[i]);
                if (!r.ok) { disableAll(); return r; }
            }
            for (int i = 0; i <= 4; i++) {
                Result r = fabricateReference(
                        IDS[id++],
                        "stat_sys_signal_" + i,
                        MOBILE4_REFS[i]);
                if (!r.ok) { disableAll(); return r; }
            }
            for (int i = 0; i <= 5; i++) {
                Result r = fabricateReference(
                        IDS[id++],
                        "stat_sys_signal_5level_" + i,
                        MOBILE5_REFS[i]);
                if (!r.ok) { disableAll(); return r; }
            }

            // Nothing is enabled until every reference FRRO has been created successfully.
            for (String name : IDS) {
                String overlay = "com.android.shell:" + name;
                ExecResult en = su("cmd overlay enable --user 0 '" + overlay + "' 2>&1");
                if (en.code != 0) {
                    String detail = diagnostic(en);
                    disableAll();
                    return new Result(false, "Samsung creó los FRRO, pero rechazó activar " + name + ":\n" + detail);
                }
                su("cmd overlay set-priority '" + overlay + "' highest >/dev/null 2>&1 || true");
            }

            if (!isEnabled()) {
                String d = overlayDiagnostics();
                disableAll();
                return new Result(false, "Los FRRO se crearon, pero no quedaron activos:\n" + d);
            }

            return new Result(true, "ON · Wi‑Fi y señal sustituidos por referencias internas. Sin archivos, sin reinicio.");
        } catch (Throwable t) {
            try { disableAll(); } catch (Throwable ignored2) {}
            return new Result(false, "Falló de forma segura: " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
        }
    }

    static Result disable() {
        disableAll();
        if (!isEnabled()) return new Result(true, "OFF · iconos Samsung restaurados.");
        return new Result(false, "No pude desactivar todos los FRRO. Si el root ya se perdió, el reinicio los elimina.");
    }

    static boolean isEnabled() {
        ExecResult r = su("cmd overlay list --user 0 com.android.systemui 2>/dev/null | grep -E '\\[x\\] com.android.shell:PS(Wifi|Mobile)' | wc -l");
        if (r.code != 0) return false;
        try { return Integer.parseInt(r.out.trim()) == IDS.length; }
        catch (Throwable ignored) { return false; }
    }

    private static Result fabricateReference(String name, String resource, long referenceId) {
        String ref = String.format("0x%08x", referenceId);
        String cmd = "cmd overlay fabricate --target com.android.systemui " +
                "--name '" + name + "' " +
                "com.android.systemui:drawable/" + resource + " 0x01 " + ref + " 2>&1";
        ExecResult r = su(cmd);
        if (r.code == 0) return new Result(true, "ok");

        String direct = firstUseful(r.out, 900);
        if (direct.equals("sin detalle")) direct = overlayDiagnostics();
        return new Result(false,
                "Samsung rechazó la referencia para " + resource + " → " + ref + ":\n" + direct);
    }

    private static void disableAll() {
        for (String name : IDS) {
            su("cmd overlay disable --user 0 'com.android.shell:" + name + "' >/dev/null 2>&1 || true");
        }
        // Neutralize the older single-overlay experiment too, if it exists.
        su("cmd overlay disable --user 0 'com.android.shell:PixelStatus' >/dev/null 2>&1 || true");
    }

    private static String diagnostic(ExecResult r) {
        String direct = firstUseful(r.out, 800);
        if (!direct.equals("sin detalle")) return direct;
        String logs = overlayDiagnostics();
        return logs.isBlank() ? "exit=" + r.code + " sin detalle" : logs;
    }

    private static String overlayDiagnostics() {
        ExecResult d = su("logcat -d -t 300 2>&1 | grep -iE 'OverlayManager|idmap|Fabricated|PSWifi|PSMobile|SecurityException|overlayable|policy' | tail -30");
        return firstUseful(d.out, 1200);
    }

    private static ExecResult su(String command) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            if (!p.waitFor(12, TimeUnit.SECONDS)) {
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

    private static String firstUseful(String s, int max) {
        if (s == null || s.isBlank()) return "sin detalle";
        String x = s.replace('\r', ' ').trim();
        return x.length() > max ? x.substring(0, max) : x;
    }
}
