package com.jorge.pixelstatusbar.oneui;

import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class RootOverlayController {
    private static final String MOBILE_OVERLAY = "com.android.shell:PixelStatusNative";
    private static final String WIFI_OVERLAY = "com.android.shell:PixelStatusWifi";

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

    static Result enableMobile(Context context) {
        try {
            Result root = requireRoot();
            if (!root.ok) return root;

            disableLegacyExperiments();

            String apk = shellQuote(context.getApplicationInfo().sourceDir);
            ExecResult reg = su("CLASSPATH=" + apk + " app_process /system/bin "
                    + "com.jorge.pixelstatusbar.oneui.FrroHelper register 2>&1");
            if (reg.code != 0 || !reg.out.contains("REGISTERED")) {
                disableMobile();
                return new Result(false, "No pude registrar señal móvil:\n" + firstUseful(reg.out, 1000));
            }

            ExecResult en = su("cmd overlay enable --user 0 '" + MOBILE_OVERLAY + "' 2>&1");
            if (en.code != 0) {
                disableMobile();
                return new Result(false, "Samsung rechazó la señal móvil:\n" + firstUseful(en.out, 900));
            }
            su("cmd overlay set-priority '" + MOBILE_OVERLAY + "' highest >/dev/null 2>&1 || true");

            if (!isMobileEnabled()) {
                disableMobile();
                return new Result(false, "La señal móvil no quedó activa.");
            }
            return new Result(true, "ON · señal móvil Pixel activa.");
        } catch (Throwable t) {
            try { disableMobile(); } catch (Throwable ignored) {}
            return new Result(false, "Señal: fallo seguro: " + t.getClass().getSimpleName());
        }
    }

    static Result disableMobileResult() {
        disableMobile();
        return !isMobileEnabled()
                ? new Result(true, "OFF · señal móvil Samsung restaurada.")
                : new Result(false, "No pude desactivar la señal móvil.");
    }

    static Result enableWifi(Context context) {
        String watchdogPid = "";
        try {
            Result root = requireRoot();
            if (!root.ok) return root;

            // Do not touch the proven mobile overlay. Wi-Fi lives in its own FRRO.
            disableWifi();

            String apk = shellQuote(context.getApplicationInfo().sourceDir);
            ExecResult reg = su("CLASSPATH=" + apk + " app_process /system/bin "
                    + "com.jorge.pixelstatusbar.oneui.WifiFrroHelper register 2>&1");
            if (reg.code != 0 || !reg.out.contains("REGISTERED")) {
                disableWifi();
                return new Result(false, "No pude registrar Wi‑Fi:\n" + firstUseful(reg.out, 1000));
            }

            // Independent fail-safe: if the app dies or SystemUI loops, this root shell
            // disables ONLY the Wi-Fi FRRO after 15 seconds. Nothing is installed at boot.
            ExecResult wd = su("(sleep 15; cmd overlay disable --user 0 '" + WIFI_OVERLAY
                    + "' >/dev/null 2>&1) >/dev/null 2>&1 & echo $!");
            watchdogPid = wd.out.trim();

            ExecResult en = su("cmd overlay enable --user 0 '" + WIFI_OVERLAY + "' 2>&1");
            if (en.code != 0) {
                disableWifi();
                cancelWatchdog(watchdogPid);
                return new Result(false, "Samsung rechazó Wi‑Fi:\n" + firstUseful(en.out, 900));
            }
            su("cmd overlay set-priority '" + WIFI_OVERLAY + "' highest >/dev/null 2>&1 || true");

            if (!isWifiEnabled()) {
                disableWifi();
                cancelWatchdog(watchdogPid);
                return new Result(false, "Wi‑Fi no quedó activo; se restauró Samsung.");
            }

            // Let any resource/configuration reaction happen while the watchdog is armed.
            Thread.sleep(2200);
            String pid1 = systemUiPid();
            Thread.sleep(5200);
            String pid2 = systemUiPid();

            boolean stable = !pid1.isBlank() && pid1.equals(pid2) && isWifiEnabled();
            if (!stable) {
                // Keep the watchdog as a second safety net and disable immediately too.
                disableWifi();
                return new Result(false,
                        "Wi‑Fi provocó inestabilidad en SystemUI y se apagó automáticamente. "
                                + "Señal móvil no fue tocada.");
            }

            cancelWatchdog(watchdogPid);
            return new Result(true,
                    "ON · Wi‑Fi Pixel activo y SystemUI estable. Watchdog superado.");
        } catch (InterruptedException e) {
            disableWifi();
            return new Result(false, "Prueba Wi‑Fi interrumpida; overlay apagado.");
        } catch (Throwable t) {
            try { disableWifi(); } catch (Throwable ignored) {}
            return new Result(false, "Wi‑Fi: fallo seguro: " + t.getClass().getSimpleName()
                    + ": " + String.valueOf(t.getMessage()));
        }
    }

    static Result disableWifiResult() {
        disableWifi();
        return !isWifiEnabled()
                ? new Result(true, "OFF · Wi‑Fi Samsung restaurado.")
                : new Result(false, "No pude desactivar el Wi‑Fi FRRO.");
    }

    static boolean isMobileEnabled() { return isOverlayEnabled(MOBILE_OVERLAY); }
    static boolean isWifiEnabled() { return isOverlayEnabled(WIFI_OVERLAY); }

    private static Result requireRoot() {
        ExecResult root = su("id");
        if (root.code != 0 || !root.out.contains("uid=0")) {
            return new Result(false, "No obtuve root. Autoriza Pixel Status Bar en tu gestor de root.");
        }
        return new Result(true, "root");
    }

    private static boolean isOverlayEnabled(String overlay) {
        ExecResult r = su("cmd overlay list --user 0 com.android.systemui 2>/dev/null "
                + "| grep -F '[x] " + overlay + "' >/dev/null");
        return r.code == 0;
    }

    private static void disableMobile() {
        su("cmd overlay disable --user 0 '" + MOBILE_OVERLAY + "' >/dev/null 2>&1 || true");
    }

    private static void disableWifi() {
        su("cmd overlay disable --user 0 '" + WIFI_OVERLAY + "' >/dev/null 2>&1 || true");
    }

    private static String systemUiPid() {
        ExecResult r = su("pidof com.android.systemui 2>/dev/null | tr -d '\\r\\n'");
        return r.code == 0 ? r.out.trim() : "";
    }

    private static void cancelWatchdog(String pid) {
        if (pid != null && pid.matches("[0-9]+")) {
            su("kill " + pid + " >/dev/null 2>&1 || true");
        }
    }

    private static void disableLegacyExperiments() {
        // Clean only obsolete experiments. Never disable PixelStatusWifi here.
        su("for n in PSWifi0 PSWifi1 PSWifi2 PSWifi3 PSWifi4 "
                + "PSMobile4_0 PSMobile4_1 PSMobile4_2 PSMobile4_3 PSMobile4_4 "
                + "PSMobile5_0 PSMobile5_1 PSMobile5_2 PSMobile5_3 PSMobile5_4 PSMobile5_5 PixelStatus; do "
                + "cmd overlay disable --user 0 \"com.android.shell:$n\" >/dev/null 2>&1 || true; done");
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
