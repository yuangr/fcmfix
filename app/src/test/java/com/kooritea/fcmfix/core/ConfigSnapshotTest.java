package com.kooritea.fcmfix.core;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ConfigSnapshotTest {
    @Test public void publicationDoesNotShareMutablePreferences() {
        Set<String> apps = new HashSet<>(Arrays.asList("one", "two"));
        Map<String, Object> source = new HashMap<>();
        source.put("allowList", apps); source.put("includeIceBoxDisableApp", true);
        ConfigSnapshot snapshot = ConfigSnapshot.from(source);
        apps.clear(); source.clear();
        assertEquals(new HashSet<>(Arrays.asList("one", "two")), snapshot.allowList);
        assertTrue(snapshot.getBoolean("includeIceBoxDisableApp", false));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.allowList.add("three"));
    }
    @Test public void malformedValuesFailClosed() {
        Map<String,Object> source = new HashMap<>();
        source.put("allowList", new HashSet<>(Arrays.asList("one", 2)));
        source.put("includeIceBoxDisableApp", "true");
        ConfigSnapshot snapshot = ConfigSnapshot.from(source);
        assertEquals(Collections.singleton("one"), snapshot.allowList);
        assertFalse(snapshot.includeIceBoxDisableApp);
        assertTrue(snapshot.getBoolean("unknown", true));
        assertTrue(ConfigSnapshot.EMPTY.getBoolean("includeIceBoxDisableApp", true));
        assertFalse(ConfigSnapshot.EMPTY.loaded);
    }
}
