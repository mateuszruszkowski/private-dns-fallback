package io.github.privatednsfallback;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import java.util.Objects;

final class Config {
  static final String MODE = "private_dns_mode";
  static final String SPECIFIER = "private_dns_specifier";

  static SharedPreferences prefs(Context c) {
    return c.createDeviceProtectedStorageContext()
        .getSharedPreferences("config", Context.MODE_PRIVATE);
  }

  static String read(Context c, String key) {
    return Settings.Global.getString(c.getContentResolver(), key);
  }

  private static void write(Context c, String key, String value) {
    if (!Objects.equals(value, read(c, key))
        && !Settings.Global.putString(c.getContentResolver(), key, value)) {
      throw new SecurityException("Android rejected the DNS setting");
    }
    if (!Objects.equals(value, read(c, key))) throw new SecurityException("DNS setting changed");
  }

  static DnsPair actual(Context c) {
    return new DnsPair(read(c, MODE), read(c, SPECIFIER));
  }

  private static DnsPair stored(SharedPreferences p, String prefix) {
    return new DnsPair(p.getString(prefix + "Mode", null), p.getString(prefix + "Host", null));
  }

  static SharedPreferences.Editor observe(SharedPreferences.Editor e, DnsPair pair) {
    return e.putBoolean("observing", true)
        .putString("baseMode", pair.mode)
        .putString("baseHost", pair.host);
  }

  private static void persist(SharedPreferences.Editor edit) {
    if (!edit.commit()) throw new IllegalStateException("Unable to save DNS state");
  }

  static boolean matches(Context c) {
    SharedPreferences p = prefs(c);
    if (p.getBoolean("observing", false)) return stored(p, "base").same(actual(c));
    String mode = p.getString("ownedMode", null);
    return mode == null
        || (mode.equals(read(c, MODE))
            && (!"hostname".equals(mode)
                || Objects.equals(read(c, SPECIFIER), p.getString("ownedHost", ""))));
  }

  static boolean ownsStrict(Context c) {
    DnsPair actual = actual(c);
    if (!"hostname".equals(actual.mode)) return false;
    SharedPreferences p = prefs(c);
    return stored(p, "owned").same(actual) || stored(p, "pending").same(actual);
  }

  static void recover(Context c) {
    SharedPreferences p = prefs(c);
    if (!p.contains("pendingMode")) return;
    DnsPair current = actual(c), target = stored(p, "pending");
    SharedPreferences.Editor edit = p.edit().remove("pendingMode").remove("pendingHost");
    switch (DnsPair.recover(stored(p, "base"), target, current)) {
      case COMPLETE:
        observe(edit, current)
            .putString("ownedMode", current.mode)
            .putString("ownedHost", current.host);
        break;
      case HOST_ONLY:
        observe(edit, current);
        break;
      case EXTERNAL:
        // Preserve the mismatching baseline until the service acknowledges the takeover.
        edit.remove("ownedMode").remove("ownedHost");
        break;
      case UNCHANGED:
        break;
    }
    persist(edit);
  }

  static DnsPair prepareProfile(Context c, String requested) {
    recover(c);
    SharedPreferences p = prefs(c);
    DnsPair before;
    if (p.getBoolean("observing", false)) before = stored(p, "base");
    else if (p.contains("ownedMode")) before = stored(p, "owned");
    else before = actual(c); // The user's explicit Start accepts this initial state.
    before.requireUnchanged(actual(c));
    boolean managed = stored(p, "owned").same(before) && p.contains("ownedMode");
    if ("hostname".equals(before.mode) && !managed && !Objects.equals(requested, before.host)) {
      throw new SecurityException("Android has a different DNS provider");
    }
    persist(observe(p.edit(), before));
    if (managed && "hostname".equals(before.mode)) {
      select(c, null);
      before = new DnsPair("opportunistic", before.host);
    }
    before.requireUnchanged(actual(c));
    return before;
  }

  private static void guardPreparedWrite(Context c, DnsPair expected) {
    if (!expected.same(actual(c))) {
      // This stage has not executed its write. A matching external target must not be adopted on
      // recovery.
      persist(prefs(c).edit().remove("pendingMode").remove("pendingHost"));
      throw new SecurityException("DNS setting changed");
    }
  }

  static void select(Context c, String host) {
    recover(c);
    if (!matches(c)) throw new SecurityException("DNS setting changed");
    SharedPreferences p = prefs(c);
    // Keep the baseline accepted before the probe. A new observation here could
    // adopt a manual change that arrived between the health result and this call.
    DnsPair before = stored(p, p.getBoolean("observing", false) ? "base" : "owned");
    before.requireUnchanged(actual(c));
    DnsPair target =
        new DnsPair(host == null ? "opportunistic" : "hostname", host == null ? before.host : host);
    if (target.same(before) && stored(p, "owned").same(target)) return;
    persist(
        observe(p.edit(), before)
            .putString("pendingMode", target.mode)
            .putString("pendingHost", target.host));
    guardPreparedWrite(c, before);
    if (host != null) write(c, SPECIFIER, host);
    guardPreparedWrite(c, new DnsPair(before.mode, target.host));
    write(c, MODE, target.mode);
    recover(c);
    if (!stored(p, "owned").same(target) || !actual(c).same(target))
      throw new SecurityException("DNS setting changed");
  }

  static void release(Context c) {
    recover(c);
    if (ownsStrict(c) && matches(c)) select(c, null);
    persist(
        prefs(c)
            .edit()
            .remove("ownedMode")
            .remove("ownedHost")
            .remove("baseMode")
            .remove("baseHost")
            .remove("observing"));
  }
}
