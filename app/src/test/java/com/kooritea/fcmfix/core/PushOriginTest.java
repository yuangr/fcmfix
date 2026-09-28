package com.kooritea.fcmfix.core;

import org.junit.Test;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class PushOriginTest {
    @Test public void packageNameAndRealUidAreBothRequired() {
        assertFalse(PushOrigin.isGmsUid(1000, new String[]{"com.google.android.gms"}));
        assertFalse(PushOrigin.isGmsUid(10001, new String[]{"attacker"}));
        assertFalse(PushOrigin.isGmsUid(10001, null));
        assertTrue(PushOrigin.isGmsUid(1010001, new String[]{"com.google.android.gms"}));
    }
    @Test public void untrustedNestedCallCannotBorrowOuterTrust() {
        PushOrigin.Scope outer = PushOrigin.enter(true,"app","receive",10001,10);
        try {
            assertTrue(PushOrigin.matches("app","receive")); assertEquals(10,PushOrigin.userId());
            PushOrigin.Scope inner = PushOrigin.enter(false,"app","receive",10002,0);
            try { assertFalse(PushOrigin.matches("app","receive")); }
            finally { PushOrigin.leave(inner); }
            assertTrue(PushOrigin.matches("app","receive")); assertEquals(10001, PushOrigin.senderUid());
            assertFalse(PushOrigin.matches("other","receive")); assertFalse(PushOrigin.matches("app","other"));
        } finally { PushOrigin.leave(outer); }
        assertFalse(PushOrigin.hasScope());
    }
    @Test public void provenanceDoesNotLeakAcrossThreads() throws Exception {
        PushOrigin.Scope scope = PushOrigin.enter(true,"app","receive",10001,-1);
        try {
            AtomicBoolean trusted = new AtomicBoolean(true);
            Thread thread = new Thread(() -> trusted.set(PushOrigin.matches("app","receive")));
            thread.start(); thread.join(3000); assertFalse(thread.isAlive()); assertFalse(trusted.get());
            assertEquals(-1, PushOrigin.userId());
        } finally { PushOrigin.leave(scope); }
    }
}
