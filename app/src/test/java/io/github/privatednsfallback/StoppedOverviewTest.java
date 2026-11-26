package io.github.privatednsfallback;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class StoppedOverviewTest {
  private static boolean shows(View v, String text) {
    if (v.getVisibility() != View.VISIBLE) return false;
    if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) return true;
    if (v instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) v;
      for (int i = 0; i < group.getChildCount(); i++) {
        if (shows(group.getChildAt(i), text)) return true;
      }
    }
    return false;
  }

  @Test
  public void strandedOverviewShowsTheStoredReasonAndRecoveryAction() {
    Context c = RuntimeEnvironment.getApplication();
    String reason = "Background launch blocked · check Android Private DNS";
    Config.prefs(c)
        .edit()
        .putString("host", "mine.example")
        .putString("ownedHost", "mine.example")
        .putString("ownedMode", "hostname")
        .putString("notice", reason)
        .commit();
    Settings.Global.putString(c.getContentResolver(), Config.SPECIFIER, "mine.example");
    Settings.Global.putString(c.getContentResolver(), Config.MODE, "hostname");
    try (var activity = Robolectric.buildActivity(MainActivity.class).setup()) {
      assertTrue(
          shows(
              activity.get().getWindow().getDecorView(),
              reason + "\nOpen Setup to restore Automatic DNS"));
    }
  }

  @Test
  public void refusedStartPreservesProviderAndExplainsWhyOnOverview() {
    Context c = RuntimeEnvironment.getApplication();
    Config.prefs(c)
        .edit()
        .putString("host", "mine.example")
        .putString("address", "192.0.2.53")
        .commit();
    Settings.Global.putString(c.getContentResolver(), Config.SPECIFIER, "someone-else.example");
    Settings.Global.putString(c.getContentResolver(), Config.MODE, "hostname");
    var service = Robolectric.buildService(DnsService.class).create();
    try {
      service
          .get()
          .onStartCommand(
              new Intent(c, DnsService.class)
                  .setAction(DnsService.START)
                  .putExtra("host", "mine.example")
                  .putExtra("address", "192.0.2.53"),
              0,
              1);
      assertFalse(DnsService.running);
      assertEquals("someone-else.example", Config.read(c, Config.SPECIFIER));
      assertEquals("hostname", Config.read(c, Config.MODE));
      String reason = Config.prefs(c).getString("notice", null);
      assertEquals("Check DNS permission and Android provider settings", reason);
      try (var activity = Robolectric.buildActivity(MainActivity.class).setup()) {
        assertTrue(
            "stopped Overview must show the persisted refusal reason",
            shows(activity.get().getWindow().getDecorView(), reason));
      }
    } finally {
      service.destroy();
    }
  }
}
