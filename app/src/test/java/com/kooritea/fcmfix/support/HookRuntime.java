package com.kooritea.fcmfix.support;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Executes registered interceptors against the production bridge, with real original methods. */
public final class HookRuntime {
    public final XposedInterface api = mock(XposedInterface.class);
    private final Map<Executable,List<XposedInterface.Hooker>> hooks = new HashMap<>();
    public HookRuntime() {
        when(api.hook(any(Executable.class))).thenAnswer(call -> {
            Executable executable = call.getArgument(0);
            XposedInterface.HookBuilder builder = mock(XposedInterface.HookBuilder.class);
            when(builder.intercept(any(XposedInterface.Hooker.class))).thenAnswer(registration -> {
                XposedInterface.Hooker hook = registration.getArgument(0);
                hooks.computeIfAbsent(executable,key -> new ArrayList<>()).add(hook);
                XposedInterface.HookHandle handle = mock(XposedInterface.HookHandle.class);
                doAnswer(ignored -> { hooks.get(executable).remove(hook); return null; }).when(handle).unhook();
                return handle;
            });
            return builder;
        });
    }
    public int count(Executable method) { return hooks.getOrDefault(method, Collections.emptyList()).size(); }
    public Object invoke(Method method, Object receiver, Object... args) throws Throwable {
        return invokeAt(new ArrayList<>(hooks.getOrDefault(method, Collections.emptyList())),0,method,receiver,args);
    }
    private Object invokeAt(List<XposedInterface.Hooker> chain,int index,Method method,Object receiver,Object[] args) throws Throwable {
        if (index == chain.size()) {
            try { return method.invoke(receiver,args); } catch (InvocationTargetException error) { throw error.getCause(); }
        }
        XposedInterface.Chain link = mock(XposedInterface.Chain.class);
        when(link.getExecutable()).thenReturn(method); when(link.getThisObject()).thenReturn(receiver);
        when(link.getArgs()).thenReturn(Arrays.asList(args));
        when(link.proceed(any(Object[].class))).thenAnswer(call -> invokeAt(chain,index+1,method,receiver,call.getArgument(0)));
        return chain.get(index).intercept(link);
    }
}
