package com.kooritea.fcmfix.core;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** A request arriving during a read always causes another read before becoming idle. */
public final class CoalescingReloader {
    private final Executor executor;
    private final Runnable load;
    private final Consumer<Throwable> error;
    private boolean running;
    private boolean dirty;

    public CoalescingReloader(Executor executor, Runnable load, Consumer<Throwable> error) {
        this.executor = executor; this.load = load; this.error = error;
    }

    public synchronized void request() {
        dirty = true;
        if (running) return;
        running = true;
        try { executor.execute(this::drain); }
        catch (RuntimeException failure) { running = false; throw failure; }
    }

    private void drain() {
        while (true) {
            synchronized (this) { dirty = false; }
            try { load.run(); } catch (Throwable failure) { try { error.accept(failure); } catch (Throwable ignored) { /* Still release/retry reload state. */ } }
            synchronized (this) {
                if (!dirty) { running = false; return; }
            }
        }
    }
}
