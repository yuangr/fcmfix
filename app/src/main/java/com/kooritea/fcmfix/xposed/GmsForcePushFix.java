package com.kooritea.fcmfix.xposed;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;
import com.kooritea.fcmfix.util.XposedUtils;

import java.lang.reflect.Method;

public class GmsForcePushFix extends XposedModule {

    public GmsForcePushFix(ClassLoader classLoader) {
        super(classLoader);
        try {
            startHookPackageManager();
        } catch (Throwable e) {
            printLog("hook error GmsForcePushFix:" + e.getMessage());
        }
    }

    private boolean isFCMStack() {
        for (StackTraceElement el : new Throwable().getStackTrace()) {
            String className = el.getClassName();
            if (className.contains("chimera") || className.contains("gcm") || className.contains("firebase")) {
                return true;
            }
        }
        return false;
    }

    private void startHookPackageManager() {
        // We hook ApplicationPackageManager inside the GMS process
        Class<?> pmsClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader);
        
        // Hook all methods named getApplicationInfo and getPackageInfo
        Method[] methods = pmsClass.getDeclaredMethods();
        int hookedCount = 0;
        
        for (Method method : methods) {
            String name = method.getName();
            if (("getApplicationInfo".equals(name) || "getPackageInfo".equals(name)) && method.getParameterTypes().length >= 1) {
                // Ensure the first parameter is String (packageName)
                if (method.getParameterTypes()[0] != String.class) {
                    continue;
                }
                
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (param.getResult() == null) {
                                return;
                            }
                            
                            String packageName = (String) param.args[0];
                            if (packageName == null || !targetIsAllow(packageName)) {
                                return;
                            }
                            if (!isFCMStack()) {
                                return;
                            }
                            
                            Object result = param.getResult();
                            ApplicationInfo appInfo = null;
                            
                            if (result instanceof ApplicationInfo) {
                                appInfo = (ApplicationInfo) result;
                            } else if (result instanceof PackageInfo) {
                                appInfo = ((PackageInfo) result).applicationInfo;
                            }
                            
                            if (appInfo != null) {
                                // Check if FLAG_STOPPED is set
                                if ((appInfo.flags & ApplicationInfo.FLAG_STOPPED) != 0) {
                                    // Remove FLAG_STOPPED so GMS thinks it is active
                                    appInfo.flags &= ~ApplicationInfo.FLAG_STOPPED;
                                    printLog("[GmsForcePushFix] Stripped FLAG_STOPPED for target=" + packageName, true);
                                }
                            }
                        }
                    });
                    hookedCount++;
                    printLog("[GmsForcePushFix] Hooked " + name + " with " + method.getParameterCount() + " params");
                } catch (Throwable e) {
                    printLog("[GmsForcePushFix] Failed to hook " + name + ": " + e.getMessage());
                }
            } else if (("isPackageStopped".equals(name) || "isPackageStoppedForUser".equals(name)) && method.getParameterTypes().length >= 1) {
                if (method.getParameterTypes()[0] != String.class) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String packageName = (String) param.args[0];
                            if (packageName != null && targetIsAllow(packageName)) {
                                if (isFCMStack()) {
                                    param.setResult(false);
                                    printLog("[GmsForcePushFix] Forced " + name + " to false for " + packageName, true);
                                }
                            }
                        }
                    });
                    hookedCount++;
                    printLog("[GmsForcePushFix] Hooked " + name + " with " + method.getParameterCount() + " params");
                } catch (Throwable e) {
                    printLog("[GmsForcePushFix] Failed to hook " + name + ": " + e.getMessage());
                }
            } else if (name.startsWith("queryBroadcastReceivers") && method.getParameterTypes().length >= 1) {
                if (method.getParameterTypes()[0] != android.content.Intent.class) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            android.content.Intent intent = (android.content.Intent) param.args[0];
                            if (intent != null) {
                                String pkg = intent.getPackage();
                                if (pkg != null && targetIsAllow(pkg)) {
                                    intent.addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                                    printLog("[GmsForcePushFix] Injected FLAG_INCLUDE_STOPPED_PACKAGES into " + name + " for " + pkg, true);
                                }
                            }
                        }
                    });
                    hookedCount++;
                    printLog("[GmsForcePushFix] Hooked " + name + " with " + method.getParameterCount() + " params");
                } catch (Throwable e) {
                    printLog("[GmsForcePushFix] Failed to hook " + name + ": " + e.getMessage());
                }
            }
        }
        
        if (hookedCount == 0) {
            printLog("[GmsForcePushFix] Warning: No getApplicationInfo/getPackageInfo methods hooked!");
        }
    }
}
