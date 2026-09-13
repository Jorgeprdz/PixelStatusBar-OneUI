package com.jorge.pixelstatusbar.oneui;

import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** v0.16 SAFE CORE: mobile + one Wi-Fi FRRO, references only, no dimensions. */
final class RootOverlayController {
    static final String MOBILE = "mobile";
    static final String WIFI = "wifi";

    private static final String OWNER = "com.android.shell:";
    private static final String MOBILE_OVERLAY = OWNER + "PixelStatusNative";
    private static final String WIFI_DYNAMIC_PREFIX = OWNER + "PixelStatusWifiAll_";

    private static final String STATE_DIR = "/data/local/tmp/";

    static final class Result {
        final boolean ok;
        final String message;
        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private static final class ExecResult {
        final int code;
        final String out;
        ExecResult(int code, String out) {
            this.code = code;
            this.out = out == null ? "" : out;
        }
    }

    private static final class StabilityResult {
        final boolean stable;
        final int pidChanges;
        StabilityResult(boolean stable, int pidChanges) {
            this.stable = stable;
            this.pidChanges = pidChanges;
        }
    }

    static Result enable(Context context, String element) {
        if (MOBILE.equals(element)) return enableMobile(context);
        if (WIFI.equals(element)) return enableWifi(context);
        return new Result(false, "Elemento no soportado en SAFE CORE.");
    }

    private static Result enableMobile(Context context) {
        Result root = requireRoot();
        if (!root.ok) return root;

        String watchdogPid = "";
        try {
            if (!waitForSystemUiSettled()) {
                return new Result(false, "Señal móvil: SystemUI aún se estaba asentando; no activé nada.");
            }

            disableOverlay(MOBILE_OVERLAY);

            String apk = shellQuote(context.getApplicationInfo().sourceDir);
            ExecResult reg = su("CLASSPATH=" + apk + " app_process /system/bin "
                    + "com.jorge.pixelstatusbar.oneui.FrroHelper register 2>&1");
            if (reg.code != 0 || !reg.out.contains("REGISTERED")) {
                disableOverlay(MOBILE_OVERLAY);
                writeState(MOBILE, "AUTO_REVERTED");
                return new Result(false, "Señal móvil: no pude registrar FRRO:\n" + firstUseful(reg.out, 900));
            }

            String pidBefore = systemUiPid();
            if (pidBefore.isBlank()) {
                disableOverlay(MOBILE_OVERLAY);
                writeState(MOBILE, "AUTO_REVERTED");
                return new Result(false, "Señal móvil: SystemUI no estaba disponible.");
            }

            watchdogPid = armWatchdog(context, MOBILE, MOBILE_OVERLAY, null);
            writeState(MOBILE, "TESTING");

            ExecResult en = su("cmd overlay enable --user 0 '" + MOBILE_OVERLAY + "' 2>&1");
            if (en.code != 0 || !isOverlayEnabled(MOBILE_OVERLAY)) {
                cancelWatchdog(watchdogPid);
                disableOverlay(MOBILE_OVERLAY);
                writeState(MOBILE, "AUTO_REVERTED");
                return new Result(false, "Señal móvil: Samsung rechazó el overlay; restaurado.");
            }
            su("cmd overlay set-priority '" + MOBILE_OVERLAY + "' highest >/dev/null 2>&1 || true");

            StabilityResult stability = watchForCrashLoop(pidBefore, MOBILE_OVERLAY);
            if (!stability.stable) {
                cancelWatchdog(watchdogPid);
                disableOverlay(MOBILE_OVERLAY);
                writeState(MOBILE, "AUTO_REVERTED");
                return new Result(false, "Señal móvil: AUTO-REVERTIDO · detecté inestabilidad real (PID="
                        + stability.pidChanges + ").");
            }

            cancelWatchdog(watchdogPid);
            writeState(MOBILE, "STABLE");
            return new Result(true, "Señal móvil: ESTABLE · ON"
                    + (stability.pidChanges == 1 ? " · 1 recarga tolerada" : "") + ".");
        } catch (InterruptedException e) {
            cancelWatchdog(watchdogPid);
            disableOverlay(MOBILE_OVERLAY);
            writeState(MOBILE, "AUTO_REVERTED");
            Thread.currentThread().interrupt();
            return new Result(false, "Señal móvil: prueba interrumpida; Samsung restaurado.");
        }
    }

    private static Result enableWifi(Context context) {
        Result root = requireRoot();
        if (!root.ok) return root;

        String overlay = "";
        String simpleName = "";
        String watchdogPid = "";
        try {
            cleanupAllWifi(context);

            if (!waitForSystemUiSettled()) {
                writeState(WIFI, "AUTO_REVERTED");
                return new Result(false, "Wi‑Fi Pixel: SystemUI aún se estaba asentando; no activé nada.");
            }

            simpleName = "PixelStatusWifiAll_" + Long.toHexString(System.currentTimeMillis());
            overlay = OWNER + simpleName;

            String apk = shellQuote(context.getApplicationInfo().sourceDir);
            ExecResult reg = su("CLASSPATH=" + apk + " app_process /system/bin "
                    + "com.jorge.pixelstatusbar.oneui.ElementFrroHelper wifi_all '"
                    + simpleName + "' 2>&1");
            if (reg.code != 0 || !reg.out.contains("REGISTERED")) {
                cleanupOneWifi(context, overlay, simpleName);
                writeState(WIFI, "AUTO_REVERTED");
                return new Result(false, "Wi‑Fi Pixel: no pude registrar FRRO:\n" + firstUseful(reg.out, 900));
            }

            writeTrackedWifi(overlay);

            String pidBefore = systemUiPid();
            if (pidBefore.isBlank()) {
                cleanupOneWifi(context, overlay, simpleName);
                writeState(WIFI, "AUTO_REVERTED");
                return new Result(false, "Wi‑Fi Pixel: SystemUI no estaba disponible.");
            }

            watchdogPid = armWatchdog(context, WIFI, overlay, simpleName);
            writeState(WIFI, "TESTING");

            ExecResult en = su("cmd overlay enable --user 0 '" + overlay + "' 2>&1");
            if (en.code != 0 || !isOverlayEnabled(overlay)) {
                cancelWatchdog(watchdogPid);
                cleanupOneWifi(context, overlay, simpleName);
                writeState(WIFI, "AUTO_REVERTED");
                return new Result(false, "Wi‑Fi Pixel: Samsung rechazó el overlay; restaurado.");
            }
            su("cmd overlay set-priority '" + overlay + "' highest >/dev/null 2>&1 || true");

            StabilityResult stability = watchForCrashLoop(pidBefore, overlay);
            if (!stability.stable) {
                cancelWatchdog(watchdogPid);
                cleanupOneWifi(context, overlay, simpleName);
                writeState(WIFI, "AUTO_REVERTED");
                return new Result(false, "Wi‑Fi Pixel: AUTO-REVERTIDO · detecté inestabilidad real (PID="
                        + stability.pidChanges + ").");
            }

            cancelWatchdog(watchdogPid);
            writeState(WIFI, "STABLE");
            return new Result(true, "Wi‑Fi Pixel: ESTABLE · familias Samsung remapeadas"
                    + (stability.pidChanges == 1 ? " · 1 recarga tolerada" : "") + ".");
        } catch (InterruptedException e) {
            cancelWatchdog(watchdogPid);
            cleanupOneWifi(context, overlay, simpleName);
            writeState(WIFI, "AUTO_REVERTED");
            Thread.currentThread().interrupt();
            return new Result(false, "Wi‑Fi Pixel: prueba interrumpida; Samsung restaurado.");
        }
    }

    static Result disable(Context context, String element) {
        Result root = requireRoot();
        if (!root.ok) return root;

        if (MOBILE.equals(element)) {
            disableOverlay(MOBILE_OVERLAY);
            writeState(MOBILE, "OFF");
            return !isOverlayEnabled(MOBILE_OVERLAY)
                    ? new Result(true, "Señal móvil: OFF · Samsung restaurado.")
                    : new Result(false, "Señal móvil: no pude desactivar el FRRO.");
        }

        if (WIFI.equals(element)) {
            cleanupAllWifi(context);
            writeState(WIFI, "OFF");
            return !isEnabled(WIFI)
                    ? new Result(true, "Wi‑Fi Pixel: OFF · Samsung restaurado.")
                    : new Result(false, "Wi‑Fi Pixel: quedó algún FRRO activo.");
        }

        return new Result(false, "Elemento no soportado.");
    }

    static boolean isEnabled(String element) {
        if (MOBILE.equals(element)) return isOverlayEnabled(MOBILE_OVERLAY);
        if (WIFI.equals(element)) return anyWifiEnabled();
        return false;
    }

    static String state(String element) {
        String raw = su("cat '" + stateFile(element) + "' 2>/dev/null | head -1").out.trim();
        if (isEnabled(element)) return "STABLE".equals(raw) ? "ESTABLE" : "ON";
        if ("AUTO_REVERTED".equals(raw) || "TESTING".equals(raw)) return "AUTO-REVERTIDO";
        return "OFF";
    }

    /** Called once on app creation: v0.16 deliberately drops all stale v0.10-v0.15 Wi-Fi FRROs. */
    static void cleanupObsoleteWifi(Context context) {
        Result root = requireRoot();
        if (!root.ok) return;
        cleanupAllWifi(context);
        writeState(WIFI, "OFF");
    }

    static Result restoreAll(Context context) {
        Result root = requireRoot();
        if (!root.ok) return root;

        disableOverlay(MOBILE_OVERLAY);
        cleanupAllWifi(context);

        // Retired experiments from older builds: disable + unregister so they cannot linger.
        cleanupNamedOverlay(context, "PixelStatusGeometry");
        cleanupNamedOverlay(context, "PixelStatusBattery");
        cleanupNamedOverlay(context, "PixelStatusClock");

        writeState(MOBILE, "OFF");
        writeState(WIFI, "OFF");
        su("rm -f '" + trackedWifiFile() + "' >/dev/null 2>&1 || true");

        boolean allOff = !isEnabled(MOBILE) && !isEnabled(WIFI)
                && !isOverlayEnabled(OWNER + "PixelStatusGeometry")
                && !isOverlayEnabled(OWNER + "PixelStatusBattery")
                && !isOverlayEnabled(OWNER + "PixelStatusClock");
        return allOff
                ? new Result(true, "Todo Samsung restaurado.")
                : new Result(false, "Quedó algún overlay activo; no desinstales todavía.");
    }

    private static StabilityResult watchForCrashLoop(String pidBefore, String overlay)
            throws InterruptedException {
        String previous = pidBefore;
        int changes = 0;
        int blankStreak = 0;

        // Observe long enough to catch a loop, but do NOT reject one late, legitimate SystemUI reload.
        for (int i = 0; i < 14; i++) {
            Thread.sleep(1000);
            String current = systemUiPid();

            if (current.isBlank()) {
                blankStreak++;
                if (blankStreak >= 3) return new StabilityResult(false, changes + 1);
                continue;
            }

            blankStreak = 0;
            if (!current.equals(previous)) {
                changes++;
                previous = current;
                if (changes > 1) return new StabilityResult(false, changes);
            }

            if (!isOverlayEnabled(overlay)) return new StabilityResult(false, changes);
        }

        return new StabilityResult(changes <= 1 && isOverlayEnabled(overlay), changes);
    }

    private static boolean waitForSystemUiSettled() throws InterruptedException {
        String previous = "";
        int stable = 0;
        for (int i = 0; i < 7; i++) {
            String current = systemUiPid();
            if (!current.isBlank() && current.equals(previous)) {
                stable++;
                if (stable >= 2) return true;
            } else {
                stable = 0;
            }
            previous = current;
            Thread.sleep(600);
        }
        return false;
    }

    private static void cleanupAllWifi(Context context) {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(OWNER + "PixelStatusWifi");
        ids.add(OWNER + "PixelStatusWifiBase");
        ids.add(OWNER + "PixelStatusWifi6");
        ids.add(OWNER + "PixelStatusWifiAll");

        String tracked = readTrackedWifi();
        if (!tracked.isBlank()) ids.add(tracked);

        ExecResult list = su("cmd overlay list --user 0 com.android.systemui 2>/dev/null");
        if (list.code == 0) {
            for (String raw : list.out.split("\\n")) {
                String id = extractOverlayId(raw);
                if (id.isBlank()) continue;
                if (id.startsWith(OWNER + "PixelStatusWifiBase_")
                        || id.startsWith(OWNER + "PixelStatusWifi6_")
                        || id.startsWith(WIFI_DYNAMIC_PREFIX)) {
                    ids.add(id);
                }
            }
        }

        for (String id : ids) {
            disableOverlay(id);
            unregisterSimple(context, simpleNameFromId(id));
        }
        su("rm -f '" + trackedWifiFile() + "' >/dev/null 2>&1 || true");
    }

    private static boolean anyWifiEnabled() {
        ExecResult list = su("cmd overlay list --user 0 com.android.systemui 2>/dev/null");
        if (list.code != 0) return false;
        for (String raw : list.out.split("\\n")) {
            String line = raw.trim();
            if (!line.startsWith("[x]")) continue;
            String id = extractOverlayId(line);
            if (id.startsWith(OWNER + "PixelStatusWifi")) return true;
        }
        return false;
    }

    private static void cleanupOneWifi(Context context, String overlay, String simpleName) {
        if (overlay != null && !overlay.isBlank()) disableOverlay(overlay);
        if (simpleName != null && !simpleName.isBlank()) unregisterSimple(context, simpleName);
        su("rm -f '" + trackedWifiFile() + "' >/dev/null 2>&1 || true");
    }

    private static void cleanupNamedOverlay(Context context, String simpleName) {
        String id = OWNER + simpleName;
        disableOverlay(id);
        unregisterSimple(context, simpleName);
    }

    private static String armWatchdog(Context context, String element, String overlay, String simpleName) {
        StringBuilder body = new StringBuilder();
        body.append("sleep 30; cmd overlay disable --user 0 '").append(overlay)
                .append("' >/dev/null 2>&1; ");
        if (simpleName != null && !simpleName.isBlank()) {
            String apk = shellQuote(context.getApplicationInfo().sourceDir);
            body.append("CLASSPATH=").append(apk).append(" app_process /system/bin ")
                    .append("com.jorge.pixelstatusbar.oneui.ElementFrroHelper unregister '")
                    .append(simpleName).append("' >/dev/null 2>&1; ")
                    .append("rm -f '").append(trackedWifiFile()).append("'; ");
        }
        body.append("echo AUTO_REVERTED > '").append(stateFile(element)).append("'");
        return su("(" + body + ") >/dev/null 2>&1 & echo $!").out.trim();
    }

    private static void unregisterSimple(Context context, String simpleName) {
        if (simpleName == null || simpleName.isBlank()) return;
        String apk = shellQuote(context.getApplicationInfo().sourceDir);
        su("CLASSPATH=" + apk + " app_process /system/bin "
                + "com.jorge.pixelstatusbar.oneui.ElementFrroHelper unregister '"
                + simpleName + "' >/dev/null 2>&1 || true");
    }

    private static void writeTrackedWifi(String overlay) {
        su("printf '%s\\n' '" + overlay + "' > '" + trackedWifiFile() + "'");
    }

    private static String readTrackedWifi() {
        return su("cat '" + trackedWifiFile() + "' 2>/dev/null | head -1").out.trim();
    }

    private static String trackedWifiFile() {
        return STATE_DIR + "pixel_status_wifi.overlay";
    }

    private static String stateFile(String element) {
        return STATE_DIR + "pixel_status_" + element + ".state";
    }

    private static void writeState(String element, String state) {
        su("printf '%s\\n' '" + state + "' > '" + stateFile(element) + "' 2>/dev/null || true");
    }

    private static Result requireRoot() {
        ExecResult root = su("id");
        if (root.code != 0 || !root.out.contains("uid=0")) {
            return new Result(false, "No obtuve root. Autoriza Pixel Status Bar en KernelSU.");
        }
        return new Result(true, "root");
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

    private static String extractOverlayId(String raw) {
        String line = raw == null ? "" : raw.trim();
        int start = line.indexOf(OWNER);
        if (start < 0) return "";
        String id = line.substring(start).trim();
        int ws = firstWhitespace(id);
        return ws >= 0 ? id.substring(0, ws) : id;
    }

    private static int firstWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    private static String simpleNameFromId(String overlayId) {
        int i = overlayId.indexOf(':');
        return i >= 0 ? overlayId.substring(i + 1) : overlayId;
    }

    private static void cancelWatchdog(String pid) {
        if (pid != null && pid.matches("[0-9]+")) {
            su("kill " + pid + " >/dev/null 2>&1 || true");
        }
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
