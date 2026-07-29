package com.kooritea.fcmfix.xposed;

import android.os.Build;

import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;
import com.kooritea.fcmfix.util.XposedUtils;

import java.lang.reflect.Method;

public class DiagnosticsLogger extends XposedModule {

    public DiagnosticsLogger(ClassLoader classLoader) {
        super(classLoader);
        logSystemInfo();
        probeColorOSClasses();
        probeBroadcastMethod();
    }

    @Override
    protected void onCanReadConfig() throws Throwable {
        super.onCanReadConfig();
    }

    private void logSystemInfo() {
        printLog("[Diagnostics] ========== FCMFix System Diagnostics ==========", true);
        printLog("[Diagnostics] Android SDK: " + Build.VERSION.SDK_INT, true);
        printLog("[Diagnostics] Build: " + Build.DISPLAY, true);
        printLog("[Diagnostics] Fingerprint: " + Build.FINGERPRINT, true);
        printLog("[Diagnostics] Brand: " + Build.BRAND + ", Model: " + Build.MODEL, true);
        printLog("[Diagnostics] Device: " + Build.DEVICE + ", Product: " + Build.PRODUCT, true);

        // Detect ROM type
        String romType = "Unknown";
        try {
            String oplusVersion = (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, "ro.build.version.oplusrom", "");
            if (oplusVersion != null && !oplusVersion.isEmpty()) {
                romType = "ColorOS/OxygenOS (" + oplusVersion + ")";
            }
        } catch (Throwable ignored) {}
        if ("Unknown".equals(romType)) {
            try {
                String miuiVersion = (String) Class.forName("android.os.SystemProperties")
                        .getMethod("get", String.class, String.class)
                        .invoke(null, "ro.miui.ui.version.name", "");
                if (miuiVersion != null && !miuiVersion.isEmpty()) {
                    romType = "MIUI/HyperOS (" + miuiVersion + ")";
                }
            } catch (Throwable ignored) {}
        }
        printLog("[Diagnostics] ROM Type: " + romType, true);
    }

    private void probeColorOSClasses() {
        printLog("[Diagnostics] --- Probing ColorOS Classes ---", true);

        String[][] classGroups = {
            {"WakeLock", "com.android.server.power.OplusProxyWakeLock", "com.android.server.power.OplusPowerWakeLock", "com.android.server.power.OplusWakeLockProxy"},
            {"HansManager", "com.android.server.hans.OplusHansManager", "com.android.server.am.OplusHansManager", "com.android.server.hans.scene.OplusBgSceneManager"},
            {"StartupManager", "com.android.server.am.OplusAppStartupManager", "com.android.server.am.OplusAppStartupManagerService"},
            {"ProxyBroadcast", "com.android.server.am.OplusProxyBroadcast", "com.android.server.am.OplusProxyBroadcastEx", "com.android.server.am.OplusBroadcastProxy"},
            {"GmsRestrict", "com.android.server.am.OplusAppStartupManager$OplusStartupStrategy", "com.android.server.hans.scene.OplusBgSceneManager"},
            {"BroadcastController", "com.android.server.am.BroadcastController", "com.android.server.am.BroadcastQueueModernImpl", "com.android.server.am.ActivityManagerService"}
        };

        for (String[] group : classGroups) {
            String groupName = group[0];
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < group.length; i++) {
                Class<?> clazz = XposedHelpers.findClassIfExists(group[i], classLoader);
                if (clazz != null) {
                    sb.append("  ✓ ").append(group[i]);
                    // List key methods
                    for (Method m : clazz.getDeclaredMethods()) {
                        String name = m.getName();
                        if (name.contains("unfreeze") || name.contains("shouldPrevent") ||
                            name.contains("shouldProxy") || name.contains("broadcastIntent") ||
                            name.contains("isGoogleRestric") || name.contains("getInstance") ||
                            name.contains("thaw") || name.contains("freeze")) {
                            sb.append("\n      → ").append(name).append("(").append(m.getParameterCount()).append(" params)");
                        }
                    }
                    sb.append("\n");
                } else {
                    sb.append("  ✗ ").append(group[i]).append("\n");
                }
            }
            printLog("[Diagnostics] [" + groupName + "]:\n" + sb.toString(), true);
        }
    }

    private void probeBroadcastMethod() {
        printLog("[Diagnostics] --- Probing broadcastIntentLocked ---", true);

        String[] targetClasses = {
            "com.android.server.am.BroadcastController",
            "com.android.server.am.BroadcastQueueModernImpl",
            "com.android.server.am.ActivityManagerService"
        };

        for (String className : targetClasses) {
            Method m = XposedUtils.tryFindMethodMostParam(classLoader, className, "broadcastIntentLocked");
            if (m != null) {
                StringBuilder sb = new StringBuilder();
                sb.append(className).append(".broadcastIntentLocked(\n");
                java.lang.reflect.Parameter[] params = m.getParameters();
                for (int i = 0; i < params.length; i++) {
                    sb.append("    [").append(i).append("] ").append(params[i].getType().getSimpleName());
                    if (params[i].isNamePresent()) {
                        sb.append(" ").append(params[i].getName());
                    }
                    sb.append("\n");
                }
                sb.append(")");
                printLog("[Diagnostics] Found: " + sb.toString(), true);
            } else {
                printLog("[Diagnostics] Not found: " + className + ".broadcastIntentLocked", true);
            }
        }
    }
}
