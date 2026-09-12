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

    // Existing AOSP/Pixel-style connectivity drawables already shipped inside this exact SystemUI.
    private static final int[] WIFI_REFS = {
            0x7e080e33, // ic_wifi_0
            0x7e080e35, // ic_wifi_1
            0x7e080e37, // ic_wifi_2
            0x7e080e39, // ic_wifi_3
            0x7e080e39  // strongest state uses ic_wifi_3
    };

    private static final int[] MOBILE4_REFS = {
            0x7e080c20, 0x7e080c24, 0x7e080c28, 0x7e080c2c, 0x7e080c30
    };

    private static final int[] MOBILE5_REFS = {
            0x7e080c22, 0x7e080c26, 0x7e080c2a, 0x7e080c2e, 0x7e080c32, 0x7e080c34
    };

    // Samsung chooses a different stat_sys family depending on the negotiated Wi-Fi generation.
    // Cover all normal families so Wi-Fi 5/6/6E/7 cannot fall back to the Samsung glyph/badge.
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
            // Samsung's FRRO for SystemUI also has a blank targetOverlayableName.
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

        // Wi-Fi: normal plus Wi-Fi 5/6/6E/7 status families -> Pixel/AOSP segmented Wi-Fi.
        for (String prefix : WIFI_PREFIXES) {
            for (int level = 0; level <= 4; level++) {
                addEntry(entries, entryCtor, resourceName, dataType, data, configuration,
                        TARGET + ":drawable/" + prefix + level,
                        0x01, WIFI_REFS[level]);
            }
        }

        // Mobile signal: already proven on this S25 in v0.7.
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

        // Pixel/AOSP current status-bar metrics. These are scalar FRRO entries only; no layout APK
        // replacement. Battery remains Samsung's live level renderer but gets Pixel unified proportions.
        addDimen(entries, entryCtor, resourceName, dataType, data, configuration,
                "status_bar_wifi_signal_size", 15f, 2); // sp

        addDimen(entries, entryCtor, resourceName, dataType, data, configuration,
                "status_bar_battery_chip_width", 20.6f, 2); // sp
        addDimen(entries, entryCtor, resourceName, dataType, data, configuration,
                "status_bar_battery_chip_height", 12f, 2); // sp
        addDimen(entries, entryCtor, resourceName, dataType, data, configuration,
                "status_bar_battery_chip_radius", 6f, 2); // sp
        addDimen(entries, entryCtor, resourceName, dataType, data, configuration,
                "status_bar_battery_unified_icon_width", 20.6f, 2); // sp
        addDimen(entries, entryCtor, resourceName, dataType, data, configuration,
                "status_bar_battery_unified_icon_height", 12f, 2); // sp

        // Pixel clock: AOSP uses 14sp and compact 4dp start padding. Samsung's layout already uses
        // weight 600, so this changes only safe resource-backed metrics.
        addDimen(entries, entryCtor, resourceName, dataType, data, configuration,
                "status_bar_clock_size", 14f, 2); // sp
        addDimen(entries, entryCtor, resourceName, dataType, data, configuration,
                "sec_status_bar_clock_size", 14f, 2); // sp
        addDimen(entries, entryCtor, resourceName, dataType, data, configuration,
                "status_bar_clock_starting_padding", 4f, 1); // dp
        addDimen(entries, entryCtor, resourceName, dataType, data, configuration,
                "status_bar_clock_end_padding", 0f, 1); // dp
        addDimen(entries, entryCtor, resourceName, dataType, data, configuration,
                "status_bar_left_clock_starting_padding", 0f, 1); // dp
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
                0x05, complexDimension(value, unit)); // Res_value::TYPE_DIMENSION
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

    /**
     * Encode a positive Android complex dimension with 1/256 precision.
     * unit: 1=dp, 2=sp. Radix 23p8 is plenty for status-bar dimensions.
     */
    private static int complexDimension(float value, int unit) {
        int mantissa = Math.round(value * 256f);
        return (mantissa << 8) | (2 << 4) | (unit & 0xf);
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
