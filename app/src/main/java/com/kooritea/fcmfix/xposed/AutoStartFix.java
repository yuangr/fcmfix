package com.kooritea.fcmfix.xposed;

import android.content.Intent;

import com.kooritea.fcmfix.util.XposedUtils;

import java.lang.reflect.Method;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

public class AutoStartFix extends XposedModule {
    private final String FCM_RECEIVE = ".android.c2dm.intent.RECEIVE";

    public AutoStartFix(ClassLoader classLoader){
        super(classLoader);
        try{
            this.startHook();
            this.startHookRemovePowerPolicy();
        }catch (Throwable e) {
            printLog("hook error AutoStartFix:" + e.getMessage());
        }
    }

    protected void startHook(){
        try{
            // miui12
            Class<?> BroadcastQueueInjector = XposedHelpers.findClass("com.android.server.am.BroadcastQueueInjector",classLoader);
            XposedUtils.findAndHookMethodAnyParam(BroadcastQueueInjector,"checkApplicationAutoStart",new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    Intent intent = (Intent) XposedHelpers.getObjectField(methodHookParam.args[2], "intent");
                    if(isFCMIntent(intent)){
                        String target = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
                        if(targetIsAllow(target)){
                            XposedHelpers.callStaticMethod(BroadcastQueueInjector,"checkAbnormalBroadcastInQueueLocked", methodHookParam.args[1], methodHookParam.args[0]);
                            printLog("Allow Auto Start: " + target, true);
                            methodHookParam.setResult(true);
                        }
                    }
                }
            });
        }catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such Method com.android.server.am.BroadcastQueueInjector.checkApplicationAutoStart");
        }
        try{
            // miui13
            Class<?> BroadcastQueueImpl = XposedHelpers.findClass("com.android.server.am.BroadcastQueueImpl",classLoader);
            XposedUtils.findAndHookMethodAnyParam(BroadcastQueueImpl,"checkApplicationAutoStart",new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    Intent intent = (Intent) XposedHelpers.getObjectField(methodHookParam.args[1], "intent");
                    if(isFCMIntent(intent)){
                        String target = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
                        if(targetIsAllow(target)){
                            XposedHelpers.callMethod(methodHookParam.thisObject, "checkAbnormalBroadcastInQueueLocked", methodHookParam.args[0]);
                            printLog("Allow Auto Start: " + target, true);
                            methodHookParam.setResult(true);
                        }
                    }
                }
            });
        }catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such Method com.android.server.am.BroadcastQueueImpl.checkApplicationAutoStart");
        }

        try{
            // hyperos
            Class<?> BroadcastQueueImpl = XposedHelpers.findClass("com.android.server.am.BroadcastQueueModernStubImpl",classLoader);
            printLog("[fcmfix] start hook com.android.server.am.BroadcastQueueModernStubImpl.checkApplicationAutoStart");
            XposedUtils.findAndHookMethodAnyParam(BroadcastQueueImpl,"checkApplicationAutoStart", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    Intent intent = (Intent) XposedHelpers.getObjectField(methodHookParam.args[1], "intent");
                    String target = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
                    if (targetIsAllow(target)) {
                        // 无日志，先放了
                        printLog("[" + intent.getAction() + "]checkApplicationAutoStart package_name: " + target, true);
                        methodHookParam.setResult(true);
//                        if(isFCMIntent(intent)){
//                            printLog("checkApplicationAutoStart package_name: " + target, true);
//                            methodHookParam.setResult(true);
//                        }else{
//                            printLog("[skip][" + intent.getAction() + "]checkApplicationAutoStart package_name: " + target, true);
//                        }

                    }
                }
            });

            printLog("[fcmfix] start hook com.android.server.am.BroadcastQueueModernStubImpl.checkReceiverIfRestricted");
            XposedUtils.findAndHookMethodAnyParam(BroadcastQueueImpl,"checkReceiverIfRestricted", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    Intent intent = (Intent) XposedHelpers.getObjectField(methodHookParam.args[1], "intent");
                    String target = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
                    if(targetIsAllow(target)){
                        if(isFCMIntent(intent)){
                            printLog("BroadcastQueueModernStubImpl.checkReceiverIfRestricted package_name: " + target, true);
                            methodHookParam.setResult(false);
                        }
                    }
                }
            });
        }catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such class com.android.server.am.BroadcastQueueModernStubImpl");
        }

        try {
            Class<?> AutoStartManagerServiceStubImpl = XposedHelpers.findClass("com.android.server.am.AutoStartManagerServiceStubImpl", classLoader);
            XC_MethodHook methodHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    Intent intent = (Intent) methodHookParam.args[1];
                    String target = intent.getComponent().getPackageName();
                    if(targetIsAllow(target)) {
                        // 拿不到action，先放了
                        printLog("[" + intent.getAction() + "]AutoStartManagerServiceStubImpl.isAllowStartService package_name: " + target, true);
                        methodHookParam.setResult(true);
//                        if(isFCMIntent(intent)){
//                            printLog("AutoStartManagerServiceStubImpl.isAllowStartService package_name: " + target, true);
//                            methodHookParam.setResult(true);
//                        }else{
//                            printLog("[skip][" + intent.getAction() + "]AutoStartManagerServiceStubImpl.isAllowStartService package_name: " + target, true);
//                        }
                    }
                }
            };

            printLog("[fcmfix] start hook com.android.server.am.AutoStartManagerServiceStubImpl.isAllowStartService");
            XC_MethodHook.Unhook unhook1 = XposedUtils.tryFindAndHookMethod(AutoStartManagerServiceStubImpl, "isAllowStartService", 3, methodHook);
            XC_MethodHook.Unhook unhook2 = XposedUtils.tryFindAndHookMethod(AutoStartManagerServiceStubImpl, "isAllowStartService", 4, methodHook);
            if(unhook1 == null && unhook2 == null){
                throw new NoSuchMethodError();
            }
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such Class com.android.server.am.AutoStartManagerServiceStubImpl.isAllowStartService");
        }

        try {
            Class<?> SmartPowerService = XposedHelpers.findClass("com.android.server.am.SmartPowerService", classLoader);

            printLog("[fcmfix] start hook com.android.server.am.SmartPowerService.shouldInterceptBroadcast");
            XposedUtils.findAndHookMethodAnyParam(SmartPowerService, "shouldInterceptBroadcast", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    Intent intent = (Intent) XposedHelpers.getObjectField(methodHookParam.args[1], "intent");
                    String target = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
                    if(targetIsAllow(target)) {
                        if(isFCMIntent(intent)){
                            printLog("SmartPowerService.shouldInterceptBroadcast package_name: " + target, true);
                            methodHookParam.setResult(false);
                        }
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e){
            printLog("No Such Class com.android.server.am.SmartPowerService");
        }

        try{
            // oos15/cos15/cos16 — try multiple class names, method names, and parameter structures
            String[] classNames = {
                "com.android.server.am.OplusAppStartupManager",
                "com.android.server.am.OplusAppStartupManagerService",
                "com.android.server.am.OplusHansManager",
                "com.android.server.hans.OplusHansManager"
            };
            boolean hooked = false;
            for (String className : classNames) {
                try {
                    Class<?> clazz = XposedHelpers.findClass(className, classLoader);
                    for (Method m : clazz.getDeclaredMethods()) {
                        String name = m.getName();
                        if ("shouldPreventSendReceiverReal".equals(name) || "shouldPreventSendReceiver".equals(name) || "shouldPreventStartBroadcast".equals(name)) {
                            XposedBridge.hookMethod(m, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                                    Intent intent = null;
                                    for (Object arg : methodHookParam.args) {
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
                                        String target = intent.getPackage();
                                        if (target == null && intent.getComponent() != null) {
                                            target = intent.getComponent().getPackageName();
                                        }
                                        if (target == null) {
                                            for (Object arg : methodHookParam.args) {
                                                if (arg instanceof String && targetIsAllow((String) arg)) {
                                                    target = (String) arg;
                                                    break;
                                                }
                                            }
                                        }
                                        if (target == null || targetIsAllow(target)) {
                                            printLog("[AutoStartFix] ColorOS 16 bypassed receiver restriction for " + target, true);
                                            methodHookParam.setResult(false);
                                        }
                                    }
                                }
                            });
                            printLog("Hooked " + className + "." + name + " (" + m.getParameterCount() + " params)");
                            hooked = true;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            if (!hooked) {
                printLog("Warning: Could not hook ColorOS startup manager methods");
            }
        } catch (Throwable e) {
            printLog("AutoStartFix ColorOS 16 error: " + e.getMessage());
        }
    }

    protected void startHookRemovePowerPolicy(){
        try {
            // MIUI13
            Class<?> AutoStartManagerService = XposedHelpers.findClass("com.miui.server.smartpower.SmartPowerPolicyManager",classLoader);
            XposedUtils.findAndHookMethodAnyParam(AutoStartManagerService,"shouldInterceptService",new XC_MethodHook() {

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.args[0];
                    if("com.google.firebase.MESSAGING_EVENT".equals(intent.getAction())){
                        String target = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
                        if(targetIsAllow(target)){
                            printLog("Disable MIUI Intercept: " + target, true);
                            param.setResult(false);
                        }
                    }
                }
            });
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError  e) {
            printLog("No Such Method com.miui.server.smartpower.SmartPowerPolicyManager.shouldInterceptService");
        }
    }
}
