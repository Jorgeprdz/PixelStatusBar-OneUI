package com.jorge.pixelstatusbar.oneui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class RootOverlayController {
    private static final String TMP = "/data/local/tmp/pixel_status_bar";

    private static final String[] IDS = {
            "PSWifi0", "PSWifi1", "PSWifi2", "PSWifi3", "PSWifi4",
            "PSMobile4_0", "PSMobile4_1", "PSMobile4_2", "PSMobile4_3", "PSMobile4_4",
            "PSMobile5_0", "PSMobile5_1", "PSMobile5_2", "PSMobile5_3", "PSMobile5_4", "PSMobile5_5"
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

    static Result enable(Context context) {
        try {
            ExecResult root = su("id");
            if (root.code != 0 || !root.out.contains("uid=0")) {
                return new Result(false, "No obtuve root. Autoriza Pixel Status Bar en tu gestor de root.");
            }

            ExecResult help = su("cmd overlay help 2>&1 | grep -q 'fabricate'");
            if (help.code != 0) {
                return new Result(false, "Este build no expone cmd overlay fabricate.");
            }

            File work = new File(context.getCacheDir(), "frro_png");
            if (!work.exists() && !work.mkdirs()) {
                return new Result(false, "No pude preparar los iconos temporales.");
            }

            List<File> files = new ArrayList<>();
            for (int i = 0; i <= 4; i++) {
                File f = new File(work, "wifi_" + i + ".png");
                writeWifiPng(f, i);
                files.add(f);
            }
            for (int i = 0; i <= 4; i++) {
                File f = new File(work, "mobile4_" + i + ".png");
                writeMobilePng(f, i, 4);
                files.add(f);
            }
            for (int i = 0; i <= 5; i++) {
                File f = new File(work, "mobile5_" + i + ".png");
                writeMobilePng(f, i, 5);
                files.add(f);
            }

            StringBuilder prep = new StringBuilder("rm -rf '").append(TMP)
                    .append("'; mkdir -p '").append(TMP).append("'; ");
            for (File f : files) {
                prep.append("cp '").append(f.getAbsolutePath()).append("' '")
                        .append(TMP).append('/').append(f.getName()).append("'; ");
            }
            prep.append("chmod 755 '").append(TMP).append("'; chmod 644 '").append(TMP).append("'/*.png");
            ExecResult copied = su(prep.toString());
            if (copied.code != 0) {
                return new Result(false, "Root no pudo preparar los PNG temporales: " + detail(copied));
            }

            disableAll();

            int id = 0;
            for (int i = 0; i <= 4; i++) {
                Result r = fabricateOne(IDS[id++], "stat_sys_wifi_signal_" + i, TMP + "/wifi_" + i + ".png");
                if (!r.ok) { disableAll(); return r; }
            }
            for (int i = 0; i <= 4; i++) {
                Result r = fabricateOne(IDS[id++], "stat_sys_signal_" + i, TMP + "/mobile4_" + i + ".png");
                if (!r.ok) { disableAll(); return r; }
            }
            for (int i = 0; i <= 5; i++) {
                Result r = fabricateOne(IDS[id++], "stat_sys_signal_5level_" + i, TMP + "/mobile5_" + i + ".png");
                if (!r.ok) { disableAll(); return r; }
            }

            for (String name : IDS) {
                String overlay = "com.android.shell:" + name;
                ExecResult en = su("cmd overlay enable --user 0 '" + overlay + "' 2>&1");
                if (en.code != 0) {
                    disableAll();
                    return new Result(false, "No pude activar " + name + ": " + diagnostic(en));
                }
                su("cmd overlay set-priority '" + overlay + "' highest >/dev/null 2>&1 || true");
            }

            if (!isEnabled()) {
                disableAll();
                return new Result(false, "Los FRRO se registraron pero SystemUI no los dejó activos. " + overlayDiagnostics());
            }

            return new Result(true, "ON · Pixel Wi‑Fi y señal activos. FRRO temporal, sin reinicio.");
        } catch (Throwable t) {
            try { disableAll(); } catch (Throwable ignored) {}
            return new Result(false, "Falló de forma segura: " + t.getClass().getSimpleName());
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

    private static Result fabricateOne(String name, String resource, String file) {
        String cmd = "cmd overlay fabricate --target com.android.systemui " +
                "--name '" + name + "' " +
                "com.android.systemui:drawable/" + resource + " drawable '" + file + "' 2>&1";
        ExecResult r = su(cmd);
        if (r.code == 0) return new Result(true, "ok");
        return new Result(false, "SystemUI rechazó " + resource + ": " + diagnostic(r));
    }

    private static void disableAll() {
        for (String name : IDS) {
            su("cmd overlay disable --user 0 'com.android.shell:" + name + "' >/dev/null 2>&1 || true");
        }
        su("cmd overlay disable --user 0 'com.android.shell:PixelStatus' >/dev/null 2>&1 || true");
    }

    private static String diagnostic(ExecResult r) {
        String direct = oneLine(r.out);
        if (!direct.equals("sin detalle")) return direct;
        String logs = overlayDiagnostics();
        return logs.isBlank() ? "exit=" + r.code + " sin detalle" : logs;
    }

    private static String overlayDiagnostics() {
        ExecResult d = su("logcat -d -t 200 2>&1 | grep -iE 'OverlayManager|idmap|Fabricated|PixelStatus|PSWifi|PSMobile|SecurityException|overlayable' | tail -14");
        return oneLine(d.out);
    }

    private static String detail(ExecResult r) {
        String s = oneLine(r.out);
        return s.equals("sin detalle") ? "exit=" + r.code : s;
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
            return new ExecResult(127, t.getClass().getSimpleName());
        }
    }

    private static void writeWifiPng(File file, int level) throws Exception {
        final int w = 72, h = 52;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(6f);
        float cx = 36f, cy = 38f;
        float[] radii = {29f, 21f, 13f};
        for (int i = 0; i < 3; i++) {
            int threshold = i == 0 ? 4 : (i == 1 ? 3 : 2);
            p.setAlpha(level >= threshold ? 255 : 64);
            float r = radii[i];
            c.drawArc(new RectF(cx-r, cy-r, cx+r, cy+r), 220f, 100f, false, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setAlpha(level >= 1 ? 255 : 64);
        c.drawCircle(cx, 43f, 4.6f, p);
        savePng(b, file);
    }

    private static void writeMobilePng(File file, int level, int max) throws Exception {
        final int w = max == 5 ? 72 : 60, h = 56;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.FILL);
        int normalized = Math.round((level / (float) max) * max);
        float left = 4f, bottom = 52f;
        float barW = max == 5 ? 9f : 10f;
        float gap = max == 5 ? 4f : 5f;
        for (int i = 0; i < max; i++) {
            float bh = 12f + i * (36f / Math.max(1, max - 1));
            float x = left + i * (barW + gap);
            p.setAlpha(i < normalized ? 255 : 76);
            c.drawRoundRect(new RectF(x, bottom-bh, x+barW, bottom), 2.6f, 2.6f, p);
        }
        savePng(b, file);
    }

    private static void savePng(Bitmap bitmap, File file) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new IllegalStateException("PNG encode failed");
            }
        } finally {
            bitmap.recycle();
        }
    }

    private static String oneLine(String s) {
        if (s == null || s.isBlank()) return "sin detalle";
        String x = s.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        return x.length() > 320 ? x.substring(x.length() - 320) : x;
    }
}
