package com.jorge.pixelstatusbar.oneui;

import android.os.IBinder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * SAFE v0.9: only mobile-signal resources that were already proven stable on this S25.
 * No Wi-Fi, battery, clock, layout, dimensions or boot-persistent changes.
 */
public final class FrroHelper {
    private static final String OWNER = "com.android.shell";
    private static final String NAME = "PixelStatusNative";
    private static final String TARGET = "com.android.systemui";

    private static final int[] MOBILE4_REFS = {
            0x7e080c20, 0x7e080c24, 0x7e080c28, 0x7e080c2c, 0x7e080c30
    };

    private static final int[] MOBILE5_REFS = {
            0x7e080c22, 0x7e080c26, 0x7e080c2a, 0x7e080c2e, 0x7e080c32, 0x7e080c34
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
            // Samsung's SystemUI fabricated overlay uses no targetOverlayableName.
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

        // Exactly the mobile-signal mappings that worked in v0.7.
        for (int level = 0; level <= 4; level++) {
            addReference(entries, entryCtor, resourceName, dataType, data, configuration,
                    TARGET + ":drawable/stat_sys_signal_" + level,
                    MOBILE4_REFS[level]);
        }
        for (int level = 0; level <= 5; level++) {
            addReference(entries, entryCtor, resourceName, dataType, data, configuration,
                    TARGET + ":drawable/stat_sys_signal_5level_" + level,
                    MOBILE5_REFS[level]);
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

    private static void addReference(List entries, Constructor<?> entryCtor,
            Field resourceName, Field dataType, Field data, Field configuration,
            String targetName, int referenceId) throws Exception {
        Object entry = entryCtor.newInstance();
        resourceName.set(entry, targetName);
        dataType.setInt(entry, 0x01); // Res_value::TYPE_REFERENCE
        data.setInt(entry, referenceId);
        configuration.set(entry, null);
        entries.add(entry);
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
            // app_process/root is normally exempt; any real failure is reported above.
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
