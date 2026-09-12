package com.jorge.pixelstatusbar.oneui;

import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** v0.14: composite Wi-Fi, exact Pixel geometry and watchdog v3. */
final class RootOverlayController {
    static final String MOBILE = "mobile";
    static final String WIFI = "wifi";
    static final String WIFI_BASE = "wifi_base";
    static final String WIFI6 = "wifi6";
    static final String GEOMETRY = "geometry";
    static final String BATTERY = "battery";
    static final String CLOCK = "clock";

    private static final String OWNER = "com.android.shell:";
    private static final String MOBILE_OVERLAY = OWNER + "PixelStatusNative";
    private static final String OLD_WIFI_OVERLAY = OWNER + "PixelStatusWifi";
    private static final String LEGACY_WIFI_BASE = OWNER + "PixelStatusWifiBase";
    private static final String LEGACY_WIFI6 = OWNER + "PixelStatusWifi6";
    private static final String GEOMETRY_OVERLAY = OWNER + "PixelStatusGeometry";
    private static final String BATTERY_OVERLAY = OWNER + "PixelStatusBattery";
    private static final String CLOCK_OVERLAY = OWNER + "PixelStatusClock";

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

    private static final class StabilityResult {
        final boolean stable;
        final int pidChanges;
        final String finalPid;
        StabilityResult(boolean stable, int pidChanges, String finalPid) {
            this.stable = stable;
            this.pidChanges = pidChanges;
            this.finalPid = finalPid == null ? "" : finalPid;
        }
    }

    static Result enable(Context context, String element) {
        if (WIFI.equals(element)) return enableWifiGroup(context);

        String overlay = "";
        String simpleName = null;
        String watchdogPid = "";
        try {
            Result root = requireRoot();
            if (!root.ok) return root;

            String apk = shellQuote(context.getApplicationInfo().sourceDir);
            String helper;

            if (isWifiPrimitive(element)) {
                cleanupWifiElement(context, element);
                if (!waitForSystemUiStable()) {
                    writeState(element, "AUTO_REVERTED");
                    return new Result(false, label(element) + ": SystemUI aún no estaba estable; no activé nada.");
                }

                simpleName = uniqueWifiSimpleName(element);
                overlay = OWNER + simpleName;
                helper = "CLASSPATH=" + apk + " app_process /system/bin "
                        + "com.jorge.pixelstatusbar.oneui.ElementFrroHelper " + element + " '" + simpleName + "' 2>&1";
            } else {
                overlay = fixedOverlayFor(element);
                disableOverlay(overlay);

                // Avoid false AUTO-REVERTED when another switch just reloaded SystemUI.
                if (!waitForSystemUiStable()) {
                    writeState(element, "AUTO_REVERTED");
                    return new Result(false, label(element) + ": SystemUI aún estaba asentándose; vuelve a intentar.");
                }

                if (MOBILE.equals(element)) {
                    helper = "CLASSPATH=" + apk + " app_process /system/bin "
                            + "com.jorge.pixelstatusbar.oneui.FrroHelper register 2>&1";
                } else {
                    helper = "CLASSPATH=" + apk + " app_process /system/bin "
                            + "com.jorge.pixelstatusbar.oneui.ElementFrroHelper " + element + " 2>&1";
                }
            }

            ExecResult reg = su(helper);
            if (reg.code != 0 || !reg.out.contains("REGISTERED")) {
                safeCleanup(context, element, overlay, simpleName, watchdogPid);
                writeState(element, "AUTO_REVERTED");
                return new Result(false, label(element) + ": no pude registrar FRRO:\n" + firstUseful(reg.out, 1000));
            }

            if (isWifiPrimitive(element)) writeTrackedOverlay(element, overlay);

            String pidBefore = systemUiPidWithGrace();
            if (pidBefore.isBlank()) {
                safeCleanup(context, element, overlay, simpleName, watchdogPid);
                writeState(element, "AUTO_REVERTED");
                return new Result(false, label(element) + ": SystemUI no estaba disponible antes de probar.");
            }

            watchdogPid = armWatchdog(context, element, overlay, simpleName);
            writeState(element, "TESTING");

            ExecResult en = su("cmd overlay enable --user 0 '" + overlay + "' 2>&1");
            if (en.code != 0) {
                safeCleanup(context, element, overlay, simpleName, watchdogPid);
                writeState(element, "AUTO_REVERTED");
                return new Result(false, label(element) + ": Samsung rechazó activarlo:\n" + firstUseful(en.out, 900));
            }
            su("cmd overlay set-priority '" + overlay + "' highest >/dev/null 2>&1 || true");

            if (!isOverlayEnabled(overlay)) {
                safeCleanup(context, element, overlay, simpleName, watchdogPid);
                writeState(element, "AUTO_REVERTED");
                return new Result(false, label(element) + ": no quedó activo; Samsung restaurado.");
            }

            StabilityResult stability = watchSystemUi(pidBefore, overlay);
            if (!stability.stable) {
                safeCleanup(context, element, overlay, simpleName, watchdogPid);
                writeState(element, "AUTO_REVERTED");
                return new Result(false, label(element)
                        + ": AUTO-REVERTIDO · SystemUI no estabilizó (cambios PID="
                        + stability.pidChanges + ").");
            }

            cancelWatchdog(watchdogPid);
            writeState(element, "STABLE");
            String restartNote = stability.pidChanges == 1 ? " · 1 recarga tolerada" : "";
            return new Result(true, label(element) + ": ESTABLE · watchdog superado" + restartNote + ".");
        } catch (InterruptedException e) {
            safeCleanup(context, element, overlay, simpleName, watchdogPid);
            writeState(element, "AUTO_REVERTED");
            Thread.currentThread().interrupt();
            return new Result(false, label(element) + ": prueba interrumpida; restaurado.");
        } catch (Throwable t) {
            safeCleanup(context, element, overlay, simpleName, watchdogPid);
            writeState(element, "AUTO_REVERTED");
            return new Result(false, label(element) + ": fallo seguro: " + t.getClass().getSimpleName());
        }
    }

    private static Result enableWifiGroup(Context context) {
        Result root = requireRoot();
        if (!root.ok) return root;

        cleanupWifiElement(context, WIFI_BASE);
        cleanupWifiElement(context, WIFI6);
        writeState(WIFI, "TESTING");

        Result base = enable(context, WIFI_BASE);
        if (!base.ok) {
            disable(context, WIFI);
            writeState(WIFI, "AUTO_REVERTED");
            return new Result(false, "Wi‑Fi Pixel: falló la capa base · " + base.message);
        }

        Result six = enable(context, WIFI6);
        if (!six.ok) {
            disable(context, WIFI);
            writeState(WIFI, "AUTO_REVERTED");
            return new Result(false, "Wi‑Fi Pixel: falló la capa Wi‑Fi 6 · " + six.message);
        }

        writeState(WIFI, "STABLE");
        return new Result(true, "Wi‑Fi Pixel: ESTABLE · base + Wi‑Fi 6 activados.");
    }

    static Result disable(Context context, String element) {
        Result root = requireRoot();
        if (!root.ok) return root;

        if (WIFI.equals(element)) {
            cleanupWifiElement(context, WIFI_BASE);
            cleanupWifiElement(context, WIFI6);
            writeState(WIFI_BASE, "OFF");
            writeState(WIFI6, "OFF");
            writeState(WIFI, "OFF");
            boolean off = !isEnabled(WIFI_BASE) && !isEnabled(WIFI6);
            return off
                    ? new Result(true, "Wi‑Fi Pixel: OFF · Samsung restaurado.")
                    : new Result(false, "Wi‑Fi Pixel: quedó alguna capa activa.");
        }

        if (isWifiPrimitive(element)) {
            cleanupWifiElement(context, element);
        } else {
            disableOverlay(fixedOverlayFor(element));
        }

        boolean off = !isEnabled(element);
        writeState(element, off ? "OFF" : "ERROR");
        return off
                ? new Result(true, label(element) + ": OFF · Samsung restaurado.")
                : new Result(false, label(element) + ": no pude desactivar por completo el FRRO.");
    }

    static boolean isEnabled(String element) {
        if (WIFI.equals(element)) return isEnabled(WIFI_BASE) || isEnabled(WIFI6);
        if (isWifiPrimitive(element)) return wifiFamilyHasEnabled(element);
        return isOverlayEnabled(fixedOverlayFor(element));
    }

    static String state(String element) {
        if (WIFI.equals(element)) {
            boolean base = isEnabled(WIFI_BASE);
            boolean six = isEnabled(WIFI6);
            if (base && six) return "ESTABLE";
            if (base || six) return "PARCIAL";
            ExecResult g = su("cat '" + stateFile(WIFI) + "' 2>/dev/null | head -1");
            String gs = g.out.trim();
            if ("AUTO_REVERTED".equals(gs) || "TESTING".equals(gs)) return "AUTO-REVERTIDO";
            return "OFF";
        }

        ExecResult r = su("cat '" + stateFile(element) + "' 2>/dev/null | head -1");
        String s = r.out.trim();
        if (isEnabled(element)) return "STABLE".equals(s) ? "ESTABLE" : "ON";
        if ("AUTO_REVERTED".equals(s) || "TESTING".equals(s)) return "AUTO-REVERTIDO";
        return "OFF";
    }

    static void cleanupObsoleteWifi(Context context) {
        disableOverlay(OLD_WIFI_OVERLAY);
        unregisterSimple(context, "PixelStatusWifi");

        disableOverlay(LEGACY_WIFI_BASE);
        unregisterSimple(context, "PixelStatusWifiBase");
        disableOverlay(LEGACY_WIFI6);
        unregisterSimple(context, "PixelStatusWifi6");

        // Preserve the currently tracked dynamic FRROs; remove only untracked leftovers.
        sweepWifiFamily(context, WIFI_BASE, readTrackedOverlay(WIFI_BASE));
        sweepWifiFamily(context, WIFI6, readTrackedOverlay(WIFI6));
    }

    static Result restoreAll(Context context) {
        Result root = requireRoot();
        if (!root.ok) return root;

        disableOverlay(MOBILE_OVERLAY);
        cleanupWifiElement(context, WIFI_BASE);
        cleanupWifiElement(context, WIFI6);
        disableOverlay(GEOMETRY_OVERLAY);
        disableOverlay(BATTERY_OVERLAY);
        disableOverlay(CLOCK_OVERLAY);
        disableOverlay(OLD_WIFI_OVERLAY);
        unregisterSimple(context, "PixelStatusWifi");

        writeState(MOBILE, "OFF");
        writeState(WIFI, "OFF");
        writeState(WIFI_BASE, "OFF");
        writeState(WIFI6, "OFF");
        writeState(GEOMETRY, "OFF");
        writeState(BATTERY, "OFF");
        writeState(CLOCK, "OFF");

        boolean allOff = !isEnabled(MOBILE) && !isEnabled(WIFI)
                && !isEnabled(GEOMETRY) && !isEnabled(BATTERY) && !isEnabled(CLOCK);
        return allOff
                ? new Result(true, "Todo Samsung restaurado.")
                : new Result(false, "Quedó algún overlay activo; no desinstales todavía.");
    }

    private static StabilityResult watchSystemUi(String pidBefore, String overlay) throws InterruptedException {
        String previous = pidBefore;
        String finalPid = pidBefore;
        int changes = 0;
        int stableSamples = 0;

        // One real PID transition is allowed. A short no-PID window is retried before counting as failure.
        for (int i = 0; i < 7; i++) {
            Thread.sleep(i == 0 ? 1100 : 1350);
            String current = systemUiPidWithGrace();
            if (current.isBlank()) return new StabilityResult(false, changes + 1, "");

            if (!current.equals(previous)) {
                changes++;
                stableSamples = 0;
                previous = current;
            } else {
                stableSamples++;
            }
            finalPid = current;

            if (changes > 1) return new StabilityResult(false, changes, finalPid);
            if (!isOverlayEnabled(overlay)) return new StabilityResult(false, changes, finalPid);
        }

        boolean stable = changes <= 1 && stableSamples >= 2 && isOverlayEnabled(overlay);
        return new StabilityResult(stable, changes, finalPid);
    }

    private static boolean waitForSystemUiStable() throws InterruptedException {
        String p0 = systemUiPidWithGrace();
        if (p0.isBlank()) return false;
        Thread.sleep(850);
        String p1 = systemUiPidWithGrace();
        Thread.sleep(850);
        String p2 = systemUiPidWithGrace();
        return p0.equals(p1) && p0.equals(p2) && !p2.isBlank();
    }

    private static String systemUiPidWithGrace() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            String pid = systemUiPid();
            if (!pid.isBlank()) return pid;
            if (i < 2) Thread.sleep(450);
        }
        return "";
    }

    private static void cleanupWifiElement(Context context, String element) {
        String tracked = readTrackedOverlay(element);
        if (!tracked.isBlank()) {
            disableOverlay(tracked);
            unregisterSimple(context, simpleNameFromId(tracked));
        }
        sweepWifiFamily(context, element, null);
        disableOverlay(OLD_WIFI_OVERLAY);
        unregisterSimple(context, "PixelStatusWifi");
        clearTrackedOverlay(element);
    }

    private static void sweepWifiFamily(Context context, String element, String keepId) {
        String dynamicPrefix = WIFI_BASE.equals(element) ? OWNER + "PixelStatusWifiBase_" : OWNER + "PixelStatusWifi6_";
        String legacy = WIFI_BASE.equals(element) ? LEGACY_WIFI_BASE : LEGACY_WIFI6;

        Set<String> ids = new LinkedHashSet<>();
        ids.add(legacy);
        ExecResult list = su("cmd overlay list --user 0 com.android.systemui 2>/dev/null");
        if (list.code == 0) {
            for (String raw : list.out.split("\\n")) {
                String line = raw.trim();
                int start = line.indexOf(OWNER);
                if (start < 0) continue;
                String id = line.substring(start).trim();
                int ws = firstWhitespace(id);
                if (ws >= 0) id = id.substring(0, ws);
                if (id.startsWith(dynamicPrefix)) ids.add(id);
            }
        }

        for (String id : ids) {
            if (keepId != null && keepId.equals(id)) continue;
            disableOverlay(id);
            unregisterSimple(context, simpleNameFromId(id));
        }
    }

    private static boolean wifiFamilyHasEnabled(String element) {
        String dynamicPrefix = WIFI_BASE.equals(element) ? OWNER + "PixelStatusWifiBase_" : OWNER + "PixelStatusWifi6_";
        String legacy = WIFI_BASE.equals(element) ? LEGACY_WIFI_BASE : LEGACY_WIFI6;
        ExecResult list = su("cmd overlay list --user 0 com.android.systemui 2>/dev/null");
        if (list.code != 0) return false;

        for (String raw : list.out.split("\\n")) {
            String line = raw.trim();
            if (!line.startsWith("[x]")) continue;
            if (line.contains(legacy) || line.contains(dynamicPrefix)) return true;
        }
        return false;
    }

    private static int firstWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    private static String uniqueWifiSimpleName(String element) {
        String prefix = WIFI_BASE.equals(element) ? "PixelStatusWifiBase_" : "PixelStatusWifi6_";
        return prefix + Long.toHexString(System.currentTimeMillis());
    }

    private static String armWatchdog(Context context, String element, String overlay, String simpleName) {
        String state = stateFile(element);
        StringBuilder body = new StringBuilder();
        body.append("sleep 22; cmd overlay disable --user 0 '").append(overlay).append("' >/dev/null 2>&1; ");
        if (simpleName != null) {
            String apk = shellQuote(context.getApplicationInfo().sourceDir);
            body.append("CLASSPATH=").append(apk).append(" app_process /system/bin ")
                    .append("com.jorge.pixelstatusbar.oneui.ElementFrroHelper unregister '")
                    .append(simpleName).append("' >/dev/null 2>&1; ")
                    .append("rm -f '").append(overlayFile(element)).append("'; ");
        }
        body.append("echo AUTO_REVERTED > '").append(state).append("'");
        ExecResult wd = su("(" + body + ") >/dev/null 2>&1 & echo $!");
        return wd.out.trim();
    }

    private static void safeCleanup(Context context, String element, String overlay,
            String simpleName, String watchdogPid) {
        cancelWatchdog(watchdogPid);
        try { if (overlay != null && !overlay.isBlank()) disableOverlay(overlay); } catch (Throwable ignored) {}
        try { if (simpleName != null) unregisterSimple(context, simpleName); } catch (Throwable ignored) {}
        try { if (isWifiPrimitive(element)) sweepWifiFamily(context, element, null); } catch (Throwable ignored) {}
        clearTrackedOverlay(element);
    }

    private static void unregisterSimple(Context context, String simpleName) {
        if (simpleName == null || simpleName.isBlank()) return;
        String apk = shellQuote(context.getApplicationInfo().sourceDir);
        su("CLASSPATH=" + apk + " app_process /system/bin "
                + "com.jorge.pixelstatusbar.oneui.ElementFrroHelper unregister '" + simpleName
                + "' >/dev/null 2>&1 || true");
    }

    private static void writeTrackedOverlay(String element, String overlay) {
        su("printf '%s\\n' '" + overlay + "' > '" + overlayFile(element) + "'");
    }

    private static String readTrackedOverlay(String element) {
        ExecResult r = su("cat '" + overlayFile(element) + "' 2>/dev/null | head -1");
        return r.out.trim();
    }

    private static void clearTrackedOverlay(String element) {
        if (isWifiPrimitive(element)) su("rm -f '" + overlayFile(element) + "' >/dev/null 2>&1 || true");
    }

    private static String overlayFile(String element) {
        return "/data/local/tmp/pixel_status_" + element + ".overlay";
    }

    private static String stateFile(String element) {
        return "/data/local/tmp/pixel_status_" + element + ".state";
    }

    private static void writeState(String element, String state) {
        su("printf '%s\\n' '" + state + "' > '" + stateFile(element) + "' 2>/dev/null || true");
    }

    private static Result requireRoot() {
        ExecResult root = su("id");
        if (root.code != 0 || !root.out.contains("uid=0")) {
            return new Result(false, "No obtuve root. Autoriza Pixel Status Bar en tu gestor de root.");
        }
        return new Result(true, "root");
    }

    private static boolean isWifiPrimitive(String element) {
        return WIFI_BASE.equals(element) || WIFI6.equals(element);
    }

    private static String fixedOverlayFor(String element) {
        switch (element) {
            case MOBILE: return MOBILE_OVERLAY;
            case GEOMETRY: return GEOMETRY_OVERLAY;
            case BATTERY: return BATTERY_OVERLAY;
            case CLOCK: return CLOCK_OVERLAY;
            default: throw new IllegalArgumentException("no fixed overlay for " + element);
        }
    }

    private static String simpleNameFromId(String overlayId) {
        int i = overlayId.indexOf(':');
        return i >= 0 ? overlayId.substring(i + 1) : overlayId;
    }

    private static String label(String element) {
        switch (element) {
            case MOBILE: return "Señal móvil";
            case WIFI: return "Wi‑Fi Pixel";
            case WIFI_BASE: return "Wi‑Fi base";
            case WIFI6: return "Wi‑Fi 6";
            case GEOMETRY: return "Geometría Pixel";
            case BATTERY: return "Batería";
            case CLOCK: return "Reloj";
            default: return element;
        }
    }

    private static boolean isOverlayEnabled(String overlay) {
        if (overlay == null || overlay.isBlank()) return false;
        ExecResult r = su("cmd overlay list --user 0 com.android.systemui 2>/dev/null "
                + "| grep -F '[x] " + overlay + "' >/dev/null");
        return r.code == 0;
    }

    private static void disableOverlay(String overlay) {
        if (overlay == null || overlay.isBlank()) return;
        su("cmd overlay disable --user 0 '" + overlay + "' >/dev/null 2>&1 || true");
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
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
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
