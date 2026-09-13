package com.jorge.pixelstatusbar.oneui;

import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** v0.17: truthful root/FRRO state. References only; no dimensions. */
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

    static final class RootInfo {
        final boolean ok;
        final String message;
        RootInfo(boolean ok, String message) {
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

    private static final class HealthResult {
        final boolean healthy;
        final int pidChanges;
        HealthResult(boolean healthy, int pidChanges) {
            this.healthy = healthy;
            this.pidChanges = pidChanges;
        }
    }

    static RootInfo rootInfo() {
        ExecResult r = su("id");
        int appUid = android.os.Process.myUid();
        if (r.code == 0 && r.out.contains("uid=0")) {
            return new RootInfo(true, "ROOT OK · app UID " + appUid + " · " + firstUseful(r.out, 160));
        }
        String detail = firstUseful(r.out, 220);
        if (detail.isBlank()) detail = "su no devolvió salida";
        return new RootInfo(false, "SIN ROOT EN APP · UID " + appUid + " · " + detail);
    }

    static Result enable(Context context, String element) {
        if (MOBILE.equals(element)) return enableMobile(context);
        if (WIFI.equals(element)) return enableWifi(context);
        return new Result(false, "Elemento no soportado.");
    }

    private static Result enableMobile(Context context) {
        RootInfo root = rootInfo();
        if (!root.ok) return new Result(false, root.message);

        String watchdogPid = "";
        try {
            disableOverlay(MOBILE_OVERLAY);

            String apk = shellQuote(context.getApplicationInfo().sourceDir);
            ExecResult reg = su("CLASSPATH=" + apk + " app_process /system/bin "
                    + "com.jorge.pixelstatusbar.oneui.FrroHelper register 2>&1");
            if (reg.code != 0 || !reg.out.contains("REGISTERED")) {
                disableOverlay(MOBILE_OVERLAY);
                writeState(MOBILE, "ERROR");
                return new Result(false, "Señal móvil: no pude registrar FRRO · " + firstUseful(reg.out, 700));
            }

            String pidBefore = systemUiPid();
            if (pidBefore.isBlank()) {
                disableOverlay(MOBILE_OVERLAY);
                writeState(MOBILE, "ERROR");
                return new Result(false, "Señal móvil: SystemUI no estaba disponible.");
            }

            watchdogPid = armWatchdog(context, MOBILE, MOBILE_OVERLAY, null);
            writeState(MOBILE, "TESTING");

            ExecResult en = su("cmd overlay enable --user 0 '" + MOBILE_OVERLAY + "' 2>&1");
            if (en.code != 0 || !isOverlayEnabled(MOBILE_OVERLAY)) {
                cancelWatchdog(watchdogPid);
                disableOverlay(MOBILE_OVERLAY);
                writeState(MOBILE, "ERROR");
                return new Result(false, "Señal móvil: Samsung rechazó el FRRO; restaurado.");
            }
            su("cmd overlay set-priority '" + MOBILE_OVERLAY + "' highest >/dev/null 2>&1 || true");

            // Mobile references were already proven visually in v0.7. We only guard against an actual loop.
            HealthResult health = watchSystemUiHealth(pidBefore, MOBILE_OVERLAY, 3);
            if (!health.healthy) {
                cancelWatchdog(watchdogPid);
                disableOverlay(MOBILE_OVERLAY);
                writeState(MOBILE, "AUTO_REVERTED");
                return new Result(false, "Señal móvil: AUTO-REVERTIDO · loop real detectado (cambios PID="
                        + health.pidChanges + ").");
            }

            cancelWatchdog(watchdogPid);
            writeState(MOBILE, "ACTIVE");
            return new Result(true, "Señal móvil: FRRO ACTIVO · verifica el icono"
                    + (health.pidChanges > 0 ? " · recargas SystemUI=" + health.pidChanges : "") + ".");
        } catch (InterruptedException e) {
            cancelWatchdog(watchdogPid);
            disableOverlay(MOBILE_OVERLAY);
            writeState(MOBILE, "ERROR");
            Thread.currentThread().interrupt();
            return new Result(false, "Señal móvil: prueba interrumpida; Samsung restaurado.");
        }
    }

    private static Result enableWifi(Context context) {
        RootInfo root = rootInfo();
        if (!root.ok) return new Result(false, root.message);

        String overlay = "";
        String simpleName = "";
        String watchdogPid = "";
        try {
            cleanupAllWifi(context);
            Thread.sleep(800);

            simpleName = "PixelStatusWifiAll_" + Long.toHexString(System.currentTimeMillis());
            overlay = OWNER + simpleName;

            String apk = shellQuote(context.getApplicationInfo().sourceDir);
            ExecResult reg = su("CLASSPATH=" + apk + " app_process /system/bin "
                    + "com.jorge.pixelstatusbar.oneui.ElementFrroHelper wifi_all '"
                    + simpleName + "' 2>&1");
            if (reg.code != 0 || !reg.out.contains("REGISTERED")) {
                cleanupOneWifi(context, overlay, simpleName);
                writeState(WIFI, "ERROR");
                return new Result(false, "Wi‑Fi Pixel: no pude registrar FRRO · " + firstUseful(reg.out, 700));
            }

            writeTrackedWifi(overlay);
            String pidBefore = systemUiPid();
            if (pidBefore.isBlank()) {
                cleanupOneWifi(context, overlay, simpleName);
                writeState(WIFI, "ERROR");
                return new Result(false, "Wi‑Fi Pixel: SystemUI no estaba disponible.");
            }

            watchdogPid = armWatchdog(context, WIFI, overlay, simpleName);
            writeState(WIFI, "TESTING");

            ExecResult en = su("cmd overlay enable --user 0 '" + overlay + "' 2>&1");
            if (en.code != 0 || !isOverlayEnabled(overlay)) {
                cancelWatchdog(watchdogPid);
                cleanupOneWifi(context, overlay, simpleName);
                writeState(WIFI, "ERROR");
                return new Result(false, "Wi‑Fi Pixel: Samsung rechazó el FRRO; restaurado.");
            }
            su("cmd overlay set-priority '" + overlay + "' highest >/dev/null 2>&1 || true");

            HealthResult health = watchSystemUiHealth(pidBefore, overlay, 2);
            if (!health.healthy) {
                cancelWatchdog(watchdogPid);
                cleanupOneWifi(context, overlay, simpleName);
                writeState(WIFI, "AUTO_REVERTED");
                return new Result(false, "Wi‑Fi Pixel: AUTO-REVERTIDO · loop real detectado (cambios PID="
                        + health.pidChanges + ").");
            }

            cancelWatchdog(watchdogPid);
            writeState(WIFI, "ACTIVE");
            return new Result(true, "Wi‑Fi Pixel: FRRO ACTIVO · verifica el icono"
                    + (health.pidChanges > 0 ? " · recargas SystemUI=" + health.pidChanges : "") + ".");
        } catch (InterruptedException e) {
            cancelWatchdog(watchdogPid);
            cleanupOneWifi(context, overlay, simpleName);
            writeState(WIFI, "ERROR");
            Thread.currentThread().interrupt();
            return new Result(false, "Wi‑Fi Pixel: prueba interrumpida; Samsung restaurado.");
        }
    }

    static Result disable(Context context, String element) {
        RootInfo root = rootInfo();
        if (!root.ok) return new Result(false, root.message);

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
            return !anyWifiEnabled()
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
        RootInfo root = rootInfo();
        if (!root.ok) return "SIN_ROOT";
        if (isEnabled(element)) return "ACTIVO";
        String raw = su("cat '" + stateFile(element) + "' 2>/dev/null | head -1").out.trim();
        if ("AUTO_REVERTED".equals(raw)) return "AUTO-REVERTIDO";
        if ("ERROR".equals(raw)) return "ERROR";
        return "OFF";
    }

    static void cleanupObsoleteWifi(Context context) {
        if (!rootInfo().ok) return;
        cleanupAllWifi(context);
        writeState(WIFI, "OFF");
    }

    static Result restoreAll(Context context) {
        RootInfo root = rootInfo();
        if (!root.ok) return new Result(false, root.message);

        disableOverlay(MOBILE_OVERLAY);
        cleanupAllWifi(context);
        cleanupNamedOverlay(context, "PixelStatusGeometry");
        cleanupNamedOverlay(context, "PixelStatusBattery");
        cleanupNamedOverlay(context, "PixelStatusClock");

        writeState(MOBILE, "OFF");
        writeState(WIFI, "OFF");

        return !isOverlayEnabled(MOBILE_OVERLAY) && !anyWifiEnabled()
                ? new Result(true, "Todo Samsung restaurado.")
                : new Result(false, "Quedó algún overlay activo; no desinstales todavía.");
    }

    private static HealthResult watchSystemUiHealth(String pidBefore, String overlay, int maxPidChanges)
            throws InterruptedException {
        String previous = pidBefore;
        int changes = 0;
        int blankStreak = 0;

        for (int i = 0; i < 10; i++) {
            Thread.sleep(1000);
            String current = systemUiPid();
            if (current.isBlank()) {
                blankStreak++;
                if (blankStreak >= 4) return new HealthResult(false, changes);
                continue;
            }

            blankStreak = 0;
            if (!current.equals(previous)) {
                changes++;
                previous = current;
                if (changes > maxPidChanges) return new HealthResult(false, changes);
            }

            if (!isOverlayEnabled(overlay)) return new HealthResult(false, changes);
        }
        return new HealthResult(isOverlayEnabled(overlay), changes);
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
        disableOverlay(OWNER + simpleName);
        unregisterSimple(context, simpleName);
    }

    private static String armWatchdog(Context context, String element, String overlay, String simpleName) {
        StringBuilder body = new StringBuilder();
        body.append("sleep 32; cmd overlay disable --user 0 '").append(overlay)
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
        String[] bins = {"su", "/system/bin/su", "/system/xbin/su"};
        ExecResult best = new ExecResult(127, "su no disponible");
        for (String bin : bins) {
            java.lang.Process p = null;
            try {
                p = new ProcessBuilder(bin, "-c", command).redirectErrorStream(true).start();
                if (!p.waitFor(20, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    best = new ExecResult(124, "timeout usando " + bin);
                    continue;
                }
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                ExecResult r = new ExecResult(p.exitValue(), out);
                if (r.code == 0) return r;
                if (!out.isBlank()) best = r;
            } catch (Throwable t) {
                if (p != null) p.destroyForcibly();
                best = new ExecResult(127, t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
            }
        }
        return best;
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String firstUseful(String s, int max) {
        if (s == null || s.isBlank()) return "";
        String x = s.replace('\r', ' ').replace('\n', ' ').trim();
        return x.length() > max ? x.substring(0, max) : x;
    }
}
