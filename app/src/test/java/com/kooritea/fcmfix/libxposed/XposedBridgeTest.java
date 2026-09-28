package com.kooritea.fcmfix.libxposed;

import com.kooritea.fcmfix.support.HookRuntime;
import org.junit.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class XposedBridgeTest {
    public static final class Target {
        int calls;
        public int run(int value) { calls++; return value; }
        public int fail() { throw new IllegalStateException("original failure"); }
    }
    HookRuntime runtime; Method method; Target target;
    @Before public void setup() throws Exception {
        runtime = new HookRuntime(); XposedBridge.init(runtime.api); target = new Target(); method = Target.class.getMethod("run",int.class);
    }
    @Test public void threeRegistrationsEachRunOnceAndOriginalRunsOnce() throws Throwable {
        List<String> events = new ArrayList<>();
        for (int i=1;i<=3;i++) { final int id=i;
            XposedBridge.hookMethod(method,new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) { events.add("b"+id); param.args[0]=(int)param.args[0]+1; }
                @Override protected void afterHookedMethod(MethodHookParam param) { events.add("a"+id); param.setResult((int)param.getResult()+10); }
            });
        }
        assertEquals(34,runtime.invoke(method,target,1)); assertEquals(1,target.calls);
        assertEquals(Arrays.asList("b1","b2","b3","a3","a2","a1"),events);
    }
    @Test public void earlyResultSkipsInnerChainButRunsItsOwnAfter() throws Throwable {
        AtomicInteger after = new AtomicInteger(), inner = new AtomicInteger();
        XposedBridge.hookMethod(method,new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) { param.setResult(8); }
            @Override protected void afterHookedMethod(MethodHookParam param) { after.incrementAndGet(); }
        });
        XposedBridge.hookMethod(method,new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) { inner.incrementAndGet(); }
        });
        assertEquals(8,runtime.invoke(method,target,1)); assertEquals(0,target.calls); assertEquals(0,inner.get()); assertEquals(1,after.get());
    }
    @Test public void afterCanObserveAndRecoverOriginalException() throws Throwable {
        Method failing = Target.class.getMethod("fail");
        XposedBridge.hookMethod(failing,new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) { assertEquals("original failure",param.getThrowable().getMessage()); param.setResult(9); }
        });
        assertEquals(9,runtime.invoke(failing,target));
    }
    @Test public void originalExceptionPropagatesAfterCleanup() throws Throwable {
        Method failing = Target.class.getMethod("fail"); AtomicInteger after = new AtomicInteger();
        XposedBridge.hookMethod(failing,new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) { after.incrementAndGet(); }
        });
        try { runtime.invoke(failing,target); fail(); } catch (IllegalStateException expected) { assertEquals("original failure",expected.getMessage()); }
        assertEquals(1,after.get());
    }
    @Test public void unhookRemovesOnlyThatRegistration() throws Throwable {
        AtomicInteger first = new AtomicInteger(), second = new AtomicInteger();
        XC_MethodHook.Unhook one = XposedBridge.hookMethod(method,new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) { first.incrementAndGet(); }
        });
        XposedBridge.hookMethod(method,new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) { second.incrementAndGet(); }
        });
        one.unhook(); assertEquals(7,runtime.invoke(method,target,7)); assertEquals(0,first.get()); assertEquals(1,second.get());
    }
    @Test public void failedRegistrationLeavesNoStaleCallback() throws Throwable {
        AtomicInteger callbacks = new AtomicInteger();
        when(runtime.api.hook(method)).thenThrow(new IllegalStateException("registration failed"));
        assertThrows(IllegalStateException.class, () -> XposedBridge.hookMethod(method,new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) { callbacks.incrementAndGet(); }
        }));
        assertEquals(2,runtime.invoke(method,target,2)); assertEquals(0,callbacks.get());
    }
}
