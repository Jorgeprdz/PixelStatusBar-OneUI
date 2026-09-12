package com.jorge.pixelstatusbar.oneui;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class RootOverlayController {
    static final String OVERLAY_ID = "com.android.shell:PixelStatus";
    private static final String TMP = "/data/local/tmp/pixel_status_bar";

    static final class Result {
        final boolean ok;
        final String message;
        Result(boolean ok, String message) { this.ok = ok; this.message = message; }
    }

    private static final class ExecResult {
        final int code; final String out;
        ExecResult(int code, String out) { this.code = code; this.out = out; }
    }

    static Result enable(Context context) {
        try {
            ExecResult root = su("id");
            if (root.code != 0 || !root.out.contains("uid=0")) return new Result(false, "No obtuve root. Autoriza la app en tu gestor de root.");

            ExecResult help = su("cmd overlay help 2>&1 | grep -q fabricate");
            if (help.code != 0) return new Result(false, "Este build no expone cmd overlay fabricate.");

            File work = new File(context.getCacheDir(), "frro");
            if (!work.exists() && !work.mkdirs()) return new Result(false, "No pude preparar archivos temporales.");

            List<String> names = new ArrayList<>();
            for (int i = 0; i <= 4; i++) {
                write(new File(work, "wifi_" + i + ".xml"), wifiVector(i));
                names.add("wifi_" + i + ".xml");
                write(new File(work, "mobile4_" + i + ".xml"), mobileVector(i, 4));
                names.add("mobile4_" + i + ".xml");
            }
            for (int i = 0; i <= 5; i++) {
                write(new File(work, "mobile5_" + i + ".xml"), mobileVector(i, 5));
                names.add("mobile5_" + i + ".xml");
            }

            StringBuilder map = new StringBuilder("<overlay>\n");
            for (int i = 0; i <= 4; i++) {
                map.append("  <item target=\"drawable/stat_sys_wifi_signal_").append(i).append("\" value=\"").append(TMP).append("/wifi_").append(i).append(".xml\"/>\n");
                map.append("  <item target=\"drawable/stat_sys_signal_").append(i).append("\" value=\"").append(TMP).append("/mobile4_").append(i).append(".xml\"/>\n");
            }
            for (int i = 0; i <= 5; i++) {
                map.append("  <item target=\"drawable/stat_sys_signal_5level_").append(i).append("\" value=\"").append(TMP).append("/mobile5_").append(i).append(".xml\"/>\n");
            }
            map.append("</overlay>\n");
            write(new File(work, "overlay.xml"), map.toString());
            names.add("overlay.xml");

            StringBuilder prep = new StringBuilder("rm -rf '").append(TMP).append("'; mkdir -p '").append(TMP).append("'; ");
            for (String name : names) {
                prep.append("cp '").append(new File(work, name).getAbsolutePath()).append("' '").append(TMP).append('/').append(name).append("'; ");
            }
            prep.append("chmod 755 '").append(TMP).append("'; chmod 644 '").append(TMP).append("'/*");
            ExecResult copied = su(prep.toString());
            if (copied.code != 0) return new Result(false, "Root no pudo preparar los drawables temporales.");

            su("cmd overlay disable --user 0 '" + OVERLAY_ID + "' >/dev/null 2>&1 || true");
            ExecResult fab = su("cmd overlay fabricate --target com.android.systemui --name PixelStatus --file '" + TMP + "/overlay.xml' 2>&1");
            if (fab.code != 0) {
                su("cmd overlay disable --user 0 '" + OVERLAY_ID + "' >/dev/null 2>&1 || true");
                return new Result(false, "SystemUI rechazó el overlay: " + oneLine(fab.out));
            }

            ExecResult en = su("cmd overlay enable --user 0 '" + OVERLAY_ID + "' 2>&1");
            if (en.code != 0 || !isEnabled()) {
                su("cmd overlay disable --user 0 '" + OVERLAY_ID + "' >/dev/null 2>&1 || true");
                return new Result(false, "No pude activar el overlay: " + oneLine(en.out));
            }
            return new Result(true, "ON · Pixel Wi‑Fi y señal activos. Sin reinicio.");
        } catch (Throwable t) {
            try { su("cmd overlay disable --user 0 '" + OVERLAY_ID + "' >/dev/null 2>&1 || true"); } catch (Throwable ignored) {}
            return new Result(false, "Falló de forma segura: " + t.getClass().getSimpleName());
        }
    }

    static Result disable() {
        ExecResult r = su("cmd overlay disable --user 0 '" + OVERLAY_ID + "' 2>&1");
        if (r.code == 0 && !isEnabled()) return new Result(true, "OFF · iconos Samsung restaurados.");
        return new Result(false, "No pude desactivar el overlay. Si perdiste root, reiniciar lo elimina automáticamente.");
    }

    static boolean isEnabled() {
        ExecResult r = su("cmd overlay list --user 0 com.android.systemui 2>/dev/null | grep -F '" + OVERLAY_ID + "'");
        return r.code == 0 && r.out.contains("[x]");
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
            return new ExecResult(127, t.getClass().getSimpleName());
        }
    }

    private static void write(File file, String text) throws Exception {
        try (FileWriter w = new FileWriter(file, StandardCharsets.UTF_8)) { w.write(text); }
    }

    private static String oneLine(String s) {
        if (s == null || s.isBlank()) return "sin detalle";
        String x = s.replace('\n', ' ').replace('\r', ' ').trim();
        return x.length() > 120 ? x.substring(0, 120) : x;
    }

    private static String wifiVector(int level) {
        float[] a = new float[] {0.24f, 0.24f, 0.24f, 0.24f};
        if (level >= 1) a[3] = 1f;
        if (level >= 2) a[2] = 1f;
        if (level >= 3) a[1] = 1f;
        if (level >= 4) a[0] = 1f;
        return "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" android:width=\"18dp\" android:height=\"13dp\" android:viewportWidth=\"18\" android:viewportHeight=\"13\">" +
                path("M0.523,3.314C0.32,3.502 0.32,3.819 0.516,4.015L1.223,4.722C1.418,4.917 1.734,4.916 1.938,4.73C5.936,1.09 12.066,1.09 16.064,4.73C16.268,4.916 16.584,4.917 16.779,4.722L17.486,4.015C17.682,3.819 17.682,3.502 17.479,3.314C12.698,-1.105 5.304,-1.105 0.523,3.314Z", a[0]) +
                path("M15.011,6.49C15.207,6.294 15.207,5.976 15.002,5.792C11.592,2.736 6.411,2.736 3,5.792C2.795,5.976 2.795,6.294 2.991,6.49L3.698,7.197C3.893,7.392 4.209,7.39 4.417,7.209C7.042,4.93 10.96,4.93 13.585,7.209C13.793,7.39 14.109,7.392 14.304,7.197L15.011,6.49Z", a[1]) +
                path("M5.465,8.964C5.27,8.769 5.269,8.45 5.481,8.273C7.515,6.576 10.487,6.576 12.521,8.273C12.733,8.45 12.732,8.769 12.537,8.964L11.83,9.672C11.634,9.867 11.319,9.863 11.099,9.698C9.859,8.767 8.143,8.767 6.904,9.698C6.683,9.863 6.368,9.867 6.173,9.672L5.465,8.964Z", a[2]) +
                path("M10.062,11.439C10.257,11.244 10.259,10.92 10.022,10.779C9.395,10.407 8.608,10.407 7.98,10.779C7.743,10.92 7.745,11.244 7.94,11.439L8.647,12.146C8.843,12.342 9.159,12.342 9.355,12.146L10.062,11.439Z", a[3]) + "</vector>";
    }

    private static String mobileVector(int level, int max) {
        int bars = max;
        float width = max == 5 ? 18f : 14f;
        StringBuilder s = new StringBuilder("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" android:width=\"").append((int)width).append("dp\" android:height=\"14dp\" android:viewportWidth=\"").append(width).append("\" android:viewportHeight=\"14\">");
        for (int i = 0; i < bars; i++) {
            float gap = max == 5 ? 3.5f : 4f;
            float x = i * gap;
            float h = 3f + i * (11f / (bars - 1));
            float y = 14f - h;
            float alpha = i < level ? 1f : 0.30f;
            s.append("<path android:pathData=\"M").append(x).append(',').append(y).append(" h2 v").append(h).append(" h-2 z\" android:fillColor=\"#FFFFFFFF\" android:fillAlpha=\"").append(alpha).append("\"/>");
        }
        return s.append("</vector>").toString();
    }

    private static String path(String data, float alpha) {
        return "<path android:pathData=\"" + data + "\" android:fillColor=\"#FFFFFFFF\" android:fillAlpha=\"" + alpha + "\"/>";
    }
}
