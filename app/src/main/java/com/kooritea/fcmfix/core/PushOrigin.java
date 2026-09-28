package com.kooritea.fcmfix.core;

/** Thread-bound provenance survives synchronous framework Intent copies, never IPC extras. */
public final class PushOrigin {
    public static final class Scope {
        final Scope previous;
        final boolean trusted;
        final String target;
        final String action;
        public final int senderUid;
        public final int userId;
        Scope(Scope previous, boolean trusted, String target, String action, int senderUid, int userId) {
            this.previous = previous; this.trusted = trusted; this.target = target; this.action = action; this.senderUid = senderUid; this.userId = userId;
        }
    }
    private static final ThreadLocal<Scope> current = new ThreadLocal<>();
    private PushOrigin() {}

    public static boolean isGmsUid(int uid, String[] packages) {
        if (uid < 10000 || packages == null) return false;
        for (String pkg : packages) if ("com.google.android.gms".equals(pkg)) return true;
        return false;
    }

    public static Scope enter(boolean trusted, String target, String action, int senderUid, int userId) {
        Scope scope = new Scope(current.get(), trusted, target, action, senderUid, userId);
        current.set(scope); return scope;
    }
    public static void leave(Scope scope) {
        if (scope == null) return;
        if (scope.previous == null) current.remove(); else current.set(scope.previous);
    }
    public static boolean matches(String target, String action) {
        Scope scope = current.get();
        return scope != null && scope.trusted && target != null && target.equals(scope.target)
                && action != null && action.equals(scope.action);
    }
    public static boolean hasScope() { return current.get() != null; }
    public static int userId() { Scope scope = current.get(); return scope == null ? -1 : scope.userId; }
    public static int senderUid() { Scope scope = current.get(); return scope == null ? -1 : scope.senderUid; }
}
