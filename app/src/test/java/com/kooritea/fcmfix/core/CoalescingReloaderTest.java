package com.kooritea.fcmfix.core;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class CoalescingReloaderTest {
    @Test public void editDuringBlockedReadIsNeverDropped() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicInteger source = new AtomicInteger(1), published = new AtomicInteger(), reads = new AtomicInteger();
        CoalescingReloader reloader = new CoalescingReloader(executor, () -> {
            int value = source.get();
            if (reads.incrementAndGet() == 1) {
                reading.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                catch (InterruptedException error) { throw new AssertionError(error); }
            }
            published.set(value);
            if (value == 2) finished.countDown();
        }, error -> { throw new AssertionError(error); });
        try {
            reloader.request(); assertTrue(reading.await(3, TimeUnit.SECONDS));
            source.set(2); reloader.request(); reloader.request(); release.countDown();
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            assertEquals(2, published.get()); assertEquals(2, reads.get());
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test public void failingLoadAndErrorHandlerDoNotLeaveWorkerStuck() {
        ArrayDeque<Runnable> jobs = new ArrayDeque<>(); AtomicInteger reads = new AtomicInteger();
        CoalescingReloader reloader = new CoalescingReloader(jobs::add, () -> {
            if (reads.incrementAndGet() == 1) throw new IllegalStateException("read failed");
        }, error -> { throw new IllegalStateException("log failed"); });
        reloader.request(); jobs.remove().run(); reloader.request(); jobs.remove().run();
        assertEquals(2, reads.get()); assertTrue(jobs.isEmpty());
    }
    @Test public void executorRejectionCanBeRetried() {
        AtomicInteger attempts = new AtomicInteger(), reads = new AtomicInteger();
        CoalescingReloader reloader = new CoalescingReloader(task -> {
            if (attempts.getAndIncrement() == 0) throw new RejectedExecutionException();
            task.run();
        }, reads::incrementAndGet, error -> fail(error.toString()));
        assertThrows(RejectedExecutionException.class, reloader::request);
        reloader.request(); assertEquals(1, reads.get());
    }
}
