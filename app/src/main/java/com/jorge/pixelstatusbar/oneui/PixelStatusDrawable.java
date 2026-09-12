package com.jorge.pixelstatusbar.oneui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tint-aware wrapper. OFF delegates to the original Samsung drawable. */
final class PixelStatusDrawable extends Drawable {
    enum Kind { WIFI, MOBILE }

    static final class Spec {
        final Kind kind;
        final int level;
        final int maxLevel;
        private static final Pattern WIFI = Pattern.compile("^stat_sys_wifi_signal_([0-4])$");
        private static final Pattern MOBILE = Pattern.compile("^stat_sys_signal_([0-4])$");
        private static final Pattern MOBILE5 = Pattern.compile("^stat_sys_signal_5level_([0-5])$");

        Spec(Kind kind, int level, int maxLevel) {
            this.kind = kind;
            this.level = level;
            this.maxLevel = maxLevel;
        }

        static Spec fromResourceName(String name) {
            if (name == null) return null;
            Matcher m = WIFI.matcher(name);
            if (m.matches()) return new Spec(Kind.WIFI, Integer.parseInt(m.group(1)), 4);
            m = MOBILE.matcher(name);
            if (m.matches()) return new Spec(Kind.MOBILE, Integer.parseInt(m.group(1)), 4);
            m = MOBILE5.matcher(name);
            if (m.matches()) return new Spec(Kind.MOBILE, Integer.parseInt(m.group(1)), 5);
            return null;
        }
    }

    private final Drawable original;
    private final Spec spec;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private ColorStateList tintList;
    private int tintColor = Color.WHITE;
    private int alpha = 255;
    private ColorFilter colorFilter;
    private boolean pixelEnabled;

    PixelStatusDrawable(Context context, Drawable original, Spec spec, ColorStateList initialTint) {
        this.original = original;
        this.spec = spec;
        this.density = context.getResources().getDisplayMetrics().density;
        this.tintList = initialTint;
        resolveTint(getState());
    }

    void setPixelEnabled(boolean enabled) {
        if (pixelEnabled != enabled) {
            pixelEnabled = enabled;
            invalidateSelf();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        if (!pixelEnabled) {
            original.draw(canvas);
            return;
        }
        Rect b = getBounds();
        if (b.isEmpty()) return;
        if (spec.kind == Kind.WIFI) drawWifi(canvas, b);
        else drawMobile(canvas, b);
    }

    private void drawWifi(Canvas canvas, Rect b) {
        float w = b.width();
        float h = b.height();
        float cx = b.exactCenterX();
        float cy = b.top + h * 0.63f;
        float stroke = Math.max(1.15f * density, Math.min(w, h) * 0.085f);
        int active = Math.max(0, Math.min(4, spec.level));

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float[] radii = {0.20f, 0.36f, 0.52f};
        for (int i = 0; i < radii.length; i++) {
            setPaint(active >= i + 2 ? 1f : 0.22f);
            float r = Math.min(w, h) * radii[i];
            canvas.drawArc(new RectF(cx-r, cy-r, cx+r, cy+r), 220f, 100f, false, paint);
        }

        paint.setStyle(Paint.Style.FILL);
        setPaint(active >= 1 ? 1f : 0.22f);
        float dot = Math.max(stroke * 0.62f, Math.min(w, h) * 0.055f);
        canvas.drawCircle(cx, b.top + h * 0.79f, dot, paint);
    }

    private void drawMobile(Canvas canvas, Rect b) {
        float w = b.width();
        float h = b.height();
        float left = b.left + w * 0.13f;
        float bottom = b.bottom - h * 0.14f;
        float availableW = w * 0.74f;
        float gap = availableW * 0.055f;
        float barW = (availableW - gap * 3f) / 4f;
        int normalized = Math.round((spec.level / (float) spec.maxLevel) * 4f);

        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 4; i++) {
            float barH = h * (0.20f + i * 0.16f);
            float x = left + i * (barW + gap);
            float radius = Math.min(barW * 0.36f, 1.8f * density);
            setPaint(i < normalized ? 1f : 0.22f);
            canvas.drawRoundRect(x, bottom-barH, x+barW, bottom, radius, radius, paint);
        }
    }

    private void setPaint(float strength) {
        int a = Math.round(alpha * strength);
        paint.setColor((tintColor & 0x00FFFFFF) | (a << 24));
        paint.setColorFilter(colorFilter);
    }

    @Override protected void onBoundsChange(Rect bounds) { original.setBounds(bounds); }

    @Override protected boolean onStateChange(int[] state) {
        original.setState(state);
        resolveTint(state);
        invalidateSelf();
        return true;
    }

    @Override protected boolean onLevelChange(int level) {
        original.setLevel(level);
        return true;
    }

    @Override public boolean isStateful() {
        return (tintList != null && tintList.isStateful()) || original.isStateful();
    }

    @Override public void setTint(int tint) {
        tintList = ColorStateList.valueOf(tint);
        tintColor = tint;
        original.setTint(tint);
        invalidateSelf();
    }

    @Override public void setTintList(ColorStateList tint) {
        tintList = tint;
        resolveTint(getState());
        original.setTintList(tint);
        invalidateSelf();
    }

    private void resolveTint(int[] state) {
        if (tintList != null) tintColor = tintList.getColorForState(state, tintList.getDefaultColor());
    }

    @Override public void setAlpha(int value) {
        alpha = Math.max(0, Math.min(255, value));
        original.setAlpha(alpha);
        invalidateSelf();
    }

    @Override public int getAlpha() { return alpha; }

    @Override public void setColorFilter(ColorFilter filter) {
        colorFilter = filter;
        original.setColorFilter(filter);
        invalidateSelf();
    }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return original.getIntrinsicWidth(); }
    @Override public int getIntrinsicHeight() { return original.getIntrinsicHeight(); }
    @Override public int getMinimumWidth() { return original.getMinimumWidth(); }
    @Override public int getMinimumHeight() { return original.getMinimumHeight(); }
}
