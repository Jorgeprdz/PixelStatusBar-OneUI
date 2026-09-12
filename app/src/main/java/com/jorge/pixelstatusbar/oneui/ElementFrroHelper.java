package com.jorge.pixelstatusbar.oneui;

import android.os.IBinder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * v0.11 modular fabricated overlays. Each element gets its own FRRO so it can
 * be tested, disabled and watchdog-reverted without touching the other ones.
 */
public final class ElementFrroHelper {
    private static final String OWNER = "com.android.shell";
    private static final String TARGET = "com.android.systemui";

    private static final int[] WIFI_REFS = {
            0x7e080e33, 0x7e080e35, 0x7e080e37, 0x7e080e39, 0x7e080e39
    };

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("ELEMENT_FRRO_ERROR: missing element");
            System.exit(2);
        }
        String element = args[0];
        try {
            exemptHiddenApis();
            register(element);
            System.out.println("REGISTERED " + overlayName(element));
        } catch (Throwable t) {
            Throwable root = unwrap(t);
            System.err.println("ELEMENT_FRRO_ERROR: " + root.getClass().getName() + ": " + String.valueOf(root.getMessage()));
            StackTraceElement[] st = root.getStackTrace();
            for (int i = 0; i < Math.min(st.length, 10); i++) System.err.println("  at " + st[i]);
            System.exit(1);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void register(String element) throws Exception {
        String simpleName = simpleName(element);

        Class<?> foClass = Class.forName("android.content.om.FabricatedOverlay");
        Constructor<?> ctor = foClass.getDeclaredConstructor(String.class, String.class);
        ctor.setAccessible(true);
        Object overlay = ctor.newInstance(simpleName, TARGET);

        Method setOwner = foClass.getDeclaredMethod("setOwningPackage", String.class);
        setOwner.setAccessible(true);
        setOwner.invoke(overlay, OWNER);

        try {
            Method setTargetOverlayable = foClass.getDeclaredMethod("setTargetOverlayable", String.class);
            setTargetOverlayable.setAccessible(true);
            setTargetOverlayable.invoke(overlay, new Object[]{null});
        } catch (NoSuchMethodException ignored) {}

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

        switch (element) {
            case "wifi_base":
                addWifi(entries, entryCtor, resourceName, dataType, data, configuration, "stat_sys_wifi_signal_");
                break;
            case "wifi6":
                addWifi(entries, entryCtor, resourceName, dataType, data, configuration, "stat_sys_wifi6_signal_");
                break;
            case "battery":
                addDimen(entries, entryCtor, resourceName, dataType, data, configuration, "status_bar_battery_chip_width", 20.6f, 1);
                addDimen(entries, entryCtor, resourceName, dataType, data, configuration, "status_bar_battery_chip_height", 12f, 1);
                addDimen(entries, entryCtor, resourceName, dataType, data, configuration, "status_bar_battery_chip_radius", 6f, 1);
                addDimen(entries, entryCtor, resourceName, dataType, data, configuration, "status_bar_battery_unified_icon_width", 20.6f, 1);
                addDimen(entries, entryCtor, resourceName, dataType, data, configuration, "status_bar_battery_unified_icon_height", 12f, 1);
                break;
            case "clock":
                addDimen(entries, entryCtor, resourceName, dataType, data, configuration, "status_bar_clock_size", 14f, 2);
                addDimen(entries, entryCtor, resourceName, dataType, data, configuration, "sec_status_bar_clock_size", 14f, 2);
                addDimen(entries, entryCtor, resourceName, dataType, data, configuration, "status_bar_clock_starting_padding", 4f, 1);
                addDimen(entries, entryCtor, resourceName, dataType, data, configuration, "status_bar_clock_end_padding", 0f, 1);
                addDimen(entries, entryCtor, resourceName, dataType, data, configuration, "status_bar_left_clock_starting_padding", 0f, 1);
                addDimen(entries, entryCtor, resourceName, dataType, data, configuration, "status_bar_left_clock_end_padding", 2f, 1);
                break;
            default:
                throw new IllegalArgumentException("unknown element: " + element);
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

    private static void addWifi(List entries, Constructor<?> entryCtor, Field resourceName,
            Field dataType, Field data, Field configuration, String prefix) throws Exception {
        for (int level = 0; level <= 4; level++) {
            addReference(entries, entryCtor, resourceName, dataType, data, configuration,
                    TARGET + ":drawable/" + prefix + level, WIFI_REFS[level]);
        }
    }

    private static void addReference(List entries, Constructor<?> entryCtor, Field resourceName,
            Field dataType, Field data, Field configuration, String name, int ref) throws Exception {
        Object entry = entryCtor.newInstance();
        resourceName.set(entry, name);
        dataType.setInt(entry, 0x01);
        data.setInt(entry, ref);
        configuration.set(entry, null);
        entries.add(entry);
    }

    private static void addDimen(List entries, Constructor<?> entryCtor, Field resourceName,
            Field dataType, Field data, Field configuration, String name, float value, int unit) throws Exception {
        Object entry = entryCtor.newInstance();
        resourceName.set(entry, TARGET + ":dimen/" + name);
        dataType.setInt(entry, 0x05);
        data.setInt(entry, createComplexDimension(value, unit));
        configuration.set(entry, null);
        entries.add(entry);
    }

    private static int createComplexDimension(float value, int unit) throws Exception {
        Class<?> tv = Class.forName("android.util.TypedValue");
        Method m = tv.getDeclaredMethod("createComplexDimension", float.class, int.class);
        m.setAccessible(true);
        return (Integer) m.invoke(null, value, unit);
    }

    static String simpleName(String element) {
        switch (element) {
            case "wifi_base": return "PixelStatusWifiBase";
            case "wifi6": return "PixelStatusWifi6";
            case "battery": return "PixelStatusBattery";
            case "clock": return "PixelStatusClock";
            default: throw new IllegalArgumentException("unknown element");
        }
    }

    static String overlayName(String element) {
        return OWNER + ":" + simpleName(element);
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
        } catch (Throwable ignored) {}
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while (cur instanceof InvocationTargetException && ((InvocationTargetException) cur).getTargetException() != null) {
            cur = ((InvocationTargetException) cur).getTargetException();
        }
        return cur;
    }
}
