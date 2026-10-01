package com.kooritea.fcmfix.core;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Monotonic, per-UID and package leases; no permanent exemptions. */
public final class DeliveryWindows {
    private static final long DURATION_MS = 60_000L;
    private final LongSupplier clock;
    private final Map<Integer, Lease> leases = new HashMap<>();
    private static final class Lease {
        final String pkg; final long deadline;
        Lease(String pkg, long deadline) { this.pkg=pkg; this.deadline=deadline; }
    }
    public DeliveryWindows(LongSupplier clock) { this.clock=clock; }
    public synchronized long grant(int uid, String pkg) {
        if (uid < 10000 || pkg == null) throw new IllegalArgumentException("Unknown target");
        long now=clock.getAsLong();
        leases.entrySet().removeIf(e -> e.getValue().deadline <= now);
        long deadline=now+DURATION_MS;
        leases.put(uid,new Lease(pkg,deadline));
        return deadline;
    }
    public synchronized boolean allows(int uid, String pkg) {
        Lease lease=leases.get(uid);
        if (lease == null) return false;
        if (clock.getAsLong() >= lease.deadline) { leases.remove(uid); return false; }
        return lease.pkg.equals(pkg);
    }
    public synchronized void expire(int uid, long deadline) {
        Lease lease=leases.get(uid);
        if (lease != null && lease.deadline == deadline && clock.getAsLong() >= deadline)
            leases.remove(uid);
    }
}
