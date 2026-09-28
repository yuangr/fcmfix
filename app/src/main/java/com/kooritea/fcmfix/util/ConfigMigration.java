package com.kooritea.fcmfix.util;

import android.content.SharedPreferences;
import com.kooritea.fcmfix.core.ConfigSnapshot;

/** Restores an explicitly retained local cache only when the framework has no config yet. */
public final class ConfigMigration {
    public enum Result { NONE, IMPORTED, FAILED }
    private ConfigMigration() {}

    public static Result seedEmptyRemote(SharedPreferences remote, SharedPreferences local) {
        if (!remote.getAll().isEmpty() || !local.getBoolean("hasLocalCache", false)) return Result.NONE;
        ConfigSnapshot snapshot = ConfigSnapshot.from(local.getAll());
        boolean saved = remote.edit().putBoolean("init", true)
                .putStringSet("allowList", snapshot.allowList)
                .putBoolean("disableAutoCleanNotification", snapshot.disableAutoCleanNotification)
                .putBoolean("includeIceBoxDisableApp", snapshot.includeIceBoxDisableApp)
                .putBoolean("noResponseNotification", snapshot.noResponseNotification).commit();
        return saved ? Result.IMPORTED : Result.FAILED;
    }
}
