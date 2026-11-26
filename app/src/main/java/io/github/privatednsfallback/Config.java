package io.github.privatednsfallback;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

final class Config {
    static final String MODE = "private_dns_mode";
    static final String SPECIFIER = "private_dns_specifier";

    static SharedPreferences prefs(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences("config", Context.MODE_PRIVATE);
    }

    static void setMode(Context c, String mode) {
        if (!mode.equals(Settings.Global.getString(c.getContentResolver(), MODE))
                && !Settings.Global.putString(c.getContentResolver(), MODE, mode)) {
            throw new SecurityException("Private DNS mode write rejected");
        }
    }

    static void release(Context c) {
        String saved = prefs(c).getString("host", "");
        if (!saved.isEmpty() && saved.equals(Settings.Global.getString(c.getContentResolver(), SPECIFIER))) {
            setMode(c, "opportunistic");
        }
    }
}
