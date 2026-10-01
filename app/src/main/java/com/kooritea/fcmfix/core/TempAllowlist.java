package com.kooritea.fcmfix.core;

import java.lang.reflect.Method;

/** Supports only the verified AOSP seven/eight-argument framework contracts. */
public final class TempAllowlist {
    private TempAllowlist() {}
    public static void grant(Object service, String target, int userId, long duration,
                             int type, int reason) throws ReflectiveOperationException {
        if (service == null || target == null || userId < 0 || duration <= 0 || duration > 60_000L)
            throw new IllegalArgumentException("Invalid bounded push exemption");
        Method method;
        Object[] arguments;
        try {
            method = service.getClass().getMethod("addPowerSaveTempWhitelistApp", int.class,
                    String.class, long.class, int.class, int.class, boolean.class, int.class, String.class);
            arguments = new Object[]{1000, target, duration, type, userId, false, reason, "FCMFix push processing"};
        } catch (NoSuchMethodException legacy) {
            method = service.getClass().getMethod("addPowerSaveTempWhitelistApp", int.class,
                    String.class, long.class, int.class, boolean.class, int.class, String.class);
            arguments = new Object[]{1000, target, duration, userId, false, reason, "FCMFix push processing"};
        }
        method.setAccessible(true);
        method.invoke(service, arguments);
    }
}
