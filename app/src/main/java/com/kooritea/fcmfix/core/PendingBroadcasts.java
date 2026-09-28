package com.kooritea.fcmfix.core;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Coalesce activation, never different messages. Capacity rejection leaves the caller in control. */
public final class PendingBroadcasts<K> {
    private static final class Message {
        final Runnable deliver; final Consumer<Throwable> failed;
        Message(Runnable deliver, Consumer<Throwable> failed) { this.deliver = deliver; this.failed = failed; }
    }
    private static final class Group {
        final ArrayDeque<Message> messages = new ArrayDeque<>();
        final Callable<Boolean> activate;
        Group(Callable<Boolean> activate) { this.activate = activate; }
    }
    private final Map<K, Group> groups = new HashMap<>();
    private final Executor executor;
    private final int capacity;
    private int queued;

    public PendingBroadcasts(Executor executor, int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.executor = executor; this.capacity = capacity;
    }

    public synchronized boolean submit(K key, Callable<Boolean> activate, Runnable deliver, Consumer<Throwable> failed) {
        if (queued >= capacity) return false;
        Group group = groups.get(key);
        boolean fresh = group == null;
        if (fresh) { group = new Group(activate); groups.put(key, group); }
        group.messages.addLast(new Message(deliver, failed)); queued++;
        if (fresh) {
            Group worker = group;
            try { executor.execute(() -> drain(key, worker)); }
            catch (RuntimeException failure) { groups.remove(key); queued -= worker.messages.size(); return false; }
        }
        return true;
    }

    private void drain(K key, Group group) {
        Throwable activationFailure = null;
        try { if (!group.activate.call()) activationFailure = new IllegalStateException("activation timed out or denied"); }
        catch (Throwable failure) { activationFailure = failure; }
        while (true) {
            Message message;
            synchronized (this) {
                message = group.messages.pollFirst();
                if (message == null) { groups.remove(key); return; }
                queued--;
            }
            try {
                if (activationFailure != null) message.failed.accept(activationFailure);
                else message.deliver.run();
            } catch (Throwable failure) {
                try { message.failed.accept(failure); } catch (Throwable ignored) { /* Continue draining other messages. */ }
            }
        }
    }

    public synchronized int queuedCount() { return queued; }
}
