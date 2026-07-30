package com.kooritea.fcmfix.xposed;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

import com.kooritea.fcmfix.util.IceboxUtils;
import com.kooritea.fcmfix.util.XposedUtils;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class BroadcastFix extends XposedModule {
    private static final ExecutorService iceboxExecutor = Executors.newCachedThreadPool();
    private static final ConcurrentHashMap<String, AtomicBoolean> iceboxUnfreezeTasks = new ConcurrentHashMap<>();

    public BroadcastFix(ClassLoader classLoader) {
        super(classLoader);
        try{
            this.startHookBroadcastIntentLocked();
        }catch (Throwable e) {
            printLog("hook error broadcastIntentLocked:" + e.getMessage());
        }
//        try{
//            this.startHookScheduleResultTo();
//        }catch (Throwable e) {
//            printLog("hook error com.android.server.am.BroadcastQueueModernImpl.scheduleResultTo:" + e.getMessage());
//        }
        try{
            this.startHookBroadcastSkipPolicy();
        }catch (Throwable e) {
            printLog("hook error BroadcastSkipPolicy:" + e.getMessage());
        }
    }

    protected void startHookBroadcastIntentLocked(){
        Method targetMethod = null;
        int intent_args_index = -1;
        int appOp_args_index = -1;

        // === Path 0: broadcastIntentWithFeature (API 30+) ===
        String[] possibleClasses = {
            "com.android.server.am.BroadcastController",
            "com.android.server.am.ActivityManagerService"
        };
        for (String className : possibleClasses) {
            targetMethod = XposedUtils.tryFindMethodMostParam(classLoader, className, "broadcastIntentWithFeature");
            if (targetMethod != null) {
                printLog("[BroadcastFix] Found broadcastIntentWithFeature in " + className + " with " + targetMethod.getParameterCount() + " params");
                break;
            }
        }
        
        // === Path 1: Android 15+ BroadcastController (API >= 35) fallback ===
        if(targetMethod == null && Build.VERSION.SDK_INT >= 35){
            String[] controllerClasses = {
                "com.android.server.am.BroadcastController",
                "com.android.server.am.BroadcastQueueModernImpl"
            };
            for (String className : controllerClasses) {
                targetMethod = XposedUtils.tryFindMethodMostParam(classLoader, className, "broadcastIntentLocked");
                if (targetMethod != null) {
                    printLog("[BroadcastFix] Found broadcastIntentLocked in " + className + " with " + targetMethod.getParameterCount() + " params");
                    break;
                }
            }
            if(targetMethod != null){
                Parameter[] parameters = targetMethod.getParameters();
                // Parameter name detection first
                for(int i = 0; i < parameters.length; i++){
                    if("appOp".equals(parameters[i].getName()) && parameters[i].getType() == int.class){
                        appOp_args_index = i;
                    }
                    if("intent".equals(parameters[i].getName()) && parameters[i].getType() == Intent.class){
                        intent_args_index = i;
                    }
                }
                // Fallback: detect by type pattern
                if(intent_args_index == -1){
                    for(int i = 0; i < parameters.length; i++){
                        if(parameters[i].getType() == Intent.class){
                            intent_args_index = i;
                            break;
                        }
                    }
                }
                if(appOp_args_index == -1){
                    for(int i = parameters.length - 1; i >= 0; i--){
                        if(parameters[i].getType() == int.class){
                            appOp_args_index = i;
                            break;
                        }
                    }
                }
                printLog("[BroadcastFix] Controller detection result: intent_idx=" + intent_args_index + ", appOp_idx=" + appOp_args_index);
            } else {
                printLog("[BroadcastFix] BroadcastController/BroadcastQueueModernImpl not found, falling back to AMS");
            }
        }

        // === Path 2: Fallback to ActivityManagerService (API 29-34, or API 35+ if Path 1 failed) ===
        if(targetMethod == null){
            targetMethod = XposedUtils.tryFindMethodMostParam(classLoader,"com.android.server.am.ActivityManagerService","broadcastIntentLocked");
            if(targetMethod != null){
                printLog("[BroadcastFix] Found method in ActivityManagerService with " + targetMethod.getParameterCount() + " params");
                Parameter[] parameters = targetMethod.getParameters();
                if(Build.VERSION.SDK_INT == Build.VERSION_CODES.Q){
                    intent_args_index = 2;
                    appOp_args_index = 9;
                }else if(Build.VERSION.SDK_INT == Build.VERSION_CODES.R){
                    intent_args_index = 3;
                    appOp_args_index = 10;
                }else if(Build.VERSION.SDK_INT == 31 || Build.VERSION.SDK_INT == 32){
                    intent_args_index = 3;
                    if(parameters.length > 11 && parameters[11].getType() == int.class){
                        appOp_args_index = 11;
                    } else if(parameters.length > 12 && parameters[12].getType() == int.class){
                        appOp_args_index = 12;
                    }
                }else if(Build.VERSION.SDK_INT == 33){
                    intent_args_index = 3;
                    appOp_args_index = 12;
                } else if(Build.VERSION.SDK_INT >= 34){
                    intent_args_index = 3;
                    if(parameters.length > 12 && parameters[12].getType() == int.class){
                        appOp_args_index = 12;
                    } else if(parameters.length > 13 && parameters[13].getType() == int.class){
                        appOp_args_index = 13;
                    }
                }
            } else {
                printLog("[BroadcastFix] ActivityManagerService.broadcastIntentLocked not found either!");
            }
        }

        // Dynamic fallback for parameter indices
        if(targetMethod != null){
            Parameter[] parameters = targetMethod.getParameters();
            if(intent_args_index == -1 || appOp_args_index == -1 ||
               intent_args_index >= parameters.length || (appOp_args_index != -1 && appOp_args_index >= parameters.length) ||
               parameters[intent_args_index].getType() != Intent.class || (appOp_args_index >= 0 && parameters[appOp_args_index].getType() != int.class)){
                intent_args_index = -1;
                appOp_args_index = -1;
                for(int i = 0; i < parameters.length; i++){
                    if("appOp".equals(parameters[i].getName()) && parameters[i].getType() == int.class){
                        appOp_args_index = i;
                    }
                    if("intent".equals(parameters[i].getName()) && parameters[i].getType() == Intent.class){
                        intent_args_index = i;
                    }
                }
                if(intent_args_index == -1){
                    for(int i = 0; i < parameters.length; i++){
                        if(parameters[i].getType() == Intent.class){
                            intent_args_index = i;
                            break;
                        }
                    }
                }
                if(appOp_args_index == -1){
                    for(int i = parameters.length - 1; i >= 0; i--){
                        if(parameters[i].getType() == int.class){
                            appOp_args_index = i;
                            break;
                        }
                    }
                }
            }
            printLog("[BroadcastFix] Detection result: intent_idx=" + intent_args_index + ", appOp_idx=" + appOp_args_index);
        }

        if(targetMethod != null && intent_args_index >= 0 &&
           intent_args_index < targetMethod.getParameterCount() &&
           targetMethod.getParameters()[intent_args_index].getType() == Intent.class &&
           (appOp_args_index == -1 || (appOp_args_index < targetMethod.getParameterCount() && targetMethod.getParameters()[appOp_args_index].getType() == int.class))){
            createBroadcastIntentLockedHooker(intent_args_index, appOp_args_index, targetMethod);
        } else {
            printLog("[BroadcastFix] broadcastIntent hook 位置查找失败，fcmfix将不会工作。targetMethod=" + (targetMethod != null ? targetMethod.getDeclaringClass().getName() : "null") + " intent_idx=" + intent_args_index + " appOp_idx=" + appOp_args_index);
        }
    }

    private String extractTargetPackage(Intent intent, Object[] args) {
        if (intent == null) return null;
        if (intent.getComponent() != null && intent.getComponent().getPackageName() != null) {
            return intent.getComponent().getPackageName();
        }
        if (intent.getPackage() != null) {
            return intent.getPackage();
        }
        return null;
    }

    protected void createBroadcastIntentLockedHooker(int intent_args_index, int appOp_args_index, Method method){
        printLog("Android API: " + Build.VERSION.SDK_INT);
        printLog("appOp_args_index: " + appOp_args_index);
        printLog("intent_args_index: " + intent_args_index);
        printLog("hook target: " + method.getDeclaringClass().getName());
        final int finalIntent_args_index = intent_args_index;
        final int finalAppOp_args_index = appOp_args_index;

        XposedBridge.hookMethod(method,new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                if(methodHookParam.args[finalIntent_args_index] == null){
                    return;
                }
                Intent intent = (Intent) methodHookParam.args[finalIntent_args_index];
                if(isFCMIntent(intent)){
                    String target = extractTargetPackage(intent, methodHookParam.args);
                    boolean hasStoppedFlag = (intent.getFlags() & Intent.FLAG_INCLUDE_STOPPED_PACKAGES) != 0;
                    printLog("[BroadcastFix] FCM Intent intercepted: action=" + intent.getAction() + ", target=" + target + ", hasStoppedFlag=" + hasStoppedFlag);

                    if(!hasStoppedFlag){
                        // If target is in allowList OR target is null (safeguard for FCM intents), add FLAG_INCLUDE_STOPPED_PACKAGES
                        if(target == null || targetIsAllow(target)){
                            if(finalAppOp_args_index >= 0) {
                                int i = (Integer) methodHookParam.args[finalAppOp_args_index];
                                if (i == -1) {
                                    methodHookParam.args[finalAppOp_args_index] = 11;
                                }
                            }
                            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                            printLog("[BroadcastFix] Added FLAG_INCLUDE_STOPPED_PACKAGES for target=" + target, true);

                            if (getBooleanConfig("includeIceBoxDisableApp",false) && target != null && !IceboxUtils.isAppEnabled(context, target)) {
                                printLog("Waiting for IceBox to activate the app: " + target, true);
                                methodHookParam.setResult(false);
                                final String finalTarget = target;
                                AtomicBoolean isUnfreezing = iceboxUnfreezeTasks.computeIfAbsent(finalTarget, k -> new AtomicBoolean(false));
                                if (isUnfreezing.compareAndSet(false, true)) {
                                    iceboxExecutor.submit(() -> {
                                        try {
                                            IceboxUtils.activeApp(context, finalTarget);
                                            for (int i1 = 0; i1 < 300; i1++) {
                                                if (!IceboxUtils.isAppEnabled(context, finalTarget)) {
                                                    try {
                                                        Thread.sleep(100);
                                                    } catch (Throwable e) {
                                                        printLog("Send Forced Start Broadcast Error: " + finalTarget + " " + e.getMessage(), true);
                                                    }
                                                } else {
                                                    break;
                                                }
                                            }
                                            if(IceboxUtils.isAppEnabled(context, finalTarget)){
                                                printLog("Send Forced Start Broadcast: " + finalTarget, true);
                                            }else{
                                                printLog("Waiting for IceBox to activate the app timed out: " + finalTarget, true);
                                            }
                                            XposedBridge.invokeOriginalMethod(methodHookParam.method, methodHookParam.thisObject, methodHookParam.args);
                                        } catch (Throwable e) {
                                            printLog("Send Forced Start Broadcast Error: " + finalTarget + " " + e.getMessage(), true);
                                        } finally {
                                            isUnfreezing.set(false);
                                        }
                                    });
                                }
                            }else{
                                printLog("Send Forced Start Broadcast: " + target, true);
                            }
                            // cos15/16 unfreeze
                            if (target != null) {
                                OplusProxyFix.unfreeze(target);
                            }
                        } else {
                            printLog("[BroadcastFix] Target " + target + " is NOT in allowList (allowList size: " + (allowList != null ? allowList.size() : 0) + ")");
                        }
                    }
                }
            }
        });
    }

    protected void startHookScheduleResultTo(){
        Method method = XposedUtils.findMethod(XposedHelpers.findClass("com.android.server.am.BroadcastQueueModernImpl",classLoader),"scheduleResultTo",1);
        XposedBridge.hookMethod(method,new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                if(!isBootComplete){
                    return;
                }
                if(methodHookParam.args[0] == null || XposedHelpers.getObjectField(methodHookParam.args[0],"resultTo") == null || XposedHelpers.getObjectField(methodHookParam.args[0],"intent") == null || XposedHelpers.getObjectField(methodHookParam.args[0],"resultCode") == null){
                    return;
                }
                Intent intent = (Intent)XposedHelpers.getObjectField(methodHookParam.args[0],"intent");
                int resultCode = (int) XposedHelpers.getObjectField(methodHookParam.args[0],"resultCode");
                String packageName = intent.getPackage();
                if(resultCode != -1 && getBooleanConfig("noResponseNotification",false) && targetIsAllow(packageName)){
                    try{
                        Intent notifyIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
                        if(notifyIntent!=null){
                            notifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            PendingIntent pendingIntent = PendingIntent.getActivity(
                                    context, 0, notifyIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
                            createFcmfixChannel(notificationManager);
                            NotificationCompat.Builder notification = new NotificationCompat.Builder(context, "fcmfix")
                                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                                    .setContentTitle("FCM Message")
                                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
                            Bitmap icon = getAppIcon(packageName);
                            if(icon != null){
                                notification.setLargeIcon(icon);
                            }
                            notification.setContentIntent(pendingIntent).setAutoCancel(true);
                            notificationManager.notify((int) System.currentTimeMillis(), notification.build());
                        }else{
                            printLog("无法获取目标应用active: " + packageName,false);
                        }
                    }catch (Throwable e){
                        printLog(e.getMessage(),false);
                    }
                }
            }
        });
    }

    protected void startHookBroadcastSkipPolicy() {
        try {
            Class<?> policyClass = XposedHelpers.findClassIfExists("com.android.server.am.BroadcastSkipPolicy", classLoader);
            if (policyClass != null) {
                for (Method m : policyClass.getDeclaredMethods()) {
                    if ("shouldSkipMessage".equals(m.getName()) || "shouldSkip".equals(m.getName())) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                Intent intent = null;
                                for (Object arg : param.args) {
                                    if (arg instanceof Intent) {
                                        intent = (Intent) arg;
                                        break;
                                    } else if (arg != null) {
                                        try {
                                            Object obj = XposedHelpers.getObjectField(arg, "intent");
                                            if (obj instanceof Intent) {
                                                intent = (Intent) obj;
                                                break;
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                }
                                if (intent != null && isFCMIntent(intent)) {
                                    String target = extractTargetPackage(intent, param.args);
                                    if (target == null || targetIsAllow(target)) {
                                        printLog("[BroadcastFix] BroadcastSkipPolicy bypassed for " + target, true);
                                        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                                        if (target != null) {
                                            OplusProxyFix.unfreeze(target);
                                        }
                                        // Check return type, it might be String (skip reason) or boolean (should skip)
                                        if (m.getReturnType() == boolean.class) {
                                            param.setResult(false);
                                        } else if (m.getReturnType() == String.class) {
                                            param.setResult(null); // null means don't skip
                                        }
                                    }
                                }
                            }
                        });
                        printLog("[BroadcastFix] Hooked BroadcastSkipPolicy." + m.getName());
                    }
                }
            }
        } catch (Throwable e) {
            printLog("hook error BroadcastSkipPolicy: " + e.getMessage());
        }
    }

    private static Bitmap getAppIcon(String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            Drawable drawable = pm.getApplicationIcon(appInfo);
            if (drawable instanceof BitmapDrawable) {
                return ((BitmapDrawable) drawable).getBitmap();
            } else {
                Bitmap bitmap = Bitmap.createBitmap(
                        drawable.getIntrinsicWidth(),
                        drawable.getIntrinsicHeight(),
                        Bitmap.Config.ARGB_8888);
                drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
                drawable.draw(new android.graphics.Canvas(bitmap));
                return bitmap;
            }
        } catch (Throwable e) {
            return null;
        }
    }
}
