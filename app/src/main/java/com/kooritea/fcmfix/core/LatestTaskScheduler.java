package com.kooritea.fcmfix.core;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One pending watchdog per timer instance, including removal of cancelled queue entries. */
public final class LatestTaskScheduler<K> {
    private static final class Entry { ScheduledFuture<?> future; }
    private final Map<K, Entry> pending = new IdentityHashMap<>();
    private final ScheduledThreadPoolExecutor executor;

    public LatestTaskScheduler(ScheduledThreadPoolExecutor executor) {
        this.executor = executor;
        executor.setRemoveOnCancelPolicy(true);
    }

    public synchronized void replace(K key, long delayMillis, Runnable action) {
        Entry old = pending.remove(key);
        if (old != null) old.future.cancel(false);
        Entry entry = new Entry();
        pending.put(key, entry);
        try {
            entry.future = executor.schedule(() -> {
                synchronized (LatestTaskScheduler.this) {
                    if (pending.get(key) != entry) return;
                    pending.remove(key);
                }
                action.run();
            }, Math.max(0, delayMillis), TimeUnit.MILLISECONDS);
        } catch (RuntimeException failure) { pending.remove(key); throw failure; }
    }

    public synchronized void cancel(K key) {
        Entry entry = pending.remove(key);
        if (entry != null) entry.future.cancel(false);
    }

    public synchronized void cancelAll() {
        for (Entry entry : pending.values()) entry.future.cancel(false);
        pending.clear();
    }

    public synchronized int pendingCount() { return pending.size(); }
}
