package com.kooritea.fcmfix.xposed;

import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.kooritea.fcmfix.core.DeliveryWindows;
import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import java.lang.reflect.Method;
import com.kooritea.fcmfix.libxposed.XposedHelpers;
import com.kooritea.fcmfix.util.PushTrust;
import com.kooritea.fcmfix.core.TempAllowlist;
import java.util.HashSet;
import java.util.Set;

/** One-shot, bounded exemption after an authenticated GMS RECEIVE broadcast. */
final class PushProcessingWindow {
    static final long DURATION_MS = 60_000L;
    private static final Set<String> pending = new HashSet<>();
    private static final DeliveryWindows windows = new DeliveryWindows(SystemClock::elapsedRealtime);
    private static boolean hansHookInstalled;

    static synchronized void installHansHook(ClassLoader loader, XposedModule owner) {
        if (hansHookInstalled) return;
        try {
            Class<?> scene = Class.forName("com.android.server.hans.scene.HansSceneManager", false, loader);
            Class<?> pkgType = Class.forName("com.android.server.hans.OplusHansPackage", false, loader);
            Class<?> restriction = Class.forName("com.android.server.hans.OplusHansRestriction", false, loader);
            Class<?> result = Class.forName("com.android.server.hans.scene.HansSceneManager$Freezing", false, loader);
            Object important = XposedHelpers.getStaticObjectField(result, "IMPORTANT");
            Method freeze = scene.getDeclaredMethod("freezeForSceneCombo", pkgType, restriction);
            if (freeze.getReturnType() != result || important == null)
                throw new NoSuchMethodException("Unverified Hans freeze result");
            XposedBridge.hookMethod(freeze, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args[0] == null || !owner.getBooleanConfig("pushProcessingWindow", true)) return;
                    try {
                        int uid = (Integer) XposedHelpers.callMethod(param.args[0], "getUid");
                        String pkg = (String) XposedHelpers.callMethod(param.args[0], "getPkgName");
                        if (owner.targetIsAllow(pkg) && windows.allows(uid, pkg)) {
                            // Exactly the existing native important-app outcome, before side effects.
                            param.setResult(important);
                            XposedModule.printLog("[PushProcessingWindow] Hans deferred freeze: " + pkg + ", uid=" + uid, true);
                        }
                    } catch (Throwable unavailable) {
                        // An unsupported package object must preserve the original freeze decision.
                    }
                }
            });
            hansHookInstalled = true;
            XposedModule.printLog("[PushProcessingWindow] Hans bounded freeze guard installed", true);
        } catch (Throwable error) {
            XposedModule.printLog("[PushProcessingWindow] Hans guard unavailable: " + error);
        }
    }
    private PushProcessingWindow() {}

    static void schedule(Context context, ClassLoader loader, String target, int userId) {
        if (context == null || target == null || userId < 0) return;
        String key = userId + ":" + target;
        synchronized (pending) { if (!pending.add(key)) return; }
        try {
            // Run outside AMS locks, after GMS has issued its shorter delivery exemption.
            boolean posted = new Handler(Looper.getMainLooper()).postDelayed(() -> {
                synchronized (pending) { pending.remove(key); }
                if (XposedModule.allowList == null || !XposedModule.allowList.contains(target)) return;
                long identity = Binder.clearCallingIdentity();
                try {
                    // Verify the package exists in the target user before calling framework internals.
                    PushTrust.userContext(context, userId).getPackageManager().getApplicationInfo(target, 0);
                    Class<?> serviceType = Class.forName("com.android.server.DeviceIdleInternal", false, loader);
                    Class<?> localServices = Class.forName("com.android.server.LocalServices", false, loader);
                    Object service = XposedHelpers.callStaticMethod(localServices, "getService", serviceType);
                    Class<?> exemptions = Class.forName("android.os.PowerExemptionManager", false, loader);
                    int reason = exemptions.getField("REASON_PUSH_MESSAGING").getInt(null);
                    int type = exemptions.getField("TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED").getInt(null);
                    TempAllowlist.grant(service, target, userId, DURATION_MS, type, reason);
                    int uid = PushTrust.userContext(context, userId).getPackageManager().getPackageUid(target, 0);
                    if (uid / 100000 != userId) throw new IllegalArgumentException("Wrong target user");
                    long deadline = windows.grant(uid, target);
                    new Handler(Looper.getMainLooper()).postDelayed(() -> windows.expire(uid, deadline), DURATION_MS);
                    OplusProxyFix.unfreeze(target, userId);
                    XposedModule.printLog("[PushProcessingWindow] Granted 60000ms: " + target + "/" + userId, true);
                } catch (Throwable error) {
                    XposedModule.printLog("[PushProcessingWindow] Unavailable; preserve normal delivery: " + error);
                } finally { Binder.restoreCallingIdentity(identity); }
            }, 1000L);
            if (!posted) synchronized (pending) { pending.remove(key); }
        } catch (Throwable error) {
            synchronized (pending) { pending.remove(key); }
            XposedModule.printLog("[PushProcessingWindow] Cannot schedule: " + error);
        }
    }
}
