package com.jorge.pixelstatusbar.oneui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.util.Log;
import android.widget.ImageView;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/** Narrow, fail-open SystemUI renderer hook. */
public final class ModuleMain extends XposedModule {
    private static final String TAG = "PixelStatusBar-OneUI";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Module loaded in " + param.getProcessName());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        safeHookImageResource();
        safeHookImageIcon();
    }

    private void safeHookImageResource() {
        try {
            Method method = ImageView.class.getDeclaredMethod("setImageResource", int.class);
            hook(method).intercept(chain -> {
                Object target = chain.getThisObject();
                Object arg = chain.getArg(0);
                if (target instanceof ImageView && arg instanceof Integer
                        && tryApply((ImageView) target, (Integer) arg)) {
                    return null;
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "Hook ready: setImageResource");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "setImageResource unavailable; Samsung rendering preserved", t);
        }
    }

    private void safeHookImageIcon() {
        try {
            Method method = ImageView.class.getDeclaredMethod("setImageIcon", Icon.class);
            hook(method).intercept(chain -> {
                Object target = chain.getThisObject();
                Object arg = chain.getArg(0);
                if (target instanceof ImageView && arg instanceof Icon) {
                    Icon icon = (Icon) arg;
                    try {
                        if (icon.getType() == Icon.TYPE_RESOURCE
                                && "com.android.systemui".equals(icon.getResPackage())
                                && tryApply((ImageView) target, icon.getResId())) {
                            return null;
                        }
                    } catch (Throwable ignored) {
                        // Fall through to Samsung.
                    }
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "Hook ready: setImageIcon");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "setImageIcon unavailable; Samsung rendering preserved", t);
        }
    }

    private boolean tryApply(ImageView view, int resId) {
        if (resId == 0) return false;
        try {
            if (!"com.android.systemui".equals(view.getResources().getResourcePackageName(resId))) {
                return false;
            }
            String name = view.getResources().getResourceEntryName(resId);
            PixelStatusDrawable.Spec spec = PixelStatusDrawable.Spec.fromResourceName(name);
            if (spec == null) return false;

            Context context = view.getContext();
            ToggleState.ensureStarted(context);
            Drawable original = view.getResources().getDrawable(resId, context.getTheme());
            if (original == null) return false;

            PixelStatusDrawable wrapped = new PixelStatusDrawable(
                    context, original.mutate(), spec, view.getImageTintList());
            ToggleState.track(wrapped);
            view.setImageDrawable(wrapped);
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Drawable left unchanged: 0x" + Integer.toHexString(resId), t);
            return false;
        }
    }
}
