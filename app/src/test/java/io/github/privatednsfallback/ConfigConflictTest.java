package io.github.privatednsfallback;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import java.util.HashMap;
import java.util.Map;
import org.junit.*;
import org.mockito.MockedStatic;

public class ConfigConflictTest {
  private final Context context = mock(Context.class);
  private final SharedPreferences prefs = mock(SharedPreferences.class);
  private final Map<String, Object> disk = new HashMap<>();
  private final Map<String, String> system = new HashMap<>();
  private Runnable afterCommit = () -> {};
  private MockedStatic<Settings.Global> settings;
  private int systemWrites;
  private java.util.function.Consumer<String> beforeRead = key -> {};

  @Before
  public void setUp() {
    ContentResolver resolver = mock(ContentResolver.class);
    when(context.getContentResolver()).thenReturn(resolver);
    when(context.createDeviceProtectedStorageContext()).thenReturn(context);
    when(context.getSharedPreferences("config", 0)).thenReturn(prefs);
    when(prefs.contains(anyString())).thenAnswer(a -> disk.containsKey(a.getArgument(0)));
    when(prefs.getString(anyString(), nullable(String.class)))
        .thenAnswer(a -> disk.getOrDefault(a.getArgument(0), a.getArgument(1)));
    when(prefs.getBoolean(anyString(), anyBoolean()))
        .thenAnswer(a -> disk.getOrDefault(a.getArgument(0), a.getArgument(1)));
    when(prefs.edit()).thenAnswer(a -> editor());
    settings = mockStatic(Settings.Global.class);
    settings
        .when(() -> Settings.Global.getString(eq(resolver), anyString()))
        .thenAnswer(
            a -> {
              String key = a.getArgument(1);
              beforeRead.accept(key);
              return system.get(key);
            });
    settings
        .when(() -> Settings.Global.putString(eq(resolver), anyString(), nullable(String.class)))
        .thenAnswer(
            a -> {
              systemWrites++;
              system.put(a.getArgument(1), a.getArgument(2));
              return true;
            });
    system.put(Config.MODE, "opportunistic");
    system.put(Config.SPECIFIER, "old.example");
    Config.observe(prefs.edit(), Config.actual(context)).commit();
  }

  private SharedPreferences.Editor editor() {
    SharedPreferences.Editor e = mock(SharedPreferences.Editor.class);
    Map<String, Object> batch = new HashMap<>();
    when(e.putString(anyString(), nullable(String.class)))
        .thenAnswer(
            a -> {
              batch.put(a.getArgument(0), a.getArgument(1));
              return e;
            });
    when(e.putBoolean(anyString(), anyBoolean()))
        .thenAnswer(
            a -> {
              batch.put(a.getArgument(0), a.getArgument(1));
              return e;
            });
    when(e.remove(anyString()))
        .thenAnswer(
            a -> {
              batch.put(a.getArgument(0), null);
              return e;
            });
    when(e.commit())
        .thenAnswer(
            a -> {
              batch.forEach(
                  (k, v) -> {
                    if (v == null) disk.remove(k);
                    else disk.put(k, v);
                  });
              afterCommit.run();
              return true;
            });
    return e;
  }

  @Test
  public void manualHostChangeAtLastModeReadPreservesStrict() {
    Config.select(context, "managed.example");
    int writesBefore = systemWrites;
    int[] fallbackReads = {0};
    beforeRead =
        key -> {
          if (Config.MODE.equals(key)
              && "opportunistic".equals(disk.get("pendingMode"))
              && ++fallbackReads[0] == 3) system.put(Config.SPECIFIER, "manual.example");
        };
    assertThrows(SecurityException.class, () -> Config.select(context, null));
    Config.recover(context);
    Config.release(context);
    assertEquals(writesBefore, systemWrites);
    assertEquals("hostname", system.get(Config.MODE));
    assertEquals("manual.example", system.get(Config.SPECIFIER));
  }

  @Test
  public void restartWithExactPendingTargetRecoversOwnership() {
    disk.put("pendingMode", "hostname");
    disk.put("pendingHost", "dns.example");
    system.put(Config.MODE, "hostname");
    system.put(Config.SPECIFIER, "dns.example");
    Config.recover(context);
    assertTrue(Config.ownsStrict(context));
    Config.release(context);
    assertEquals("opportunistic", system.get(Config.MODE));
  }

  @After
  public void close() {
    settings.close();
  }

  @Test
  public void manualStrictSelectedAtFinalModeGuardRemainsManual() {
    int[] modeReads = {0};
    beforeRead =
        key -> {
          if (Config.MODE.equals(key) && systemWrites == 1 && ++modeReads[0] == 2)
            system.put(Config.MODE, "hostname");
        };
    assertThrows(SecurityException.class, () -> Config.select(context, "new.example"));
    assertFalse(disk.containsKey("pendingMode"));
    Config.recover(context);
    Config.release(context);
    assertEquals(1, systemWrites);
    assertEquals("hostname", system.get(Config.MODE));
    assertEquals("new.example", system.get(Config.SPECIFIER));
  }

  @Test
  public void matchingExternalTargetAfterJournalDoesNotBecomeOurs() {
    afterCommit =
        () -> {
          if (disk.containsKey("pendingMode")) {
            system.put(Config.MODE, "hostname");
            system.put(Config.SPECIFIER, "new.example");
            afterCommit = () -> {};
          }
        };
    assertThrows(SecurityException.class, () -> Config.select(context, "new.example"));
    assertFalse(disk.containsKey("pendingMode"));
    Config.recover(context);
    Config.release(context);
    assertEquals("hostname", system.get(Config.MODE));
    assertEquals(0, systemWrites);
  }

  @Test
  public void applyingProfileDoesNotRecaptureManualSelection() {
    Config.select(context, "old.example");
    int writes = systemWrites;
    afterCommit =
        () -> {
          system.put(Config.SPECIFIER, "manual.example");
          afterCommit = () -> {};
        };
    assertThrows(SecurityException.class, () -> Config.prepareProfile(context, "new.example"));
    Config.release(context);
    assertEquals("manual.example", system.get(Config.SPECIFIER));
    assertEquals("hostname", system.get(Config.MODE));
    assertEquals(writes, systemWrites);
  }
}
