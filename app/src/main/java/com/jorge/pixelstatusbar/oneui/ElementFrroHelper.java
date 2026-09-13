package com.jorge.pixelstatusbar.oneui;

import android.os.IBinder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** v0.16: reference-only Wi-Fi FRRO helper. No dimensions are modified. */
public final class ElementFrroHelper {
    private static final String OWNER = "com.android.shell";
    private static final String TARGET = "com.android.systemui";

    // Internal AOSP/Pixel-style drawables already present in this exact Samsung SystemUI.
    private static final int[] WIFI_REFS = {
            0x7e080e33, 0x7e080e35, 0x7e080e37, 0x7e080e39, 0x7e080e39
    };

    private static final String[] WIFI_ALL_PREFIXES = {
            "stat_sys_wifi_signal_",
            "stat_sys_wifi5_signal_",
            "stat_sys_wifi6_signal_",
            "stat_sys_6ewifi_signal_",
            "stat_sys_wifi7_signal_",
            "sec_ic_wifi_signal_",
            "sec_ic_wifi_signal_wifi5_",
            "sec_ic_wifi_signal_wifi6_",
            "sec_ic_wifi_signal_wifi6e_",
            "sec_ic_wifi7_signal_"
    };

    public static void main(String[] args) {
        try {
            exemptHiddenApis();

            if (args.length >= 1 && "unregister".equals(args[0])) {
                if (args.length < 2) throw new IllegalArgumentException("missing overlay simple name");
                unregister(args[1]);
                System.out.println("UNREGISTERED " + OWNER + ":" + args[1]);
                return;
            }

            if (args.length < 1) throw new IllegalArgumentException("missing element");
            String element = args[0];
            String customName = args.length >= 2 ? args[1] : simpleName(element);
            register(element, customName);
            System.out.println("REGISTERED " + OWNER + ":" + customName);
        } catch (Throwable t) {
            Throwable root = unwrap(t);
            System.err.println("ELEMENT_FRRO_ERROR: " + root.getClass().getName() + ": " + String.valueOf(root.getMessage()));
            StackTraceElement[] st = root.getStackTrace();
            for (int i = 0; i < Math.min(st.length, 10); i++) System.err.println("  at " + st[i]);
            System.exit(1);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void register(String element, String simpleName) throws Exception {
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

        if ("wifi_all".equals(element)) {
            for (String prefix : WIFI_ALL_PREFIXES) {
                addWifi(entries, entryCtor, resourceName, dataType, data, configuration, prefix);
            }
        } else if ("wifi_base".equals(element)) {
            addWifi(entries, entryCtor, resourceName, dataType, data, configuration, "stat_sys_wifi_signal_");
        } else if ("wifi6".equals(element)) {
            addWifi(entries, entryCtor, resourceName, dataType, data, configuration, "stat_sys_wifi6_signal_");
        } else {
            throw new IllegalArgumentException("unsafe/unknown element: " + element);
        }

        Object builder = transactionBuilder();
        Method register = findSingleArgMethod(builder.getClass(), "registerFabricatedOverlay");
        register.setAccessible(true);
        register.invoke(builder, overlay);
        commit(builder);
    }

    private static void unregister(String simpleName) throws Exception {
        Class<?> idClass = Class.forName("android.content.om.OverlayIdentifier");
        Object identifier;
        try {
            Constructor<?> c = idClass.getDeclaredConstructor(String.class, String.class);
            c.setAccessible(true);
            identifier = c.newInstance(OWNER, simpleName);
        } catch (NoSuchMethodException noCtor) {
            Method fromString = idClass.getDeclaredMethod("fromString", String.class);
            fromString.setAccessible(true);
            identifier = fromString.invoke(null, OWNER + ":" + simpleName);
        }

        Object builder = transactionBuilder();
        Method unregister = findSingleArgMethod(builder.getClass(), "unregisterFabricatedOverlay");
        unregister.setAccessible(true);
        unregister.invoke(builder, identifier);
        commit(builder);
    }

    private static Object transactionBuilder() throws Exception {
        Class<?> cls = Class.forName("android.content.om.OverlayManagerTransaction$Builder");
        Constructor<?> c = cls.getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
    }

    private static Method findSingleArgMethod(Class<?> cls, String name) throws NoSuchMethodException {
        for (Method m : cls.getDeclaredMethods()) {
            if (name.equals(m.getName()) && m.getParameterCount() == 1) return m;
        }
        throw new NoSuchMethodException(name);
    }

    private static void commit(Object txBuilder) throws Exception {
        Method build = txBuilder.getClass().getDeclaredMethod("build");
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

    static String simpleName(String element) {
        switch (element) {
            case "wifi_all": return "PixelStatusWifiAll";
            case "wifi_base": return "PixelStatusWifiBase";
            case "wifi6": return "PixelStatusWifi6";
            default: throw new IllegalArgumentException("unknown element");
        }
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
        while (cur instanceof InvocationTargetException
                && ((InvocationTargetException) cur).getTargetException() != null) {
            cur = ((InvocationTargetException) cur).getTargetException();
        }
        return cur;
    }
}
