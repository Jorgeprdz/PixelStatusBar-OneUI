package com.jorge.pixelstatusbar.oneui;

/**
 * v0.18 RESCUE / FAIL-CLOSED.
 *
 * Mobile FRRO registration is deliberately disabled. Previous builds embedded
 * hard-coded TYPE_REFERENCE values (0x7e08...) that are not proven safe across
 * SystemUI lifecycles/reboots on this firmware.
 */
public final class FrroHelper {
    public static void main(String[] args) {
        System.err.println(
                "FRRO_HELPER_DISABLED: unsafe hard-coded TYPE_REFERENCE values are blocked");
        System.exit(2);
    }
}
