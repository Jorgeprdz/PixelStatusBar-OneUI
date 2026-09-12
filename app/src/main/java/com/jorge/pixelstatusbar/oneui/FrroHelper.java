package com.jorge.pixelstatusbar.oneui;

import android.os.IBinder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs under app_process as uid 0. Builds one temporary shell-owned FRRO.
 * Nothing is written to /system and nothing is installed for boot.
 */
public final class FrroHelper {
    private static final String OWNER = "com.android.shell";
    private static final String NAME = "PixelStatusNative";
    private static final String TARGET = "com.android.systemui";

    private static final int[] WIFI_REFS = {
            0x7e080e33, // ic_wifi_0
            0x7e080e35, // ic_wifi_1
            0x7e080e37, // ic_wifi_2
            0x7e080e39, // ic_wifi_3
            0x7e080e39  // strongest state
    };

    private static final int[] MOBILE4_REFS = {
            0x7e080c20, 0x7e080c24, 0x7e080c28, 0x7e080c2c, 0x7e080c30
    };

    private static final int[] MOBILE5_REFS = {
            0x7e080c22, 0x7e080c26, 0x7e080c2a, 0x7e080c2e, 0x7e080c32, 0x7e080c34
    };

    // Samsung selects a different drawable family according to Wi-Fi generation.
    private static final String[] WIFI_PREFIXES = {
            "stat_sys_wifi_signal_",
            "stat_sys_wifi5_signal_",
            "stat_sys_wifi6_signal_",
            "stat_sys_6ewifi_signal_",
            "stat_sys_wifi7_signal_"
    };

    public static void main(String[] args) {
        try {
            exemptHiddenApis();
            registerOverlay();
            System.out.println("REGISTERED " + OWNER + ":" + NAME);
        } catch (Throwable t) {
            Throwable root = unwrap(t);
            System.err.println("FRRO_HELPER_ERROR: " + root.getClass().getName() + ": "
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
            // Samsung's own SystemUI FRRO also uses a blank targetOverlayableName.
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

        // Wi-Fi 4/5/6/6E/7 -> same Pixel/AOSP glyph family, removing Samsung's generation badge.
        for (String prefix : WIFI_PREFIXES) {
            for (int level = 0; level <= 4; level++) {
                addEntry(entries, entryCtor, resourceName, dataType, data, configuration,
                        TARGET + ":drawable/" + prefix + level,
                        0x01, WIFI_REFS[level]);
            }
        }

        // Mobile signal (already proven working on this exact S25 in v0.7).
        for (int level = 0; level <= 4; level++) {
            addEntry(entries, entryCtor, resourceName, dataType, data, configuration,
                    TARGET + ":drawable/stat_sys_signal_" + level,
                    0x01, MOBILE4_REFS[level]);
        }
        for (int level = 0; level <= 5; level++) {
            addEntry(entries, entryCtor, resourceName, dataType, data, configuration,
                    TARGET + ":drawable/stat_sys_signal_5level_" + level,
                    0x01, MOBILE5_REFS[level]);
        }

        // Pixel/AOSP status bar metrics. These remain scalar temporary resource overrides;
        // Samsung's live battery renderer/tint logic stays intact.
        addDimen(entries, entryCtor, resourceName, dataType, data, configuration,
                "status_bar_wifi_signal_size", 15f, 2); // sp

        addDimen(entries, entryCtor, resourceName, dataType, data, configuration,
                "status_bar_battery_chip_width", 20.6f, 2);
        addDimen(entries, entryCtor, resourceName, dataType, data, configuration,
                "status_bar_battery_chip_height", 12f, 2);
        addDimen(entries, entryCtor, resourceName, dataType, data, configuration,
                "status_bar_battery_chip_radius", 6f, 2);
        addDimen(entries, entryCtor, resourceName, dataType, data, configuration,
                "status_bar_battery_unified_icon_width", 20.6f, 2);
        addDimen(entries, entryCtor, resourceName, dataType, data, configuration,
                "status_bar_battery_unified_icon_height", 12f, 2);

        // Pixel clock metrics. Samsung hardcodes fontFamily="sec" in status_bar.xml, so we do not
        // replace that layout; size/padding are safe resource-backed overrides.
        addDimen(entries, entryCtor, resourceName, dataType, data, configuration,
                "status_bar_clock_size", 14f, 2);
        addDimen(entries, entryCtor, resourceName, dataType, data, configuration,
                "sec_status_bar_clock_size", 14f, 2);
        addDimen(entries, entryCtor, resourceName, dataType, data, configuration,
                "status_bar_clock_starting_padding", 4f, 1); // dp
        addDimen(entries, entryCtor, resourceName, dataType, data, configuration,
                "status_bar_clock_end_padding", 0f, 1);
        addDimen(entries, entryCtor, resourceName, dataType, data, configuration,
                "status_bar_left_clock_starting_padding", 0f, 1);
        addDimen(entries, entryCtor, resourceName, dataType, data, configuration,
                "status_bar_left_clock_end_padding", 2f, 2); // sp

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

    private static void addDimen(List entries, Constructor<?> entryCtor,
            Field resourceName, Field dataType, Field data, Field configuration,
            String name, float value, int unit) throws Exception {
        addEntry(entries, entryCtor, resourceName, dataType, data, configuration,
                TARGET + ":dimen/" + name,
                0x05, createComplexDimension(value, unit)); // Res_value::TYPE_DIMENSION
    }

    private static void addEntry(List entries, Constructor<?> entryCtor,
            Field resourceName, Field dataType, Field data, Field configuration,
            String targetName, int type, int value) throws Exception {
        Object entry = entryCtor.newInstance();
        resourceName.set(entry, targetName);
        dataType.setInt(entry, type);
        data.setInt(entry, value);
        configuration.set(entry, null);
        entries.add(entry);
    }

    // Use Android's own encoder instead of duplicating the packed-complex format.
    private static int createComplexDimension(float value, int unit) throws Exception {
        Class<?> tv = Class.forName("android.util.TypedValue");
        Method m = tv.getDeclaredMethod("createComplexDimension", float.class, int.class);
        m.setAccessible(true);
        return (Integer) m.invoke(null, value, unit);
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
            // app_process/root is commonly exempt already; report a real reflection failure later.
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
