package com.jorge.pixelstatusbar.oneui;

import android.os.IBinder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * v0.10 Wi-Fi-only FRRO. Deliberately isolated from the proven-safe mobile overlay.
 * Only generic Wi-Fi and the Wi-Fi 6 family currently shown on this S25 are touched.
 */
public final class WifiFrroHelper {
    private static final String OWNER = "com.android.shell";
    private static final String NAME = "PixelStatusWifi";
    private static final String TARGET = "com.android.systemui";

    private static final int[] WIFI_REFS = {
            0x7e080e33, // ic_wifi_0
            0x7e080e35, // ic_wifi_1
            0x7e080e37, // ic_wifi_2
            0x7e080e39, // ic_wifi_3
            0x7e080e39  // strongest state
    };

    private static final String[] PREFIXES = {
            "stat_sys_wifi_signal_",
            "stat_sys_wifi6_signal_"
    };

    public static void main(String[] args) {
        try {
            exemptHiddenApis();
            registerOverlay();
            System.out.println("REGISTERED " + OWNER + ":" + NAME);
        } catch (Throwable t) {
            Throwable root = unwrap(t);
            System.err.println("WIFI_FRRO_ERROR: " + root.getClass().getName() + ": "
                    + String.valueOf(root.getMessage()));
            StackTraceElement[] st = root.getStackTrace();
            for (int i = 0; i < Math.min(st.length, 10); i++) {
                System.err.println("  at " + st[i]);
            }
            System.exit(1);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerOverlay() throws Exception {
        Class<?> foClass = Class.forName("android.content.om.FabricatedOverlay");
        Constructor<?> ctor = foClass.getDeclaredConstructor(String.class, String.class);
        ctor.setAccessible(true);
        Object overlay = ctor.newInstance(NAME, TARGET);

        Method setOwner = foClass.getDeclaredMethod("setOwningPackage", String.class);
        setOwner.setAccessible(true);
        setOwner.invoke(overlay, OWNER);

        try {
            Method setTargetOverlayable = foClass.getDeclaredMethod("setTargetOverlayable", String.class);
            setTargetOverlayable.setAccessible(true);
            setTargetOverlayable.invoke(overlay, new Object[]{null});
        } catch (NoSuchMethodException ignored) {
            // Samsung SystemUI FRRO uses a blank targetOverlayableName.
        }

        Field internalField = foClass.getDeclaredField("mOverlay");
        internalField.setAccessible(true);
        Object internal = internalField.get(overlay);

        Field entriesField = internal.getClass().getField("entries");
        Object existing = entriesField.get(internal);
        List entries;
        if (existing instanceof List) {
            entries = (List) existing;
            entries.clear();
        } else {
            entries = new ArrayList();
            entriesField.set(internal, entries);
        }

        Class<?> entryClass = Class.forName("android.os.FabricatedOverlayInternalEntry");
        Constructor<?> entryCtor = entryClass.getDeclaredConstructor();
        entryCtor.setAccessible(true);
        Field resourceName = entryClass.getField("resourceName");
        Field dataType = entryClass.getField("dataType");
        Field data = entryClass.getField("data");
        Field configuration = entryClass.getField("configuration");

        for (String prefix : PREFIXES) {
            for (int level = 0; level <= 4; level++) {
                Object entry = entryCtor.newInstance();
                resourceName.set(entry, TARGET + ":drawable/" + prefix + level);
                dataType.setInt(entry, 0x01); // Res_value::TYPE_REFERENCE
                data.setInt(entry, WIFI_REFS[level]);
                configuration.set(entry, null);
                entries.add(entry);
            }
        }

        Class<?> txBuilderClass = Class.forName("android.content.om.OverlayManagerTransaction$Builder");
        Object txBuilder = txBuilderClass.getDeclaredConstructor().newInstance();
        Method register = txBuilderClass.getDeclaredMethod("registerFabricatedOverlay", foClass);
        register.setAccessible(true);
        register.invoke(txBuilder, overlay);
        Method build = txBuilderClass.getDeclaredMethod("build");
        build.setAccessible(true);
        Object transaction = build.invoke(txBuilder);

        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        IBinder binder = (IBinder) getService.invoke(null, "overlay");
        if (binder == null) throw new IllegalStateException("overlay service unavailable");

        Class<?> stubClass = Class.forName("android.content.om.IOverlayManager$Stub");
        Method asInterface = stubClass.getDeclaredMethod("asInterface", IBinder.class);
        asInterface.setAccessible(true);
        Object iom = asInterface.invoke(null, binder);
        if (iom == null) throw new IllegalStateException("IOverlayManager unavailable");

        Class<?> txClass = Class.forName("android.content.om.OverlayManagerTransaction");
        Class<?> iomClass = Class.forName("android.content.om.IOverlayManager");
        Method commit = iomClass.getMethod("commit", txClass);
        commit.invoke(iom, transaction);
    }

    private static void exemptHiddenApis() {
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntime.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object runtime = getRuntime.invoke(null);
            Method setExemptions = vmRuntime.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            setExemptions.setAccessible(true);
            setExemptions.invoke(runtime, (Object) new String[]{"L"});
        } catch (Throwable ignored) {
            // app_process/root is normally exempt.
        }
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while (cur instanceof InvocationTargetException
                && ((InvocationTargetException) cur).getTargetException() != null) {
            cur = ((InvocationTargetException) cur).getTargetException();
        }
        return cur;
    }
}
