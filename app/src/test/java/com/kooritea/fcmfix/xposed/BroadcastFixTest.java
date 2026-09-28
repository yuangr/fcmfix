package com.kooritea.fcmfix.xposed;

import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import com.android.server.am.*;
import com.kooritea.fcmfix.core.*;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.support.HookRuntime;
import com.kooritea.fcmfix.util.PushTrust;
import org.junit.*;
import org.mockito.MockedStatic;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class BroadcastFixTest {
    HookRuntime runtime; Context context; PackageManager pm; MockedStatic<Binder> binder;
    Method entry,policyMethod; BroadcastController controller; BroadcastSkipPolicy policy;
    @Before public void setup() throws Exception {
        runtime=new HookRuntime(); XposedBridge.init(runtime.api); context=mock(Context.class); pm=mock(PackageManager.class);
        when(context.getPackageManager()).thenReturn(pm);
        UserManager manager=mock(UserManager.class); when(context.getSystemService(UserManager.class)).thenReturn(manager);
        when(pm.getPackagesForUid(10001)).thenReturn(new String[]{"com.google.android.gms"});
        when(pm.getPackagesForUid(10002)).thenReturn(new String[]{"attacker"});
        when(pm.getPackagesForUid(10003)).thenReturn(new String[]{"target.app"});
        set("context",context); set("config",ConfigSnapshot.from(Collections.singletonMap("allowList",Collections.singleton("target.app"))));
        binder=mockStatic(Binder.class); binder.when(Binder::getCallingUid).thenReturn(10001);
        new BroadcastFix(getClass().getClassLoader());
        for (Method method:BroadcastController.class.getDeclaredMethods()) if (method.getName().equals("broadcastIntentWithFeature")) entry=method;
        policyMethod=BroadcastSkipPolicy.class.getMethod("shouldSkipMessage",BroadcastRecord.class,int.class);
        controller=new BroadcastController(); policy=new BroadcastSkipPolicy();
        assertEquals(1,runtime.count(entry)); assertEquals(1,runtime.count(policyMethod));
    }
    @After public void cleanup() throws Exception { binder.close(); set("context",null); set("config",ConfigSnapshot.EMPTY); assertFalse(PushOrigin.hasScope()); }
    private static void set(String name,Object value) throws Exception { Field field=XposedModule.class.getDeclaredField(name); field.setAccessible(true); field.set(null,value); }
    private Intent intent(String action,String target) {
        Intent intent=mock(Intent.class); when(intent.getAction()).thenReturn(action); when(intent.getPackage()).thenReturn(target);
        AtomicInteger flags=new AtomicInteger(); when(intent.getFlags()).thenAnswer(call -> flags.get());
        when(intent.addFlags(anyInt())).thenAnswer(call -> { flags.getAndUpdate(value -> value | (Integer)call.getArgument(0)); return intent; });
        return intent;
    }
    private Object broadcast(Intent intent) throws Throwable {
        return runtime.invoke(entry,controller,null,null,intent,null,null,0,null,null,null,null,null,-1,null,false,false,-1);
    }
    @Test public void genuineFcmStillRunsOriginalPermissionPolicy() throws Throwable {
        Intent intent=intent("com.google.android.c2dm.intent.RECEIVE","target.app");
        binder.when(Binder::getCallingUid).thenReturn(1000); // Framework may have cleared Binder identity.
        assertEquals("permission denied",runtime.invoke(policyMethod,policy,new BroadcastRecord(intent,10001,-1),0));
        assertEquals(1,policy.checks); assertEquals(Intent.FLAG_INCLUDE_STOPPED_PACKAGES,intent.getFlags());
    }
    @Test public void forgedExtrasDoNotAuthorizeBackgroundBypass() throws Throwable {
        Intent intent=intent("custom.action","target.app"); when(intent.hasExtra("google.message_id")).thenReturn(true);
        binder.when(Binder::getCallingUid).thenReturn(10002);
        assertEquals(17,broadcast(intent)); assertEquals(0,intent.getFlags()); assertEquals(1,controller.calls);
        assertEquals("permission denied",runtime.invoke(policyMethod,policy,new BroadcastRecord(intent,10002,-1),0));
        assertEquals(0,intent.getFlags()); assertEquals(1,policy.checks);
    }
    @Test public void recordUidOverridesOuterTrustedScope() throws Throwable {
        Intent intent=intent("com.google.android.c2dm.intent.RECEIVE","target.app");
        PushOrigin.Scope scope=PushOrigin.enter(true,"target.app",intent.getAction(),10001,-1);
        try { assertFalse(PushTrust.isTrustedFcm(context,intent,new Object[]{new BroadcastRecord(intent,10002,0)})); }
        finally { PushOrigin.leave(scope); }
    }
    @Test public void unknownTargetNeverBecomesAnAllowAllMatch() throws Throwable {
        Intent intent=intent("com.google.android.c2dm.intent.RECEIVE",null);
        assertEquals(17,broadcast(intent)); assertEquals(0,intent.getFlags());
        assertEquals("permission denied",runtime.invoke(policyMethod,policy,new BroadcastRecord(intent,10001,-1),0)); assertEquals(0,intent.getFlags());
    }
    @Test public void appOpAndUserAllRemainUnchangedWithoutParameterNames() throws Throwable {
        assertFalse(entry.getParameters()[15].isNamePresent());
        Intent intent=intent("com.google.android.c2dm.intent.RECEIVE","target.app");
        assertEquals(17,broadcast(intent)); assertEquals(-1,controller.seenAppOp); assertEquals(-1,controller.seenUserId);
        assertEquals(Intent.FLAG_INCLUDE_STOPPED_PACKAGES,intent.getFlags()); assertFalse(PushOrigin.hasScope());
    }
    @Test public void ownFirebaseServiceDispatchWorksButCannotTargetAnotherApp() {
        Intent intent=intent("com.google.firebase.MESSAGING_EVENT","target.app");
        binder.when(Binder::getCallingUid).thenReturn(10003); assertTrue(PushTrust.isTrustedFcm(context,intent,new Object[0]));
        binder.when(Binder::getCallingUid).thenReturn(10002); assertFalse(PushTrust.isTrustedFcm(context,intent,new Object[0]));
    }
    @Test public void nonGmsSystemCallerCannotSpoofPush() throws Throwable {
        Intent intent=intent("com.google.android.c2dm.intent.RECEIVE","target.app"); binder.when(Binder::getCallingUid).thenReturn(1000);
        assertEquals(17,broadcast(intent)); assertEquals(0,intent.getFlags());
    }
}
