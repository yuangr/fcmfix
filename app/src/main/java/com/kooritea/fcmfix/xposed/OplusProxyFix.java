package com.kooritea.fcmfix.xposed;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.WorkSource;

import com.kooritea.fcmfix.util.XposedUtils;

import java.lang.reflect.Method;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XC_MethodReplacement;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

public class OplusProxyFix extends XposedModule {

    private static Object s_oplusProxyWakeLock = null;
    private static volatile boolean s_useFourParams = false;
    private static volatile boolean s_signatureDetected = false;

    public OplusProxyFix(ClassLoader classLoader) {
        super(classLoader);
        try{
            this.startHookOplusProxyWakeLock();
            this.startHookOplusProxyBroadcast();
        }catch(Throwable e) {
            printLog("hook error OplusProxy:" + e.getMessage());
        }
        try {
            this.startHookRegisterGmsRestrictObserver(); // 阻止Hans监听GMS状态更新
        } catch (Throwable e) {
            printLog("hook error registerGmsRestrictObserver:" + e.getMessage());
        }
        try {
            this.startHookUpdateGmsRestrict(); // 拦截Hans更新GMS限制状态
        } catch (Throwable e) {
            printLog("hook error updateGmsRestrict:" + e.getMessage());
        }
        try {
            this.startHookIsGoogleRestricInfoOn(); // 阻止判断GMS限制
        } catch (Throwable e) {
            printLog("hook error isGoogleRestricInfoOn:" + e.getMessage());
        }
        /*
        try {
            this.startHookIsGmsApp(); // 阻止Hans对GMS进行特殊处理
        } catch (Throwable e) {
            printLog("hook error isGmsApp:" + e.getMessage());
        }
        */
    }

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
                            XposedBridge.hookMethod(targetMethod, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    String action = null;
                                    String pkgName = null;

                                    for (Object arg : param.args) {
                                        if (arg instanceof Intent) {
                                            Intent intent = (Intent) arg;
                                            action = intent.getAction();
                                            if (intent.getComponent() != null) {
                                                pkgName = intent.getComponent().getPackageName();
                                            } else {
                                                pkgName = intent.getPackage();
                                            }
                                            break;
                                        }
                                    }

                                    if (action == null) {
                                        for (Object arg : param.args) {
                                            if (arg instanceof String && isFCMAction((String) arg)) {
                                                action = (String) arg;
                                                break;
                                            }
                                        }
                                    }

                                    if (pkgName == null) {
                                        for (Object arg : param.args) {
                                            if (arg instanceof String && targetIsAllow((String) arg)) {
                                                pkgName = (String) arg;
                                                break;
                                            }
                                        }
                                    }

                                    if ((isFCMAction(action) || (action != null && isFCMAction(action))) && (pkgName == null || targetIsAllow(pkgName))) {
                                        printLog("OplusProxyBroadcast: bypass pkg=" + pkgName + ", action=" + action, true);
                                        if (notIncludeValue != null) {
                                            param.setResult(notIncludeValue);
                                        }
                                    }
                                }
                            });
                            printLog("Hooked " + className + ".shouldProxy (" + targetMethod.getParameterCount() + " params)");
                            hooked = true;
                        }
                    }
                }
            }
            if (!hooked) {
                printLog("OplusProxyBroadcast.shouldProxy method not found in any class");
            }
        } catch (Throwable e) {
            printLog("hook error OplusProxyBroadcast.shouldProxy: " + e.getMessage());
        }
    }

    private void startHookOplusProxyWakeLock() {
        // Try multiple class names for ColorOS 15/16 compatibility
        String[] classNames = {
            "com.android.server.power.OplusProxyWakeLock",
            "com.android.server.power.OplusPowerWakeLock",
            "com.android.server.power.OplusWakeLockProxy"
        };
        for (String className : classNames) {
            try {
                Class<?> oplusWakelockClass = XposedHelpers.findClass(className, classLoader);
                XposedUtils.findAndHookConstructorAnyParam(oplusWakelockClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (s_oplusProxyWakeLock != null) {
                            printLog("warn: OplusProxyWakeLock constructed multiple times!");
                            return;
                        }
                        s_oplusProxyWakeLock = param.thisObject;
                        printLog("OplusProxyWakeLock instance captured from " + param.thisObject.getClass().getName());
                    }
                });
                printLog("Hooked " + className + " constructor for unfreeze");
                return;
            } catch (Throwable ignored) {}
        }
        printLog("[OplusProxyFix] Failed to hook OplusProxyWakeLock: no matching class found, unfreeze will not work!");
    }

    private static int getTargetUidFromPackageName(String packageName) {
        // Convert package name to UID
        if (packageName != null) {
            try {
                PackageManager pm = context.getPackageManager();
                return pm.getPackageUid(packageName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                printLog("error: Package not found: " + packageName);
            }
        }

        // Default to an invalid UID if we couldn't determine the target
        return -1;
    }

    public static void unfreeze(String target) {
        if (s_oplusProxyWakeLock == null) {
            return;
        }

        int uid = getTargetUidFromPackageName(target);
        if (uid < 0) {
            return;
        }

        /*
        XXX only tested on OnePlus13T ColorOS 15
        unfreezeIfNeed: 3 args
            00 int uid,
            01 WorkSource ws,
            02 String tag
         */

        WorkSource ws = new WorkSource();
        String tag = "FCMXX";

        if (!s_signatureDetected) {
            try {
                XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", uid, ws, tag, "FCMFix");
                s_useFourParams = true;
            } catch (Throwable e) {
                // 降级用3参
                XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", uid, ws, tag);
                s_useFourParams = false;
            }
            s_signatureDetected = true;
        } else {
            // 后续调用直接用缓存的
            try {
                if (s_useFourParams) {
                    XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", uid, ws, tag, "FCMFix");
                    printLog("unfreeze " + target + ", uid=" + uid);
                } else {
                    XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", uid, ws, tag);
                    printLog("unfreeze " + target + ", uid=" + uid);
                }
            } catch (Throwable ignored) {
                // 静默或log
            }
        }
    }

    private void startHookRegisterGmsRestrictObserver() {
        // Try multiple class names for ColorOS 15/16 compatibility
        String[] classNames = {
            "com.android.server.hans.scene.OplusBgSceneManager",
            "com.android.server.hans.OplusBgSceneManager",
            "com.android.server.hans.scene.OplusHansSceneManager"
        };
        for (String className : classNames) {
            try {
                XposedHelpers.findAndHookMethod(className, classLoader, "registerGmsRestrictObserver", XC_MethodReplacement.DO_NOTHING);
                printLog("Hooked " + className + ".registerGmsRestrictObserver");
                return;
            } catch (Throwable ignored) {}
        }
        printLog("Failed to hook registerGmsRestrictObserver: no matching class found");
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
                printLog("Hooked " + className + ".updateGmsRestrict");
                return;
            } catch (Throwable ignored) {}
        }
        printLog("Failed to hook updateGmsRestrict: no matching class found");
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
                printLog("Hooked " + className + ".isGoogleRestricInfoOn");
                return;
            } catch (Throwable ignored) {}
        }
        printLog("Failed to hook isGoogleRestricInfoOn: no matching class found");
    }

    private void startHookIsGmsApp() {
        XposedHelpers.findAndHookMethod("com.android.server.hans.OplusHansDBConfig", classLoader, "isGmsApp", int.class, XC_MethodReplacement.returnConstant(false));
    }

}
