package com.kooritea.fcmfix.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** One immutable configuration publication for every Hook reader. */
public final class ConfigSnapshot {
    public static final ConfigSnapshot EMPTY = new ConfigSnapshot(false, Collections.emptySet(), false, false, false);
    public final boolean loaded;
    public final Set<String> allowList;
    public final boolean disableAutoCleanNotification;
    public final boolean includeIceBoxDisableApp;
    public final boolean noResponseNotification;

    private ConfigSnapshot(boolean loaded, Set<String> apps, boolean keep, boolean icebox, boolean notify) {
        this.loaded = loaded;
        this.allowList = Collections.unmodifiableSet(new HashSet<>(apps));
        disableAutoCleanNotification = keep;
        includeIceBoxDisableApp = icebox;
        noResponseNotification = notify;
    }

    public static ConfigSnapshot from(Map<String, ?> values) {
        Set<String> apps = new HashSet<>();
        Object raw = values.get("allowList");
        if (raw instanceof Set<?>) for (Object item : (Set<?>) raw) if (item instanceof String) apps.add((String) item);
        return new ConfigSnapshot(true, apps, Boolean.TRUE.equals(values.get("disableAutoCleanNotification")),
                Boolean.TRUE.equals(values.get("includeIceBoxDisableApp")), Boolean.TRUE.equals(values.get("noResponseNotification")));
    }

    public boolean getBoolean(String key, boolean fallback) {
        if (!loaded) return fallback;
        switch (key) {
            case "disableAutoCleanNotification": return disableAutoCleanNotification;
            case "includeIceBoxDisableApp": return includeIceBoxDisableApp;
            case "noResponseNotification": return noResponseNotification;
            default: return fallback;
        }
    }
}
