package com.kooritea.fcmfix.core;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class PendingBroadcastsTest {
    @Test public void burstCoalescesActivationButDeliversEveryMessageInOrder() {
        ArrayDeque<Runnable> jobs = new ArrayDeque<>(); List<Integer> delivered = new ArrayList<>();
        AtomicInteger activations = new AtomicInteger();
        PendingBroadcasts<String> pending = new PendingBroadcasts<>(jobs::add, 8);
        for (int i=0;i<3;i++) { final int id=i;
            assertTrue(pending.submit("0:app", () -> { activations.incrementAndGet(); return true; },
                    () -> delivered.add(id), error -> fail(error.toString())));
        }
        assertEquals(1, jobs.size()); assertEquals(3, pending.queuedCount());
        jobs.remove().run();
        assertEquals(Arrays.asList(0,1,2), delivered); assertEquals(1, activations.get()); assertEquals(0, pending.queuedCount());
    }
    @Test public void messageArrivingDuringDeliveryUsesSameActivation() {
        ArrayDeque<Runnable> jobs = new ArrayDeque<>(); List<Integer> delivered = new ArrayList<>();
        AtomicInteger activations = new AtomicInteger();
        PendingBroadcasts<String> pending = new PendingBroadcasts<>(jobs::add, 8);
        pending.submit("app", () -> { activations.incrementAndGet(); return true; }, () -> {
            delivered.add(1);
            assertTrue(pending.submit("app", () -> { activations.incrementAndGet(); return true; },
                    () -> delivered.add(2), error -> fail(error.toString())));
        }, error -> fail(error.toString()));
        jobs.remove().run();
        assertEquals(Arrays.asList(1,2), delivered); assertEquals(1, activations.get()); assertTrue(jobs.isEmpty());
    }
    @Test public void profilesHaveIndependentActivationGroups() {
        ArrayDeque<Runnable> jobs = new ArrayDeque<>(); AtomicInteger delivered = new AtomicInteger();
        PendingBroadcasts<String> pending = new PendingBroadcasts<>(jobs::add, 8);
        pending.submit("0:app", () -> true, delivered::incrementAndGet, error -> fail());
        pending.submit("10:app", () -> true, delivered::incrementAndGet, error -> fail());
        assertEquals(2, jobs.size()); while (!jobs.isEmpty()) jobs.remove().run(); assertEquals(2, delivered.get());
    }
    @Test public void fullQueueLeavesRejectedMessageWithCaller() {
        ArrayDeque<Runnable> jobs = new ArrayDeque<>(); AtomicInteger delivered = new AtomicInteger();
        PendingBroadcasts<String> pending = new PendingBroadcasts<>(jobs::add, 1);
        assertTrue(pending.submit("app", () -> true, delivered::incrementAndGet, error -> fail()));
        assertFalse(pending.submit("app", () -> true, delivered::incrementAndGet, error -> fail()));
        assertEquals(1, pending.queuedCount()); jobs.remove().run(); assertEquals(1, delivered.get());
    }
    @Test public void executorRejectionDoesNotSwallowMessageOrCapacity() {
        PendingBroadcasts<String> pending = new PendingBroadcasts<>(task -> { throw new RejectedExecutionException(); }, 1);
        assertFalse(pending.submit("app", () -> true, () -> fail(), error -> fail())); assertEquals(0, pending.queuedCount());
    }
    @Test public void failedMessageDoesNotDiscardFollowingMessage() {
        ArrayDeque<Runnable> jobs = new ArrayDeque<>(); AtomicInteger failures = new AtomicInteger(), delivered = new AtomicInteger();
        PendingBroadcasts<String> pending = new PendingBroadcasts<>(jobs::add, 8);
        pending.submit("app", () -> true, () -> { throw new IllegalStateException(); }, error -> {
            failures.incrementAndGet(); throw new IllegalStateException("broken logging");
        });
        pending.submit("app", () -> true, delivered::incrementAndGet, error -> fail()); jobs.remove().run();
        assertEquals(1, failures.get()); assertEquals(1, delivered.get()); assertEquals(0, pending.queuedCount());
    }
    @Test public void activationFailureIsReportedForEveryQueuedMessage() {
        ArrayDeque<Runnable> jobs = new ArrayDeque<>(); AtomicInteger failures = new AtomicInteger();
        PendingBroadcasts<String> pending = new PendingBroadcasts<>(jobs::add, 8);
        for (int i=0;i<3;i++) pending.submit("app", () -> false, () -> fail(), error -> failures.incrementAndGet());
        jobs.remove().run(); assertEquals(3, failures.get()); assertEquals(0, pending.queuedCount());
    }
}
