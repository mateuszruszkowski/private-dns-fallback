package io.github.privatednsfallback;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.InetAddresses;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.*;
import java.util.Locale;
import javax.net.ssl.SNIHostName;

public final class MainActivity extends Activity {
    private LinearLayout form;
    private TextView state;
    private EditText hostname, address;
    private Button action;
    private SharedPreferences prefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (p,k) -> render();

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = Config.prefs(this);
        ScrollView scroll = new ScrollView(this);
        form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(24 * getResources().getDisplayMetrics().density);
        form.setPadding(pad,pad*2,pad,pad);
        scroll.addView(form);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(scroll);
        TextView title = label("Private DNS Fallback", 30);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        state = label("", 18);
        state.setPadding(0,pad/2,0,pad);
        label("Private DNS hostname", 16);
        hostname = input("dns.example.net", "host", R.string.suggested_resolver_hostname);
        label("Resolver IP inside the VPN", 16);
        address = input("192.0.2.53", "address", R.string.suggested_resolver_address);
        action = new Button(this);
        action.setOnClickListener(v -> toggle());
        form.addView(action);
        TextView help = label("When the VPN resolver is unavailable, Android uses Automatic DNS. Queries may then use your network provider.", 15);
        help.setPadding(0,pad,0,0);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        render();
    }

    private TextView label(String s, int size) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(size); form.addView(v);
        return v;
    }

    private EditText input(String hint, String key, int suggestion) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        String draftKey = "draft_" + key;
        String saved = prefs.getString(key, getString(suggestion));
        input.setHint(hint);
        input.setText(prefs.getBoolean("enabled", false) ? saved : prefs.getString(draftKey, saved));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) {
                prefs.edit().putString(draftKey, text.toString()).apply();
            }
        });
        form.addView(input);
        return input;
    }

    private void toggle() {
        if (prefs.getBoolean("enabled",false)) {
            prefs.edit().putBoolean("enabled",false).apply();
            stopService(new Intent(this,DnsService.class));
            if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                Config.release(this);
            }
            render();
            return;
        }
        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            state.setText("Grant WRITE_SECURE_SETTINGS using ADB. See the installation guide.");
            return;
        }
        String host = hostname.getText().toString().trim().toLowerCase(Locale.ROOT);
        try { new SNIHostName(host); }
        catch (IllegalArgumentException e) { hostname.setError("Enter a TLS hostname"); return; }
        if (!host.contains(".") || InetAddresses.isNumericAddress(host)) {
            hostname.setError("Enter a fully qualified DNS hostname"); return;
        }
        String ip = address.getText().toString().trim();
        if (!InetAddresses.isNumericAddress(ip)) { address.setError("Enter an IPv4 or IPv6 address"); return; }
        prefs.edit().putString("host",host).putString("address",ip)
                .remove("draft_host").remove("draft_address")
                .putString("status","Checking network").putBoolean("enabled",true).apply();
        startForegroundService(new Intent(this,DnsService.class));
        render();
    }

    private void render() {
        boolean enabled = prefs.getBoolean("enabled",false);
        state.setText(enabled ? prefs.getString("status","Checking network") : "Stopped");
        hostname.setEnabled(!enabled); address.setEnabled(!enabled);
        action.setText(enabled ? "Stop and use Automatic DNS" : "Start monitoring");
    }
    @Override protected void onResume() { super.onResume(); prefs.registerOnSharedPreferenceChangeListener(listener); render(); }
    @Override protected void onPause() { prefs.unregisterOnSharedPreferenceChangeListener(listener); super.onPause(); }
}
