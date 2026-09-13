package com.jorge.pixelstatusbar.oneui;

import android.os.IBinder;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * v0.18 RESCUE / FAIL-CLOSED.
 *
 * Registration is intentionally impossible in this helper. The only allowed
 * operation is unregister for the exact historical PixelStatus identities that
 * this project created in previous versions.
 */
public final class ElementFrroHelper {
    private static final String OWNER = "com.android.shell";

    public static void main(String[] args) {
        try {
            exemptHiddenApis();

            if (args.length != 2 || !"unregister".equals(args[0])) {
                throw new IllegalArgumentException(
                        "registration disabled: unsafe TYPE_REFERENCE values are blocked");
            }

            validateSimpleName(args[1]);
            unregister(args[1]);
            System.out.println("UNREGISTERED " + OWNER + ":" + args[1]);
        } catch (Throwable t) {
            Throwable root = unwrap(t);
            System.err.println("ELEMENT_FRRO_ERROR: " + root.getClass().getName() + ": "
                    + String.valueOf(root.getMessage()));
            StackTraceElement[] st = root.getStackTrace();
            for (int i = 0; i < Math.min(st.length, 10); i++) {
                System.err.println("  at " + st[i]);
            }
            System.exit(1);
        }
    }

    private static void validateSimpleName(String name) {
        boolean fixed = "PixelStatusNative".equals(name)
                || "PixelStatusWifi".equals(name)
                || "PixelStatusWifiBase".equals(name)
                || "PixelStatusWifi6".equals(name)
                || "PixelStatusWifiAll".equals(name);
        boolean historical = name != null
                && name.matches("PixelStatusWifi(?:Base|6|All)_[0-9A-Fa-f]+");

        if (!fixed && !historical) {
            throw new IllegalArgumentException("overlay identity outside cleanup allowlist");
        }
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

    private static Method findSingleArgMethod(Class<?> cls, String name)
            throws NoSuchMethodException {
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

    private static void exemptHiddenApis() {
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntime.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object runtime = getRuntime.invoke(null);
            Method setExemptions = vmRuntime.getDeclaredMethod(
                    "setHiddenApiExemptions", String[].class);
            setExemptions.setAccessible(true);
            setExemptions.invoke(runtime, (Object) new String[]{"L"});
        } catch (Throwable ignored) {
            // If hidden API access is unavailable, unregister will fail closed.
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
