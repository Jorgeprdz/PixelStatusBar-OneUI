package com.jorge.pixelstatusbar.oneui;

import android.content.Context;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * v0.18 RESCUE / FAIL-CLOSED.
 *
 * This controller cannot register or enable a PixelStatus FRRO. Its only job is
 * to query OMS and remove the exact historical mobile/Wi-Fi identities created
 * by this project, with verification after every mutation.
 */
final class RootOverlayController {
    static final String MOBILE = "mobile";
    static final String WIFI = "wifi";

    private static final String OWNER = "com.android.shell:";
    private static final ReentrantLock PROCESS_LOCK = new ReentrantLock();

    private static final String[] FIXED_NAMES = {
            "PixelStatusNative",
            "PixelStatusWifi",
            "PixelStatusWifiBase",
            "PixelStatusWifi6",
            "PixelStatusWifiAll"
    };

    enum OmsState {
        CLEAN,
        PRESENT_DISABLED,
        PRESENT_ENABLED,
        QUERY_FAILED,
        CLEANUP_FAILED
    }

    static final class Result {
        final boolean ok;
        final OmsState state;
        final String message;

        Result(boolean ok, OmsState state, String message) {
            this.ok = ok;
            this.state = state;
            this.message = message == null ? "" : message;
        }
    }

    static final class RootInfo {
        final boolean ok;
        final String message;

        RootInfo(boolean ok, String message) {
            this.ok = ok;
            this.message = message == null ? "" : message;
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

    private static final class OverlayList {
        final boolean ok;
        final Set<String> ids;
        final Set<String> enabled;
        final String error;

        OverlayList(boolean ok, Set<String> ids, Set<String> enabled, String error) {
            this.ok = ok;
            this.ids = ids;
            this.enabled = enabled;
            this.error = error == null ? "" : error;
        }
    }

    static RootInfo rootInfo() {
        ExecResult r = su("id");
        int appUid = android.os.Process.myUid();
        if (r.code == 0 && r.out.contains("uid=0")) {
            return new RootInfo(true,
                    "ROOT OK · app UID " + appUid + " · " + firstUseful(r.out, 180));
        }

        String detail = firstUseful(r.out, 220);
        if (detail.isBlank()) detail = "su no devolvió salida";
        return new RootInfo(false,
                "SIN ROOT EN APP · UID " + appUid + " · " + detail);
    }

    /**
     * P0 fail-closed entry point. Any attempted activation first reconciles OMS
     * and then returns blocked. There is no registration/enable path in v0.18.
     */
    static Result enable(Context context, String element) {
        Result cleanup = reconcile(context);
        if (!cleanup.ok) return cleanup;
        return new Result(false, OmsState.CLEAN,
                "ACTIVACIÓN BLOQUEADA · los FRRO antiguos usan TYPE_REFERENCE 0x7e08 no validado.");
    }

    /** Disabling either logical element means reconciling all known PixelStatus FRROs. */
    static Result disable(Context context, String element) {
        return reconcile(context);
    }

    static Result restoreAll(Context context) {
        return reconcile(context);
    }

    /** Read-only OMS inspection. No mutation. */
    static Result inspect() {
        RootInfo root = rootInfo();
        if (!root.ok) {
            return new Result(false, OmsState.QUERY_FAILED, root.message);
        }

        OverlayList list = listOwnedOverlays();
        if (!list.ok) {
            return new Result(false, OmsState.QUERY_FAILED,
                    "OMS NO COMPROBADO · " + firstUseful(list.error, 500));
        }
        if (list.ids.isEmpty()) {
            return new Result(true, OmsState.CLEAN, "OMS LIMPIO · no hay FRRO PixelStatus conocidos.");
        }
        if (!list.enabled.isEmpty()) {
            return new Result(false, OmsState.PRESENT_ENABLED,
                    "FRRO PRESENTE Y ACTIVO · " + list.enabled.iterator().next());
        }
        return new Result(false, OmsState.PRESENT_DISABLED,
                "FRRO PRESENTE PERO DESHABILITADO · " + list.ids.iterator().next());
    }

    /**
     * Disable -> verify -> unregister -> verify for every allowlisted historical
     * PixelStatus identity. OMS is the source of truth.
     */
    static Result reconcile(Context context) {
        RootInfo root = rootInfo();
        if (!root.ok) {
            return new Result(false, OmsState.QUERY_FAILED, root.message);
        }

        if (!PROCESS_LOCK.tryLock()) {
            return new Result(false, OmsState.QUERY_FAILED,
                    "OPERACIÓN EN CURSO · otra reconciliación FRRO ya tiene el lock.");
        }

        FileChannel channel = null;
        FileLock fileLock = null;
        try {
            channel = FileChannel.open(
                    context.getFileStreamPath("overlay-operation.lock").toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            fileLock = tryFileLock(channel);
            if (fileLock == null) {
                return new Result(false, OmsState.QUERY_FAILED,
                        "OPERACIÓN EN CURSO · otra instancia tiene el lock FRRO.");
            }

            return reconcileLocked(context);
        } catch (Throwable t) {
            return new Result(false, OmsState.CLEANUP_FAILED,
                    "FALLO SEGURO · " + t.getClass().getSimpleName() + ": "
                            + firstUseful(String.valueOf(t.getMessage()), 300));
        } finally {
            if (fileLock != null) {
                try {
                    fileLock.release();
                } catch (Throwable ignored) {
                    // Releasing a local lock cannot justify another OMS mutation.
                }
            }
            if (channel != null) {
                try {
                    channel.close();
                } catch (Throwable ignored) {
                    // Same: fail closed, do not mutate OMS from cleanup exceptions.
                }
            }
            PROCESS_LOCK.unlock();
        }
    }

    private static FileLock tryFileLock(FileChannel channel) throws java.io.IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException busy) {
            return null;
        }
    }

    private static Result reconcileLocked(Context context) {
        OverlayList before = listOwnedOverlays();
        if (!before.ok) {
            return new Result(false, OmsState.QUERY_FAILED,
                    "OMS NO COMPROBADO · no ejecuté ninguna mutación · "
                            + firstUseful(before.error, 500));
        }

        for (String id : before.ids) {
            Result cleaned = removeOne(context, id);
            if (!cleaned.ok) return cleaned;
        }

        OverlayList after = listOwnedOverlays();
        if (!after.ok) {
            return new Result(false, OmsState.QUERY_FAILED,
                    "Limpieza ejecutada, pero OMS no pudo verificarse · "
                            + firstUseful(after.error, 500));
        }
        if (!after.ids.isEmpty()) {
            return new Result(false, OmsState.CLEANUP_FAILED,
                    "LIMPIEZA INCOMPLETA · permanece " + after.ids.iterator().next());
        }

        ExecResult metadata = clearLegacyMetadata();
        if (metadata.code != 0) {
            return new Result(false, OmsState.CLEANUP_FAILED,
                    "OMS LIMPIO, pero metadata legacy no pudo borrarse · "
                            + firstUseful(metadata.out, 350));
        }

        return new Result(true, OmsState.CLEAN,
                "OFF SEGURO · todos los FRRO PixelStatus conocidos fueron eliminados de OMS.");
    }

    private static Result removeOne(Context context, String id) {
        OverlayList initial = listOwnedOverlays();
        if (!initial.ok) {
            return new Result(false, OmsState.QUERY_FAILED,
                    "OMS falló antes de limpiar " + id + " · " + firstUseful(initial.error, 350));
        }
        if (!initial.ids.contains(id)) {
            return new Result(true, OmsState.CLEAN, "Ya no existe " + id);
        }

        if (initial.enabled.contains(id)) {
            ExecResult disabled = su("cmd overlay disable --user 0 '" + id + "' 2>&1");
            if (disabled.code != 0) {
                return new Result(false, OmsState.CLEANUP_FAILED,
                        "Disable falló para " + id + " · " + firstUseful(disabled.out, 400));
            }

            OverlayList verifyDisabled = listOwnedOverlays();
            if (!verifyDisabled.ok) {
                return new Result(false, OmsState.QUERY_FAILED,
                        "OMS no pudo verificar disable de " + id + " · "
                                + firstUseful(verifyDisabled.error, 350));
            }
            if (verifyDisabled.enabled.contains(id)) {
                return new Result(false, OmsState.CLEANUP_FAILED,
                        "OMS NO CONFIRMÓ DISABLE · " + id);
            }
        }

        ExecResult unregister = unregisterSimple(context, simpleNameFromId(id));
        if (unregister.code != 0) {
            return new Result(false, OmsState.CLEANUP_FAILED,
                    "Unregister falló para " + id + " · " + firstUseful(unregister.out, 400));
        }

        OverlayList verifyRemoved = listOwnedOverlays();
        if (!verifyRemoved.ok) {
            return new Result(false, OmsState.QUERY_FAILED,
                    "OMS no pudo verificar unregister de " + id + " · "
                            + firstUseful(verifyRemoved.error, 350));
        }
        if (verifyRemoved.ids.contains(id)) {
            return new Result(false, OmsState.CLEANUP_FAILED,
                    "OMS NO CONFIRMÓ UNREGISTER · " + id);
        }

        return new Result(true, OmsState.CLEAN, "Eliminado " + id);
    }

    private static OverlayList listOwnedOverlays() {
        ExecResult r = su("cmd overlay list --user 0 com.android.systemui 2>&1");
        Set<String> ids = new LinkedHashSet<>();
        Set<String> enabled = new LinkedHashSet<>();

        if (r.code != 0) {
            return new OverlayList(false, ids, enabled, r.out);
        }

        for (String raw : r.out.split("\\n")) {
            String id = extractOverlayId(raw);
            if (!isCleanupCandidate(id)) continue;
            ids.add(id);
            if (raw.trim().startsWith("[x]")) enabled.add(id);
        }
        return new OverlayList(true, ids, enabled, "");
    }

    private static boolean isCleanupCandidate(String id) {
        if (id == null || !id.startsWith(OWNER)) return false;
        String name = simpleNameFromId(id);
        for (String fixed : FIXED_NAMES) {
            if (fixed.equals(name)) return true;
        }
        return name.matches("PixelStatusWifi(?:Base|6|All)_[0-9A-Fa-f]+");
    }

    private static ExecResult unregisterSimple(Context context, String simpleName) {
        if (simpleName == null || simpleName.isBlank()) {
            return new ExecResult(2, "nombre vacío");
        }
        if (!isCleanupCandidate(OWNER + simpleName)) {
            return new ExecResult(2, "identidad fuera de allowlist");
        }

        String apk = shellQuote(context.getApplicationInfo().sourceDir);
        return su("CLASSPATH=" + apk + " app_process /system/bin "
                + "com.jorge.pixelstatusbar.oneui.ElementFrroHelper unregister '"
                + simpleName + "' 2>&1");
    }

    private static ExecResult clearLegacyMetadata() {
        return su("rm -f "
                + "'/data/local/tmp/pixel_status_wifi.overlay' "
                + "'/data/local/tmp/pixel_status_mobile.state' "
                + "'/data/local/tmp/pixel_status_wifi.state' 2>&1");
    }

    private static String extractOverlayId(String raw) {
        String line = raw == null ? "" : raw.trim();
        int start = line.indexOf(OWNER);
        if (start < 0) return "";

        String id = line.substring(start).trim();
        for (int i = 0; i < id.length(); i++) {
            if (Character.isWhitespace(id.charAt(i))) return id.substring(0, i);
        }
        return id;
    }

    private static String simpleNameFromId(String overlayId) {
        int i = overlayId == null ? -1 : overlayId.indexOf(':');
        return i >= 0 ? overlayId.substring(i + 1) : overlayId;
    }

    private static ExecResult su(String command) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new ExecResult(124, "timeout");
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new ExecResult(p.exitValue(), out.trim());
        } catch (Throwable t) {
            if (p != null) p.destroyForcibly();
            return new ExecResult(127,
                    t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
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
