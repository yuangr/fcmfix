package com.kooritea.fcmfix.util;

import android.content.SharedPreferences;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class ConfigMigrationTest {
    SharedPreferences remote,local; SharedPreferences.Editor editor;
    @Before public void setup() {
        remote=mock(SharedPreferences.class); local=mock(SharedPreferences.class);
        editor=mock(SharedPreferences.Editor.class, RETURNS_SELF);
        when(remote.getAll()).thenReturn(Collections.emptyMap()); when(remote.edit()).thenReturn(editor);
        when(local.getBoolean("hasLocalCache",false)).thenReturn(true);
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private void localValues() {
        Map<String,Object> values=new HashMap<>(); values.put("allowList",new HashSet<>(Arrays.asList("one","two")));
        values.put("includeIceBoxDisableApp",true); values.put("disableAutoCleanNotification",false);
        when(local.getAll()).thenReturn((Map)values);
    }
    @Test public void retainedCacheSeedsFrameworkOnFreshInstallation() {
        localValues(); when(editor.commit()).thenReturn(true);
        assertEquals(ConfigMigration.Result.IMPORTED,ConfigMigration.seedEmptyRemote(remote,local));
        verify(editor).putStringSet("allowList",new HashSet<>(Arrays.asList("one","two")));
        verify(editor).putBoolean("init",true); verify(editor).putBoolean("includeIceBoxDisableApp",true);
        verify(editor).putBoolean("disableAutoCleanNotification",false); verify(editor).putBoolean("noResponseNotification",false);
        verify(editor).commit();
    }
    @Test public void existingRemoteIncludingDeliberatelyEmptyAllowListAlwaysWins() {
        doReturn(Collections.singletonMap("allowList",Collections.emptySet())).when(remote).getAll();
        assertEquals(ConfigMigration.Result.NONE,ConfigMigration.seedEmptyRemote(remote,local)); verifyNoInteractions(editor);
    }
    @Test public void absentLocalCacheNeverCreatesConfiguration() {
        when(local.getBoolean("hasLocalCache",false)).thenReturn(false);
        assertEquals(ConfigMigration.Result.NONE,ConfigMigration.seedEmptyRemote(remote,local)); verifyNoInteractions(editor);
    }
    @Test public void failedCommitReportsFailureSoCallerKeepsLocalState() {
        localValues(); when(editor.commit()).thenReturn(false);
        assertEquals(ConfigMigration.Result.FAILED,ConfigMigration.seedEmptyRemote(remote,local));
        verify(editor).commit(); verify(local,never()).edit();
    }
}
