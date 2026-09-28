package com.kooritea.fcmfix.core;

import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class LatestTaskSchedulerTest {
    @Test public void repeatedTimeoutResetsKeepOneQueueEntry() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        try {
            LatestTaskScheduler<Object> scheduler = new LatestTaskScheduler<>(executor); Object timer = new Object();
            for (int i=0;i<1000;i++) scheduler.replace(timer, 60000, () -> fail("stale task ran"));
            assertEquals(1, scheduler.pendingCount()); assertEquals(1, executor.getQueue().size());
            scheduler.cancel(timer); assertEquals(0, scheduler.pendingCount()); assertTrue(executor.getQueue().isEmpty());
        } finally { executor.shutdownNow(); }
    }
    @Test public void newestTaskRunsAndSupersededTaskCannotReconnect() throws Exception {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1); AtomicInteger stale = new AtomicInteger();
        CountDownLatch finished = new CountDownLatch(1);
        try {
            LatestTaskScheduler<Object> scheduler = new LatestTaskScheduler<>(executor); Object timer = new Object();
            scheduler.replace(timer, 60000, stale::incrementAndGet); scheduler.replace(timer, 0, finished::countDown);
            assertTrue(finished.await(3, TimeUnit.SECONDS)); assertEquals(0, stale.get()); assertEquals(0, scheduler.pendingCount());
        } finally { executor.shutdownNow(); }
    }
    @Test public void equalButDistinctTimersDoNotCancelEachOther() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        try {
            LatestTaskScheduler<String> scheduler = new LatestTaskScheduler<>(executor);
            scheduler.replace(new String("timer"), 60000, () -> {}); scheduler.replace(new String("timer"), 60000, () -> {});
            assertEquals(2, scheduler.pendingCount()); scheduler.cancelAll();
            assertEquals(0, scheduler.pendingCount()); assertTrue(executor.getQueue().isEmpty());
        } finally { executor.shutdownNow(); }
    }
}
