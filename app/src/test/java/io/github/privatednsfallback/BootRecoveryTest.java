package io.github.privatednsfallback;

import static org.junit.Assert.*;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.provider.Settings;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class BootRecoveryTest {
  private Context c;

  @Before
  public void ownedStrictBeforeShutdown() {
    c = RuntimeEnvironment.getApplication();
    Settings.Global.putString(c.getContentResolver(), Config.MODE, "opportunistic");
    Config.prepareProfile(c, "dns.example");
    Config.prefs(c)
        .edit()
        .putBoolean("enabled", true)
        .putString("host", "dns.example")
        .putString("address", "192.0.2.53")
        .commit();
    Config.select(c, "dns.example");
  }

  @Test
  public void lockedBootWithoutVpnResumesAndReleasesStrict() {
    var request = new AtomicReference<Intent>();
    Context boot =
        new ContextWrapper(c) {
          @Override
          public ComponentName startForegroundService(Intent intent) {
            request.set(intent);
            return intent.getComponent();
          }
        };
    new BootReceiver().onReceive(boot, new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED));
    assertEquals(DnsService.class.getName(), request.get().getComponent().getClassName());
    var service = Robolectric.buildService(DnsService.class).create();
    try {
      service.get().onStartCommand(request.get(), 0, 1);
      assertTrue(DnsService.running);
      assertEquals("opportunistic", Config.read(c, Config.MODE));
      assertTrue(Config.prefs(c).getBoolean("enabled", false));
      service.get().onStartCommand(null, 0, 2);
      assertEquals("opportunistic", Config.read(c, Config.MODE));
    } finally {
      service.destroy();
    }
  }

  @Test
  public void rejectedUpgradeFromLegacyStrictStillFallsBack() {
    Config.prefs(c)
        .edit()
        .clear()
        .putBoolean("enabled", true)
        .putString("host", "dns.example")
        .putString("address", "192.0.2.53")
        .commit();
    denyLaunch(Intent.ACTION_MY_PACKAGE_REPLACED);
    assertEquals("opportunistic", Config.read(c, Config.MODE));
    assertTrue(Config.prefs(c).getBoolean("enabled", false));
  }

  @Test
  public void deniedLegacyUpdateLeavesDifferentStrictProviderAlone() {
    Config.prefs(c)
        .edit()
        .clear()
        .putBoolean("enabled", true)
        .putString("host", "dns.example")
        .commit();
    Settings.Global.putString(c.getContentResolver(), Config.SPECIFIER, "manual.example");
    denyLaunch(Intent.ACTION_MY_PACKAGE_REPLACED);
    assertFalse(Config.ownsStrict(c));
    assertEquals("hostname", Config.read(c, Config.MODE));
    assertEquals("manual.example", Config.read(c, Config.SPECIFIER));
  }

  @Test
  public void manualOffAfterRefusedStartSurvivesStickyResume() {
    denyLaunch(Intent.ACTION_BOOT_COMPLETED);
    Settings.Global.putString(c.getContentResolver(), Config.MODE, "off");
    var service = Robolectric.buildService(DnsService.class).create();
    try {
      service.get().onStartCommand(null, 0, 1);
      assertFalse(DnsService.running);
      assertEquals("off", Config.read(c, Config.MODE));
    } finally {
      service.destroy();
    }
  }

  @Test
  public void rejectedBootLaunchReleasesOwnedStrictAndKeepsResumeRequest() {
    denyLaunch(Intent.ACTION_BOOT_COMPLETED);
    assertEquals("opportunistic", Config.read(c, Config.MODE));
    assertTrue(Config.prefs(c).getBoolean("enabled", false));
    assertTrue(Config.prefs(c).getString("notice", "").contains("launch blocked"));
  }

  private void denyLaunch(String action) {
    Context blocked =
        new ContextWrapper(c) {
          @Override
          public ComponentName startForegroundService(Intent intent) {
            throw new IllegalStateException("Background launch denied");
          }
        };
    new BootReceiver().onReceive(blocked, new Intent(action));
  }
}
