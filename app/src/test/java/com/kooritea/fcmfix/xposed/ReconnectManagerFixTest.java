package com.kooritea.fcmfix.xposed;

import android.content.*;
import android.os.UserManager;
import com.kooritea.fcmfix.core.ConfigSnapshot;
import com.kooritea.fcmfix.core.LatestTaskScheduler;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.support.HookRuntime;
import org.junit.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class ReconnectManagerFixTest {
    public static final class Timer {
        public String alarmType="GCM_HB_ALARM"; public long deadline=100000;
        public int calls;
        public final void setTimeout(long delay) { calls++; }
        @Override public String toString() { return "timer"; }
    }
    @Test public void repeatedServiceInitialisationRegistersEachHookOnlyOnce() throws Throwable {
        HookRuntime runtime=new HookRuntime(); XposedBridge.init(runtime.api);
        Context context=mock(Context.class); UserManager users=mock(UserManager.class);
        SharedPreferences preferences=mock(SharedPreferences.class);
        when(context.getSystemService(UserManager.class)).thenReturn(users);
        when(context.getSharedPreferences(anyString(),anyInt())).thenReturn(preferences);
        when(preferences.getString(eq("timer_class"),anyString())).thenReturn(Timer.class.getName());
        when(preferences.getString(eq("timer_settimeout_method"),anyString())).thenReturn("setTimeout");
        when(preferences.getString(eq("timer_alarm_type_property"),anyString())).thenReturn("alarmType");
        when(preferences.getLong(eq("heartbeatInterval"),anyLong())).thenReturn(60000L);
        set("context",context); set("config",ConfigSnapshot.from(Collections.emptyMap()));
        Field field=ReconnectManagerFix.class.getDeclaredField("watchdogs"); field.setAccessible(true);
        LatestTaskScheduler<?> watchdogs=(LatestTaskScheduler<?>)field.get(null);
        try {
            ReconnectManagerFix fix=new ReconnectManagerFix(getClass().getClassLoader());
            for (int i=0;i<3;i++) fix.startHook();
            Method timeout=Timer.class.getMethod("setTimeout",long.class), display=Timer.class.getMethod("toString");
            assertEquals(1,runtime.count(timeout)); assertEquals(1,runtime.count(display));
            Timer timer=new Timer(); for (int i=0;i<10;i++) runtime.invoke(timeout,timer,60000L);
            assertEquals(10,timer.calls); assertEquals(1,watchdogs.pendingCount());
        } finally { watchdogs.cancelAll(); set("context",null); set("config",ConfigSnapshot.EMPTY); }
    }
    private static void set(String name,Object value) throws Exception { Field field=XposedModule.class.getDeclaredField(name); field.setAccessible(true); field.set(null,value); }
}
