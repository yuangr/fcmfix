package com.kooritea.fcmfix.xposed;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.WorkSource;

import com.kooritea.fcmfix.util.XposedUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XC_MethodReplacement;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

public class OplusProxyFix extends XposedModule {

    // Separate instance variables for different service types to avoid method mismatch
    private static volatile Object s_oplusProxyWakeLock = null;  // OplusProxyWakeLock/OplusPowerWakeLock/OplusWakeLockProxy
    private static volatile Object s_oplusHansManager = null;    // OplusHansManager/OplusBgSceneManager
    private static volatile boolean s_useFourParams = false;
    private static volatile boolean s_signatureDetected = false;
    private static ClassLoader s_systemClassLoader = null;

    // Class name candidates
    private static final String[] WAKELOCK_CLASSES = {
        "com.android.server.power.OplusProxyWakeLock",
        "com.android.server.power.OplusPowerWakeLock",
        "com.android.server.power.OplusWakeLockProxy"
    };

    private static final String[] HANS_CLASSES = {
        "com.android.server.hans.OplusHansManager",
        "com.android.server.am.OplusHansManager",
        "com.android.server.hans.scene.OplusBgSceneManager"
    };

    public OplusProxyFix(ClassLoader classLoader) {
        super(classLoader);
        s_systemClassLoader = classLoader;
        try {
            this.startHookOplusProxyWakeLock();
            this.startHookOplusHansManager();
            this.startHookOplusProxyBroadcast();
        } catch (Throwable e) {
            printLog("hook error OplusProxy:" + e.getMessage());
        }
        try {
            this.startHookRegisterGmsRestrictObserver();
        } catch (Throwable e) {
            printLog("hook error registerGmsRestrictObserver:" + e.getMessage());
        }
        try {
            this.startHookUpdateGmsRestrict();
        } catch (Throwable e) {
            printLog("hook error updateGmsRestrict:" + e.getMessage());
        }
        try {
            this.startHookIsGoogleRestricInfoOn();
        } catch (Throwable e) {
            printLog("hook error isGoogleRestricInfoOn:" + e.getMessage());
        }
    }

    // ==================== shouldProxy bypass ====================

    private void startHookOplusProxyBroadcast() {
        try {
            String[] classNames = {
                "com.android.server.am.OplusProxyBroadcast",
                "com.android.server.am.OplusProxyBroadcastEx",
                "com.android.server.am.OplusBroadcastProxy"
            };
            boolean hooked = false;
            for (String className : classNames) {
                Class<?> oplusProxyBroadcastClass = XposedHelpers.findClassIfExists(className, classLoader);
                if (oplusProxyBroadcastClass != null) {
                    Class<?> resultEnum = XposedHelpers.findClassIfExists("com.android.server.am.OplusProxyBroadcast$RESULT", classLoader);
                    final Object notIncludeValue = resultEnum != null ? XposedHelpers.getStaticObjectField(resultEnum, "NOT_INCLUDE") : null;

                    for (Method targetMethod : oplusProxyBroadcastClass.getDeclaredMethods()) {
                        if ("shouldProxy".equals(targetMethod.getName())) {
                            final Method finalTargetMethod = targetMethod;
                            XposedBridge.hookMethod(targetMethod, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    String action = null;
                                    String pkgName = null;
                                    Intent foundIntent = null;

                                    // Extract Intent from args
                                    for (Object arg : param.args) {
                                        if (arg instanceof Intent) {
                                            foundIntent = (Intent) arg;
                                            break;
                                        }
                                        // Try extracting intent field from BroadcastRecord-like objects
                                        if (arg != null) {
                                            try {
                                                Object obj = XposedHelpers.getObjectField(arg, "intent");
                                                if (obj instanceof Intent) {
                                                    foundIntent = (Intent) obj;
                                                    break;
                                                }
                                            } catch (Throwable ignored) {}
                                        }
                                    }

                                    if (foundIntent != null) {
                                        action = foundIntent.getAction();
                                        if (foundIntent.getComponent() != null) {
                                            pkgName = foundIntent.getComponent().getPackageName();
                                        } else {
                                            pkgName = foundIntent.getPackage();
                                        }
                                    }

                                    // Fallback: scan args for action string
                                    if (action == null) {
                                        for (Object arg : param.args) {
                                            if (arg instanceof String && isFCMAction((String) arg)) {
                                                action = (String) arg;
                                                break;
                                            }
                                        }
                                    }

                                    // Fallback: scan args for package name
                                    if (pkgName == null) {
                                        for (Object arg : param.args) {
                                            if (arg instanceof String && targetIsAllow((String) arg)) {
                                                pkgName = (String) arg;
                                                break;
                                            }
                                        }
                                    }

                                    // Check FCM by action OR by Intent extras
                                    boolean isFcm = isFCMAction(action) || (foundIntent != null && isFCMIntent(foundIntent));
                                    if (isFcm && (pkgName == null || targetIsAllow(pkgName))) {
                                        printLog("[OplusProxyFix] shouldProxy bypass: pkg=" + pkgName + ", action=" + action, true);
                                        if (pkgName != null) {
                                            unfreeze(pkgName);
                                        }
                                        if (foundIntent != null) {
                                            foundIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                                        }
                                        if (notIncludeValue != null) {
                                            param.setResult(notIncludeValue);
                                        } else {
                                            // Fallback: determine result based on method return type
                                            Class<?> returnType = finalTargetMethod.getReturnType();
                                            if (returnType == boolean.class || returnType == Boolean.class) {
                                                param.setResult(false);
                                            } else {
                                                param.setResult(null);
                                            }
                                        }
                                    }
                                }
                            });
                            printLog("[OplusProxyFix] Hooked " + className + ".shouldProxy (" + targetMethod.getParameterCount() + " params)");
                            hooked = true;
                        }
                    }
                }
            }
            if (!hooked) {
                printLog("[OplusProxyFix] shouldProxy method not found in any class");
            }
        } catch (Throwable e) {
            printLog("[OplusProxyFix] hook error shouldProxy: " + e.getMessage());
        }
    }

    // ==================== OplusProxyWakeLock instance capture (SEPARATED from Hans) ====================

    private void startHookOplusProxyWakeLock() {
        for (String className : WAKELOCK_CLASSES) {
            try {
                Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
                if (clazz != null) {
                    // Hook constructor for instance capture
                    try {
                        XposedUtils.findAndHookConstructorAnyParam(clazz, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                s_oplusProxyWakeLock = param.thisObject;
                                printLog("[OplusProxyFix] WakeLock instance captured from constructor: " + param.thisObject.getClass().getName(), true);
                            }
                        });
                    } catch (Throwable ignored) {}

                    printLog("[OplusProxyFix] Hooked " + className + " constructor for WakeLock capture");
                }
            } catch (Throwable ignored) {}
        }
    }

    // ==================== OplusHansManager instance capture (SEPARATE variable) ====================

    private void startHookOplusHansManager() {
        for (String className : HANS_CLASSES) {
            try {
                Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
                if (clazz != null) {
                    // Hook constructor
                    try {
                        XposedUtils.findAndHookConstructorAnyParam(clazz, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                s_oplusHansManager = param.thisObject;
                                printLog("[OplusProxyFix] HansManager instance captured from constructor: " + param.thisObject.getClass().getName(), true);
                            }
                        });
                    } catch (Throwable ignored) {}

                    printLog("[OplusProxyFix] Hooked " + className + " constructor for Hans capture");
                }
            } catch (Throwable ignored) {}
        }
    }

    // ==================== Instance acquisition with fallback ====================

    private static Object getWakeLockInstance() {
        if (s_oplusProxyWakeLock != null) return s_oplusProxyWakeLock;
        // Fallback: try to find via static fields
        ClassLoader cl = s_systemClassLoader;
        for (String className : WAKELOCK_CLASSES) {
            try {
                Class<?> clazz = XposedHelpers.findClassIfExists(className, cl);
                if (clazz != null) {
                    // Try getInstance()
                    try {
                        Method getInstance = clazz.getDeclaredMethod("getInstance");
                        getInstance.setAccessible(true);
                        Object obj = getInstance.invoke(null);
                        if (obj != null) {
                            s_oplusProxyWakeLock = obj;
                            printLog("[OplusProxyFix] WakeLock acquired via " + className + ".getInstance()", true);
                            return obj;
                        }
                    } catch (Throwable ignored) {}
                    // Try static fields of same type
                    for (Field field : clazz.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers())) {
                            try {
                                field.setAccessible(true);
                                if (clazz.isAssignableFrom(field.getType())) {
                                    Object obj = field.get(null);
                                    if (obj != null) {
                                        s_oplusProxyWakeLock = obj;
                                        printLog("[OplusProxyFix] WakeLock acquired via static field " + className + "." + field.getName(), true);
                                        return obj;
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object getHansManagerInstance() {
        if (s_oplusHansManager != null) return s_oplusHansManager;
        ClassLoader cl = s_systemClassLoader;
        for (String className : HANS_CLASSES) {
            try {
                Class<?> clazz = XposedHelpers.findClassIfExists(className, cl);
                if (clazz != null) {
                    // Try getInstance()
                    try {
                        Method getInstance = clazz.getDeclaredMethod("getInstance");
                        getInstance.setAccessible(true);
                        Object obj = getInstance.invoke(null);
                        if (obj != null) {
                            s_oplusHansManager = obj;
                            printLog("[OplusProxyFix] HansManager acquired via " + className + ".getInstance()", true);
                            return obj;
                        }
                    } catch (Throwable ignored) {}
                    // Try static fields
                    for (Field field : clazz.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers())) {
                            try {
                                field.setAccessible(true);
                                if (clazz.isAssignableFrom(field.getType())) {
                                    Object obj = field.get(null);
                                    if (obj != null) {
                                        s_oplusHansManager = obj;
                                        printLog("[OplusProxyFix] HansManager acquired via static field " + className + "." + field.getName(), true);
                                        return obj;
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // ==================== UID resolution ====================

    private static int getTargetUidFromPackageName(String packageName) {
        if (packageName != null && context != null) {
            try {
                PackageManager pm = context.getPackageManager();
                return pm.getPackageUid(packageName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                printLog("[OplusProxyFix] Package not found: " + packageName);
            }
        }
        return -1;
    }

    // ==================== Multi-path unfreeze ====================

    public static void unfreeze(String target) {
        int uid = getTargetUidFromPackageName(target);
        if (uid < 0) {
            printLog("[OplusProxyFix] unfreeze skipped: cannot resolve UID for " + target);
            return;
        }

        // Path A: OplusProxyWakeLock.unfreezeIfNeed
        if (tryUnfreezeViaWakeLock(uid, target)) return;

        // Path B: OplusHansManager unfreeze methods
        if (tryUnfreezeViaHansManager(uid, target)) return;

        printLog("[OplusProxyFix] All unfreeze paths failed for " + target + " (uid=" + uid + ")");
    }

    private static boolean tryUnfreezeViaWakeLock(int uid, String target) {
        Object instance = getWakeLockInstance();
        if (instance == null) {
            printLog("[OplusProxyFix] WakeLock instance unavailable");
            return false;
        }

        WorkSource ws = new WorkSource();
        try {
            // WorkSource.add(int uid) is @hide, use reflection
            Method addMethod = WorkSource.class.getDeclaredMethod("add", int.class);
            addMethod.setAccessible(true);
            addMethod.invoke(ws, uid);
        } catch (Throwable ignored) {
            // Fallback: empty WorkSource, uid is already passed as first arg to unfreezeIfNeed
        }
        String tag = "FCMXX";

        if (!s_signatureDetected) {
            // Auto-detect 4-param vs 3-param
            try {
                XposedHelpers.callMethod(instance, "unfreezeIfNeed", uid, ws, tag, "FCMFix");
                s_useFourParams = true;
                s_signatureDetected = true;
                printLog("[OplusProxyFix] WakeLock unfreeze (4-param) success: " + target + ", uid=" + uid, true);
                return true;
            } catch (Throwable e1) {
                try {
                    XposedHelpers.callMethod(instance, "unfreezeIfNeed", uid, ws, tag);
                    s_useFourParams = false;
                    s_signatureDetected = true;
                    printLog("[OplusProxyFix] WakeLock unfreeze (3-param) success: " + target + ", uid=" + uid, true);
                    return true;
                } catch (Throwable e2) {
                    printLog("[OplusProxyFix] WakeLock unfreezeIfNeed failed: " + e2.getMessage());
                    return false;
                }
            }
        } else {
            try {
                if (s_useFourParams) {
                    XposedHelpers.callMethod(instance, "unfreezeIfNeed", uid, ws, tag, "FCMFix");
                } else {
                    XposedHelpers.callMethod(instance, "unfreezeIfNeed", uid, ws, tag);
                }
                printLog("[OplusProxyFix] WakeLock unfreeze: " + target + ", uid=" + uid, true);
                return true;
            } catch (Throwable e) {
                printLog("[OplusProxyFix] WakeLock unfreeze error: " + e.getMessage());
                return false;
            }
        }
    }

    private static boolean tryUnfreezeViaHansManager(int uid, String target) {
        Object hans = getHansManagerInstance();
        if (hans == null) {
            printLog("[OplusProxyFix] HansManager instance unavailable");
            return false;
        }

        // Try various method names used across ColorOS versions
        String[] methodNames = {"unfreezeApp", "thawApp", "unfreezeProcess", "unfreezeUid"};

        for (String methodName : methodNames) {
            // Try (int uid, String reason)
            try {
                XposedHelpers.callMethod(hans, methodName, uid, "FCMFix");
                printLog("[OplusProxyFix] Hans." + methodName + "(uid, reason) success: " + target + ", uid=" + uid, true);
                return true;
            } catch (Throwable ignored) {}

            // Try (int uid)
            try {
                XposedHelpers.callMethod(hans, methodName, uid);
                printLog("[OplusProxyFix] Hans." + methodName + "(uid) success: " + target + ", uid=" + uid, true);
                return true;
            } catch (Throwable ignored) {}

            // Try (String pkg, int userId, String reason)
            try {
                XposedHelpers.callMethod(hans, methodName, target, 0, "FCMFix");
                printLog("[OplusProxyFix] Hans." + methodName + "(pkg, userId, reason) success: " + target, true);
                return true;
            } catch (Throwable ignored) {}
        }

        printLog("[OplusProxyFix] All HansManager unfreeze methods failed for " + target);
        return false;
    }

    // ==================== GMS Restriction bypass ====================

    private void startHookRegisterGmsRestrictObserver() {
        String[] classNames = {
            "com.android.server.hans.scene.OplusBgSceneManager",
            "com.android.server.hans.OplusBgSceneManager",
            "com.android.server.hans.scene.OplusHansSceneManager"
        };
        for (String className : classNames) {
            try {
                XposedHelpers.findAndHookMethod(className, classLoader, "registerGmsRestrictObserver", XC_MethodReplacement.DO_NOTHING);
                printLog("[OplusProxyFix] Hooked " + className + ".registerGmsRestrictObserver");
                return;
            } catch (Throwable ignored) {}
        }
        printLog("[OplusProxyFix] Failed to hook registerGmsRestrictObserver");
    }

    private void startHookUpdateGmsRestrict() {
        String[] classNames = {
            "com.android.server.hans.scene.OplusBgSceneManager",
            "com.android.server.hans.OplusBgSceneManager",
            "com.android.server.hans.scene.OplusHansSceneManager"
        };
        for (String className : classNames) {
            try {
                XposedHelpers.findAndHookMethod(className, classLoader, "updateGmsRestrict", XC_MethodReplacement.DO_NOTHING);
                printLog("[OplusProxyFix] Hooked " + className + ".updateGmsRestrict");
                return;
            } catch (Throwable ignored) {}
        }
        printLog("[OplusProxyFix] Failed to hook updateGmsRestrict");
    }

    private void startHookIsGoogleRestricInfoOn() {
        String[] classNames = {
            "com.android.server.am.OplusAppStartupManager$OplusStartupStrategy",
            "com.android.server.am.OplusAppStartupManagerService$OplusStartupStrategy",
            "com.android.server.am.OplusAppStartupManager$StartupStrategy"
        };
        for (String className : classNames) {
            try {
                XposedHelpers.findAndHookMethod(className, classLoader, "isGoogleRestricInfoOn", int.class, XC_MethodReplacement.returnConstant(false));
                printLog("[OplusProxyFix] Hooked " + className + ".isGoogleRestricInfoOn");
                return;
            } catch (Throwable ignored) {}
        }
        printLog("[OplusProxyFix] Failed to hook isGoogleRestricInfoOn");
    }
}
