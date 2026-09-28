package com.kooritea.fcmfix.xposed;

import android.app.PendingIntent;
import android.content.Context;
import android.os.Binder;
import com.kooritea.fcmfix.core.PendingBroadcasts;
import com.kooritea.fcmfix.core.PushOrigin;
import com.kooritea.fcmfix.util.PushTrust;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

import com.kooritea.fcmfix.util.IceboxUtils;
import com.kooritea.fcmfix.util.XposedUtils;
import java.util.concurrent.Executors;

public class BroadcastFix extends XposedModule {
    private static final PendingBroadcasts<String> pendingBroadcasts = new PendingBroadcasts<>(
            Executors.newFixedThreadPool(2), 256);

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

    protected void startHookBroadcastIntentLocked() {
        Method target = null;
        String[] owners = {"com.android.server.am.BroadcastController", "com.android.server.am.ActivityManagerService"};
        for (String owner : owners) {
            target = XposedUtils.tryFindMethodMostParam(classLoader, owner, "broadcastIntentWithFeature");
            if (target != null) break;
        }
        if (target == null) for (String owner : owners) {
            target = XposedUtils.tryFindMethodMostParam(classLoader, owner, "broadcastIntentLocked");
            if (target != null) break;
        }
        if (target == null) throw new NoSuchMethodError("broadcast entry unavailable");
        int intentIndex = -1;
        for (int i = 0; i < target.getParameterCount(); i++) {
            if (target.getParameterTypes()[i] == Intent.class) { intentIndex = i; break; }
        }
        if (intentIndex < 0) throw new NoSuchMethodError("broadcast Intent parameter unavailable");
        createBroadcastHook(intentIndex, target);
    }

    private static int entryUserId(Method method, Object[] args, int senderUid) {
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if ("userId".equals(parameters[i].getName()) && parameters[i].getType() == int.class) return (Integer) args[i];
        }
        // Only this verified binder entry contract ends with userId; never mutate it.
        if ("broadcastIntentWithFeature".equals(method.getName()) && parameters.length > 0
                && parameters[parameters.length - 1].getType() == int.class) return (Integer) args[parameters.length - 1];
        return senderUid >= 10000 ? senderUid / 100000 : -1;
    }

    private void createBroadcastHook(int intentIndex, Method method) {
        boolean binderEntry = "broadcastIntentWithFeature".equals(method.getName());
        printLog("[BroadcastFix] Hook " + method + "; AppOps and userId are unchanged");
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Intent intent = (Intent) param.args[intentIndex];
                String target = PushTrust.targetPackage(intent);
                int senderUid = PushTrust.entrySenderUid(param.args, binderEntry);
                boolean trusted = isFCMIntent(intent) && PushTrust.isGmsSender(context, senderUid);
                int userId = entryUserId(method, param.args, senderUid);
                PushOrigin.Scope scope = PushOrigin.enter(trusted, target, intent == null ? null : intent.getAction(), senderUid, userId);
                param.invocationState = scope;
                try {
                if (!trusted || !targetIsAllow(target)) return;
                intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                if (userId >= 0 && getBooleanConfig("includeIceBoxDisableApp", false)) {
                    Context targetContext = PushTrust.userContext(context, userId);
                    if (!IceboxUtils.isAppEnabled(targetContext, target) && method.getReturnType() == int.class) {
                        // Only binder entries own their locking and re-check permissions on replay.
                        if (binderEntry && deferBroadcast(param, intentIndex, target, userId, targetContext, scope)) return;
                        printLog("[BroadcastFix] Deferred IceBox replay unavailable for this entry; preserve original delivery");
                    }
                }
                if (userId >= 0) OplusProxyFix.unfreeze(target, userId);
                } catch (Throwable failure) {
                    PushOrigin.leave(scope);
                    param.invocationState = null;
                    printLog("[BroadcastFix] preserve original delivery after Hook error: " + failure);
                }
            }
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                PushOrigin.leave((PushOrigin.Scope) param.invocationState);
            }
        });
    }

    private boolean deferBroadcast(XC_MethodHook.MethodHookParam param, int intentIndex, String target,
            int userId, Context targetContext, PushOrigin.Scope origin) {
        Object[] args = param.args.clone();
        args[intentIndex] = new Intent((Intent) args[intentIndex]);
        long senderIdentity = Binder.clearCallingIdentity();
        Binder.restoreCallingIdentity(senderIdentity);
        boolean accepted = pendingBroadcasts.submit(userId + ":" + target, () -> {
            IceboxUtils.activeApp(targetContext, target, userId);
            for (int i = 0; i < 300; i++) {
                if (IceboxUtils.isAppEnabled(targetContext, target)) return true;
                Thread.sleep(100);
            }
            return false;
        }, () -> {
            long workerIdentity = Binder.clearCallingIdentity();
            PushOrigin.Scope replayScope = PushOrigin.enter(true, target, ((Intent) args[intentIndex]).getAction(), origin.senderUid, userId);
            try {
                Binder.restoreCallingIdentity(senderIdentity);
                // Replay the binder entry: its original permission checks and service locks still run.
                XposedBridge.invokeOriginalMethod(param.method, param.thisObject, args);
            } catch (Throwable error) { throw new IllegalStateException("deferred broadcast failed", error); }
            finally { Binder.restoreCallingIdentity(workerIdentity); PushOrigin.leave(replayScope); }
        }, error -> printLog("[IceBox] delivery failed for " + userId + ":" + target + ": " + error));
        if (accepted) param.setResult(0); // ActivityManager.BROADCAST_SUCCESS, an int.
        else printLog("[IceBox] queue full/unavailable; preserve original delivery for " + target);
        return accepted;
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
        Class<?> policy = XposedHelpers.findClassIfExists("com.android.server.am.BroadcastSkipPolicy", classLoader);
        if (policy == null) return;
        for (Method method : policy.getDeclaredMethods()) {
            if (!"shouldSkipMessage".equals(method.getName()) && !"shouldSkip".equals(method.getName())) continue;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Intent intent = null;
                    for (Object arg : param.args) {
                        if (arg instanceof Intent) { intent = (Intent) arg; break; }
                        if (arg != null && arg.getClass().getName().equals("com.android.server.am.BroadcastRecord")) {
                            Object value = XposedHelpers.getObjectField(arg, "intent");
                            if (value instanceof Intent) { intent = (Intent) value; break; }
                        }
                    }
                    String target = PushTrust.targetPackage(intent);
                    if (targetIsAllow(target) && isTrustedFCMIntent(intent, param.args)) {
                        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                        int userId = PushTrust.targetUserId(param.args);
                        if (userId >= 0) OplusProxyFix.unfreeze(target, userId);
                    }
                    // ALWAYS let the original policy decide: permissions, exports, IFW, and AppOps.
                }
            });
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
