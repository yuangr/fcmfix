package com.kooritea.fcmfix;

import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.xposed.AutoStartFix;
import com.kooritea.fcmfix.xposed.BroadcastFix;
import com.kooritea.fcmfix.xposed.DiagnosticsLogger;
import com.kooritea.fcmfix.xposed.KeepNotification;
import com.kooritea.fcmfix.xposed.MiuiLocalNotificationFix;
import com.kooritea.fcmfix.xposed.OplusProxyFix;
import com.kooritea.fcmfix.xposed.PowerkeeperFix;
import com.kooritea.fcmfix.xposed.ReconnectManagerFix;
import com.kooritea.fcmfix.xposed.XposedModule;
import com.kooritea.fcmfix.xposed.GmsForcePushFix;

import io.github.libxposed.api.XposedModuleInterface;

public class XposedMain extends io.github.libxposed.api.XposedModule {

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        XposedBridge.init(this);
        XposedModule.setSelfPackageName("android");

        ClassLoader classLoader = param.getClassLoader();
        try { new DiagnosticsLogger(classLoader); } catch (Throwable e) { XposedBridge.log("[fcmfix] DiagnosticsLogger init failed: " + e.getMessage()); }

        XposedBridge.log("[fcmfix] start hook com.android.server.am.ActivityManagerService/com.android.server.am.BroadcastController");
        try { new BroadcastFix(classLoader); } catch (Throwable e) { XposedBridge.log("[fcmfix] BroadcastFix init failed: " + e.getMessage()); }

        XposedBridge.log("[fcmfix] start hook com.android.server.notification.NotificationManagerServiceInjector");
        try { new MiuiLocalNotificationFix(classLoader); } catch (Throwable e) { XposedBridge.log("[fcmfix] MiuiLocalNotificationFix init failed: " + e.getMessage()); }

        XposedBridge.log("[fcmfix] com.android.server.am.BroadcastQueueInjector.checkApplicationAutoStart");
        try { new AutoStartFix(classLoader); } catch (Throwable e) { XposedBridge.log("[fcmfix] AutoStartFix init failed: " + e.getMessage()); }

        XposedBridge.log("[fcmfix] com.android.server.notification.NotificationManagerService");
        try { new KeepNotification(classLoader); } catch (Throwable e) { XposedBridge.log("[fcmfix] KeepNotification init failed: " + e.getMessage()); }

        XposedBridge.log("[fcmfix] start hook com.android.server.power.OplusProxyWakeLock");
        try { new OplusProxyFix(classLoader); } catch (Throwable e) { XposedBridge.log("[fcmfix] OplusProxyFix init failed: " + e.getMessage()); }
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        XposedBridge.init(this);

        if ("com.google.android.gms".equals(param.getPackageName()) && param.isFirstPackage()) {
            XposedModule.setSelfPackageName("com.google.android.gms");
            XposedBridge.log("[fcmfix] start hook com.google.android.gms");
            new ReconnectManagerFix(param.getClassLoader());
            try { new GmsForcePushFix(param.getClassLoader()); } catch (Throwable e) { XposedBridge.log("[fcmfix] GmsForcePushFix init failed: " + e.getMessage()); }
        }

        if ("com.miui.powerkeeper".equals(param.getPackageName()) && param.isFirstPackage()) {
            XposedModule.setSelfPackageName("com.miui.powerkeeper");
            XposedBridge.log("[fcmfix] start hook com.miui.powerkeeper");
            new PowerkeeperFix(param.getClassLoader());
        }
    }
}
