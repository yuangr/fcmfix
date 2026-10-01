package com.kooritea.fcmfix.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class TempAllowlistTest {
    public static class Modern {
        int caller, type, user, reason; String target; long duration; boolean sync;
        public void addPowerSaveTempWhitelistApp(int caller, String target, long duration,
                int type, int user, boolean sync, int reason, String text) {
            this.caller=caller; this.target=target; this.duration=duration;
            this.type=type; this.user=user; this.sync=sync; this.reason=reason;
        }
    }
    public static class Legacy {
        int user; long duration;
        public void addPowerSaveTempWhitelistApp(int caller, String target, long duration,
                int user, boolean sync, int reason, String text) { this.user=user; this.duration=duration; }
    }
    @Test public void modernContractPreservesTypeAndUserPositions() throws Exception {
        Modern service=new Modern(); TempAllowlist.grant(service,"wechat",10,60000,7,102);
        assertEquals(1000,service.caller); assertEquals("wechat",service.target);
        assertEquals(60000,service.duration); assertEquals(7,service.type);
        assertEquals(10,service.user); assertEquals(102,service.reason); assertFalse(service.sync);
    }
    @Test public void legacyContractPreservesUserAndBoundedDuration() throws Exception {
        Legacy service=new Legacy(); TempAllowlist.grant(service,"wechat",10,60000,7,102);
        assertEquals(10,service.user); assertEquals(60000,service.duration);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsUnboundedWindow() throws Exception {
        TempAllowlist.grant(new Modern(),"wechat",0,60001,0,102);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsUnknownUser() throws Exception {
        TempAllowlist.grant(new Modern(),"wechat",-1,60000,0,102);
    }
    @Test(expected=NoSuchMethodException.class) public void neverGuessesUnsupportedSignature() throws Exception {
        TempAllowlist.grant(new Object(),"wechat",0,60000,0,102);
    }
}
