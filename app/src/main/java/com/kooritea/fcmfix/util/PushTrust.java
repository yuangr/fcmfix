package com.kooritea.fcmfix.util;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.UserHandle;
import com.kooritea.fcmfix.core.PushOrigin;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

/** Message shape is a hint; only framework-derived UID provenance authorizes a bypass. */
public final class PushTrust {
    private PushTrust() {}
    public static String targetPackage(Intent intent) {
        if (intent == null) return null;
        return intent.getComponent() != null ? intent.getComponent().getPackageName() : intent.getPackage();
    }
    public static boolean isFcmAction(String action) {
        return action != null && (action.endsWith(".android.c2dm.intent.RECEIVE")
                || "com.google.firebase.MESSAGING_EVENT".equals(action)
                || "com.google.firebase.INSTANCE_ID_EVENT".equals(action)
                || "com.google.android.c2dm.intent.REGISTRATION".equals(action)
                || "com.google.android.intent.action.GCM_NOTIFICATION".equals(action));
    }
    public static boolean looksLikeFcm(Intent intent) {
        if (intent == null) return false;
        if (isFcmAction(intent.getAction())) return true;
        try { return intent.hasExtra("google.message_id") || intent.hasExtra("gcm.message_id")
                || (intent.hasExtra("from") && intent.hasExtra("collapse_key")); }
        catch (RuntimeException malformedExtras) { return false; }
    }
    public static boolean isGmsSender(Context context, int uid) {
        if (context == null || uid < 10000) return false;
        try { return PushOrigin.isGmsUid(uid, context.getPackageManager().getPackagesForUid(uid)); }
        catch (RuntimeException unavailable) { return false; }
    }
    private static Object record(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) if (arg != null && arg.getClass().getName().equals("com.android.server.am.BroadcastRecord")) return arg;
        return null;
    }
    private static int intField(Object object, String field) {
        try { return (Integer) XposedHelpers.getObjectField(object, field); }
        catch (Throwable unavailable) { return -1; }
    }
    public static int recordSenderUid(Object[] args) {
        Object record = record(args);
        return record == null ? -1 : intField(record, "callingUid");
    }
    public static int entrySenderUid(Object[] args, boolean binderEntry) {
        int uid = Binder.getCallingUid();
        if (binderEntry || uid >= 10000) return uid;
        if (args != null && args.length > 0 && args[0] != null
                && args[0].getClass().getName().equals("com.android.server.am.ProcessRecord")) {
            return intField(args[0], "uid");
        }
        int sender = recordSenderUid(args);
        return sender >= 0 ? sender : uid;
    }
    public static boolean isTrustedFcm(Context context, Intent intent, Object[] args) {
        String target = targetPackage(intent);
        if (target == null || !looksLikeFcm(intent)) return false;
        int sender = recordSenderUid(args);
        if (sender >= 0) return trustedServiceSender(context, sender, intent, target);
        if (PushOrigin.matches(target, intent.getAction())) return true;
        return trustedServiceSender(context, Binder.getCallingUid(), intent, target);
    }
    private static boolean trustedServiceSender(Context context, int uid, Intent intent, String target) {
        if (isGmsSender(context, uid)) return true;
        if (context == null || uid < 10000 || !"com.google.firebase.MESSAGING_EVENT".equals(intent.getAction())) return false;
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages != null) for (String pkg : packages) if (target.equals(pkg)) return true;
        } catch (RuntimeException unavailable) { return false; }
        return false;
    }
    public static int targetUserId(Object[] args) {
        Object record = record(args);
        if (record != null) return intField(record, "userId");
        if (PushOrigin.hasScope()) return PushOrigin.userId();
        int sender = PushOrigin.senderUid();
        if (sender < 10000) sender = Binder.getCallingUid();
        return sender >= 10000 ? sender / 100000 : -1;
    }
    public static Context userContext(Context context, int userId) {
        if (context == null || userId < 0) throw new IllegalArgumentException("unknown target user");
        try {
            return (Context) Context.class.getMethod("createContextAsUser", UserHandle.class, int.class)
                    .invoke(context, UserHandle.getUserHandleForUid(userId * 100000), 0);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("cannot resolve user context", failure); }
    }
}
