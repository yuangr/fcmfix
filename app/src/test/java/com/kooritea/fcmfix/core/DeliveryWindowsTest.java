package com.kooritea.fcmfix.core;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
public class DeliveryWindowsTest {
    @Test public void expiresAtSixtySecondsAndDoesNotCrossUsersOrPackages() {
        AtomicLong now=new AtomicLong(100); DeliveryWindows windows=new DeliveryWindows(now::get);
        windows.grant(10372,"wechat");
        assertTrue(windows.allows(10372,"wechat"));
        assertFalse(windows.allows(110372,"wechat"));
        assertFalse(windows.allows(10372,"another"));
        now.set(60099); assertTrue(windows.allows(10372,"wechat"));
        now.set(60100); assertFalse(windows.allows(10372,"wechat"));
    }
    @Test public void oldExpiryCannotRemoveAWindowRenewedByANewMessage() {
        AtomicLong now=new AtomicLong(0); DeliveryWindows windows=new DeliveryWindows(now::get);
        long old=windows.grant(10372,"wechat"); now.set(1000); windows.grant(10372,"wechat");
        now.set(60000); windows.expire(10372,old); assertTrue(windows.allows(10372,"wechat"));
        now.set(61000); assertFalse(windows.allows(10372,"wechat"));
    }
    @Test(expected=IllegalArgumentException.class) public void neverExemptsSystemUid() {
        new DeliveryWindows(() -> 0).grant(1000,"android");
    }
}
