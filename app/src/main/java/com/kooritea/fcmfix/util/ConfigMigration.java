package com.kooritea.fcmfix.util;

import android.content.SharedPreferences;
import com.kooritea.fcmfix.core.ConfigSnapshot;

/** Restores a retained cache into empty preferences, or on a one-time explicit restore marker. */
public final class ConfigMigration {
    public enum Result { NONE, IMPORTED, FAILED }
    private ConfigMigration() {}

    public static Result seedEmptyRemote(SharedPreferences remote, SharedPreferences local) {
        boolean explicitRestore = local.getBoolean("restoreRemotePending", false);
        if (!local.getBoolean("hasLocalCache", false)
                || (!explicitRestore && !remote.getAll().isEmpty())) return Result.NONE;
        ConfigSnapshot snapshot = ConfigSnapshot.from(local.getAll());
        boolean saved = remote.edit().putBoolean("init", true)
                .putStringSet("allowList", snapshot.allowList)
                .putBoolean("disableAutoCleanNotification", snapshot.disableAutoCleanNotification)
                .putBoolean("includeIceBoxDisableApp", snapshot.includeIceBoxDisableApp)
                .putBoolean("noResponseNotification", snapshot.noResponseNotification).commit();
        if (!saved) return Result.FAILED;
        if (explicitRestore && !local.edit().putBoolean("restoreRemotePending", false).commit()) return Result.FAILED;
        return Result.IMPORTED;
    }
}
