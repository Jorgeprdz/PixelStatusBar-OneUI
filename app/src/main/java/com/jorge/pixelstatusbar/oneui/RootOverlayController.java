package com.jorge.pixelstatusbar.oneui;

import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** v0.11: one independent FRRO + watchdog per status-bar element. */
final class RootOverlayController {
    static final String MOBILE = "mobile";
    static final String WIFI_BASE = "wifi_base";
    static final String WIFI6 = "wifi6";
    static final String BATTERY = "battery";
    static final String CLOCK = "clock";

    private static final String MOBILE_OVERLAY = "com.android.shell:PixelStatusNative";
    private static final String OLD_WIFI_OVERLAY = "com.android.shell:PixelStatusWifi";

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

    static Result enable(Context context, String element) {
        String overlay = overlayFor(element);
        String watchdogPid = "";
        try {
            Result root = requireRoot();
            if (!root.ok) return root;

            // v0.10 combined Wi-Fi overlay must never coexist with v0.11 split tests.
            if (WIFI_BASE.equals(element) || WIFI6.equals(element)) disableOldWifi();

            disableOverlay(overlay);

            String apk = shellQuote(context.getApplicationInfo().sourceDir);
            String helper;
            if (MOBILE.equals(element)) {
                helper = "CLASSPATH=" + apk + " app_process /system/bin "
                        + "com.jorge.pixelstatusbar.oneui.FrroHelper register 2>&1";
            } else {
                helper = "CLASSPATH=" + apk + " app_process /system/bin "
                        + "com.jorge.pixelstatusbar.oneui.ElementFrroHelper " + element + " 2>&1";
            }

            ExecResult reg = su(helper);
            if (reg.code != 0 || !reg.out.contains("REGISTERED")) {
                disableOverlay(overlay);
                writeState(element, "AUTO_REVERTED");
                return new Result(false, label(element) + ": no pude registrar FRRO:\n" + firstUseful(reg.out, 1000));
            }

            String pidBefore = systemUiPid();
            if (pidBefore.isBlank()) {
                disableOverlay(overlay);
                writeState(element, "AUTO_REVERTED");
                return new Result(false, label(element) + ": SystemUI no estaba estable antes de probar.");
            }

            watchdogPid = armWatchdog(element, overlay);
            writeState(element, "TESTING");

            ExecResult en = su("cmd overlay enable --user 0 '" + overlay + "' 2>&1");
            if (en.code != 0) {
                disableOverlay(overlay);
                cancelWatchdog(watchdogPid);
                writeState(element, "AUTO_REVERTED");
                return new Result(false, label(element) + ": Samsung rechazó activarlo:\n" + firstUseful(en.out, 900));
            }
            su("cmd overlay set-priority '" + overlay + "' highest >/dev/null 2>&1 || true");

            if (!isEnabled(element)) {
                disableOverlay(overlay);
                cancelWatchdog(watchdogPid);
                writeState(element, "AUTO_REVERTED");
                return new Result(false, label(element) + ": no quedó activo; se restauró Samsung.");
            }

            // Strict watchdog: even ONE SystemUI restart counts as unstable.
            Thread.sleep(1800);
            String pid1 = systemUiPid();
            Thread.sleep(4200);
            String pid2 = systemUiPid();

            boolean stable = pidBefore.equals(pid1) && pidBefore.equals(pid2) && isEnabled(element);
            if (!stable) {
                disableOverlay(overlay);
                writeState(element, "AUTO_REVERTED");
                return new Result(false, label(element) + ": AUTO-REVERTIDO; SystemUI cambió/reinició durante la prueba.");
            }

            cancelWatchdog(watchdogPid);
            writeState(element, "STABLE");
            return new Result(true, label(element) + ": ESTABLE · watchdog superado.");
        } catch (InterruptedException e) {
            disableOverlay(overlay);
            writeState(element, "AUTO_REVERTED");
            return new Result(false, label(element) + ": prueba interrumpida; restaurado.");
        } catch (Throwable t) {
            try { disableOverlay(overlay); } catch (Throwable ignored) {}
            writeState(element, "AUTO_REVERTED");
            return new Result(false, label(element) + ": fallo seguro: " + t.getClass().getSimpleName());
        }
    }

    static Result disable(String element) {
        String overlay = overlayFor(element);
        disableOverlay(overlay);
        if (WIFI_BASE.equals(element) || WIFI6.equals(element)) disableOldWifi();
        boolean off = !isEnabled(element);
        writeState(element, off ? "OFF" : "ERROR");
        return off
                ? new Result(true, label(element) + ": OFF · Samsung restaurado.")
                : new Result(false, label(element) + ": no pude desactivar el FRRO.");
    }

    static boolean isEnabled(String element) {
        return isOverlayEnabled(overlayFor(element));
    }

    static String state(String element) {
        ExecResult r = su("cat '/data/local/tmp/pixel_status_" + element + ".state' 2>/dev/null | head -1");
        String s = r.out.trim();
        if (isEnabled(element)) return "STABLE".equals(s) ? "ESTABLE" : "ON";
        if ("AUTO_REVERTED".equals(s)) return "AUTO-REVERTIDO";
        if ("TESTING".equals(s)) return "AUTO-REVERTIDO";
        return "OFF";
    }

    static void cleanupObsoleteWifi() { disableOldWifi(); }

    private static String armWatchdog(String element, String overlay) {
        String state = "/data/local/tmp/pixel_status_" + element + ".state";
        ExecResult wd = su("(sleep 15; cmd overlay disable --user 0 '" + overlay
                + "' >/dev/null 2>&1; echo AUTO_REVERTED > '" + state
                + "') >/dev/null 2>&1 & echo $!");
        return wd.out.trim();
    }

    private static void writeState(String element, String state) {
        su("printf '%s\\n' '" + state + "' > '/data/local/tmp/pixel_status_" + element + ".state' 2>/dev/null || true");
    }

    private static Result requireRoot() {
        ExecResult root = su("id");
        if (root.code != 0 || !root.out.contains("uid=0")) {
            return new Result(false, "No obtuve root. Autoriza Pixel Status Bar en tu gestor de root.");
        }
        return new Result(true, "root");
    }

    private static String overlayFor(String element) {
        switch (element) {
            case MOBILE: return MOBILE_OVERLAY;
            case WIFI_BASE: return "com.android.shell:PixelStatusWifiBase";
            case WIFI6: return "com.android.shell:PixelStatusWifi6";
            case BATTERY: return "com.android.shell:PixelStatusBattery";
            case CLOCK: return "com.android.shell:PixelStatusClock";
            default: throw new IllegalArgumentException("unknown element: " + element);
        }
    }

    private static String label(String element) {
        switch (element) {
            case MOBILE: return "Señal móvil";
            case WIFI_BASE: return "Wi‑Fi base";
            case WIFI6: return "Wi‑Fi 6";
            case BATTERY: return "Batería";
            case CLOCK: return "Reloj";
            default: return element;
        }
    }

    private static boolean isOverlayEnabled(String overlay) {
        ExecResult r = su("cmd overlay list --user 0 com.android.systemui 2>/dev/null "
                + "| grep -F '[x] " + overlay + "' >/dev/null");
        return r.code == 0;
    }

    private static void disableOverlay(String overlay) {
        su("cmd overlay disable --user 0 '" + overlay + "' >/dev/null 2>&1 || true");
    }

    private static void disableOldWifi() {
        su("cmd overlay disable --user 0 '" + OLD_WIFI_OVERLAY + "' >/dev/null 2>&1 || true");
    }

    private static String systemUiPid() {
        ExecResult r = su("pidof com.android.systemui 2>/dev/null | tr -d '\\r\\n'");
        return r.code == 0 ? r.out.trim() : "";
    }

    private static void cancelWatchdog(String pid) {
        if (pid != null && pid.matches("[0-9]+")) su("kill " + pid + " >/dev/null 2>&1 || true");
    }

    private static ExecResult su(String command) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            if (!p.waitFor(18, TimeUnit.SECONDS)) {
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
