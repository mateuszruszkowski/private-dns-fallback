package io.github.privatednsfallback;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.Locale;
import javax.net.ssl.SNIHostName;

public final class MainActivity extends Activity {
  private static final int INK = 0xff162d2c,
      SECONDARY = 0xff435956,
      GOOD = 0xff086950,
      WARN = 0xff895416,
      DANGER = 0xffab3035;
  private LinearLayout page, rows;
  private TextView summary;
  private EditText host, address;
  private Button start, stop;
  private SharedPreferences prefs;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Runnable update =
      new Runnable() {
        @Override
        public void run() {
          render();
          main.postDelayed(this, 1000);
        }
      };
  private final TextView[] checks = new TextView[8];
  private String command = "";
  private long commandTime;

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    prefs = Config.prefs(this);
    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(0xfff2f6f4);
    scroll.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          var bars =
              insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
          v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return insets;
        });
    page = new LinearLayout(this);
    page.setOrientation(LinearLayout.VERTICAL);
    page.setPadding(dp(20), dp(20), dp(20), dp(28));
    scroll.addView(page);
    setContentView(scroll);
    page.addView(text("Private DNS Fallback", 27, true));
    summary = text("", 17, true);
    summary.setPadding(0, dp(6), 0, dp(14));
    summary.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    page.addView(summary);
    rows = new LinearLayout(this);
    rows.setOrientation(LinearLayout.VERTICAL);
    rows.setBackground(card());
    page.addView(rows);
    for (int i = 0; i < checks.length; i++) {
      final int index = i;
      TextView row = text("", 15, false);
      row.setPadding(dp(14), dp(6), dp(12), dp(6));
      row.setMinHeight(dp(48));
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setFocusable(true);
      row.setOnClickListener(v -> details(index));
      checks[i] = row;
      rows.addView(row);
    }
    TextView endpoint = text("Resolver configuration", 20, true);
    endpoint.setPadding(0, dp(18), 0, dp(5));
    page.addView(endpoint);
    host =
        input(
            "Private DNS hostname",
            "dns.example.net",
            "host",
            R.string.suggested_resolver_hostname);
    address = input("VPN address", "192.0.2.53", "address", R.string.suggested_resolver_address);
    start = new Button(this);
    start.setOnClickListener(v -> save());
    page.addView(start);
    stop = new Button(this);
    stop.setText("Stop and use Automatic DNS");
    stop.setOnClickListener(v -> send(DnsService.STOP, null, null));
    page.addView(stop);
    page.setFocusableInTouchMode(true);
    page.requestFocus();
    render();
  }

  private GradientDrawable card() {
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(Color.WHITE);
    shape.setCornerRadius(dp(14));
    return shape;
  }

  private int dp(int n) {
    return Math.round(getResources().getDisplayMetrics().density * n);
  }

  private TextView text(String value, int size, boolean bold) {
    TextView v = new TextView(this);
    v.setText(value);
    v.setTextSize(size);
    v.setTextColor(INK);
    if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    return v;
  }

  private EditText input(String label, String hint, String key, int fallback) {
    EditText edit = new EditText(this);
    edit.setId(View.generateViewId());
    edit.setSaveEnabled(false);
    TextView caption = text(label, 14, false);
    caption.setLabelFor(edit.getId());
    page.addView(caption);
    edit.setSingleLine(true);
    edit.setInputType(
        InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_VARIATION_URI
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    edit.setHint(hint);
    edit.setText(prefs.getString("draft_" + key, prefs.getString(key, getString(fallback))));
    edit.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int count, int after) {}

          public void onTextChanged(CharSequence s, int st, int before, int count) {}

          public void afterTextChanged(Editable s) {
            prefs.edit().putString("draft_" + key, s.toString()).apply();
          }
        });
    page.addView(edit);
    return edit;
  }

  private boolean grant() {
    return checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
        == PackageManager.PERMISSION_GRANTED;
  }

  private boolean notifications() {
    NotificationManager nm = getSystemService(NotificationManager.class);
    NotificationChannel channel = nm.getNotificationChannel(DnsService.CHANNEL);
    return nm.areNotificationsEnabled()
        && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
  }

  private void save() {
    if (!grant()) {
      details(1);
      return;
    }
    String h = host.getText().toString().trim().toLowerCase(Locale.ROOT);
    try {
      new SNIHostName(h);
      if (!h.contains(".") || InetAddresses.isNumericAddress(h))
        throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) {
      host.setError("Enter a fully qualified TLS hostname");
      return;
    }
    String ip = address.getText().toString().trim();
    if (!InetAddresses.isNumericAddress(ip)) {
      address.setError("Enter an IPv4 or IPv6 address");
      return;
    }
    send(DnsService.running ? DnsService.APPLY : DnsService.START, h, ip);
  }

  private void send(String action, String h, String ip) {
    Intent i = new Intent(this, DnsService.class).setAction(action);
    if (h != null) i.putExtra("host", h).putExtra("address", ip);
    command = action;
    commandTime = SystemClock.elapsedRealtime();
    try {
      startForegroundService(i);
    } catch (IllegalStateException | SecurityException e) {
      command = "";
      dialog(
          "Service unavailable",
          "Allow background activity in the app settings, then try again.",
          "App settings",
          this::appInfo);
    }
    render();
  }

  private void row(int i, String title, String value, int color) {
    String s = "●   " + title + "  ·  " + value;
    if (!s.contentEquals(checks[i].getText())) checks[i].setText(s);
    checks[i].setTextColor(color);
  }

  private void render() {
    boolean live = DnsService.running, desired = prefs.getBoolean("enabled", false);
    boolean strict = "hostname".equals(Config.read(this, Config.MODE));
    boolean owned = Config.ownsStrict(this);
    boolean stranded = !live && owned;
    String headline =
        live
            ? DnsService.message
            : stranded
                ? "Private DNS selected · monitoring inactive"
                : prefs.getString(
                    "notice",
                    desired ? "Monitoring inactive · start to resume" : "Monitoring stopped");
    if (!headline.contentEquals(summary.getText())) summary.setText(headline);
    summary.setTextColor(stranded ? DANGER : INK);
    row(
        0,
        "Monitoring service",
        live ? "Running" : "Stopped",
        live ? GOOD : stranded ? DANGER : SECONDARY);
    row(1, "DNS permission", grant() ? "Granted" : "Setup required", grant() ? GOOD : DANGER);
    ConnectivityManager cm = getSystemService(ConnectivityManager.class);
    Network network = DnsService.vpn(cm);
    boolean vpn = network != null;
    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
    boolean validated =
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    row(
        2,
        "VPN",
        vpn
            ? (validated ? "Connected · network validated" : "Connected · validation pending")
            : "Disconnected",
        validated ? GOOD : WARN);
    Boolean probe = DnsService.directOk;
    long probeAge = (SystemClock.elapsedRealtime() - DnsService.probeAt) / 1000;
    long systemAge = (SystemClock.elapsedRealtime() - DnsService.systemAt) / 1000;
    row(
        3,
        "Private resolver",
        probe == null
            ? "Unchecked"
            : (probe ? "TLS and DNS OK" : "Unreachable") + " · " + probeAge + " s",
        Boolean.TRUE.equals(probe) && probeAge < 60 ? GOOD : WARN);
    Boolean sys = DnsService.systemOk;
    String selected =
        strict
            ? ("Strict · " + Config.read(this, Config.SPECIFIER))
            : "off".equals(Config.read(this, Config.MODE)) ? "Off" : "Automatic";
    row(
        4,
        "Android DNS",
        selected
            + (Boolean.TRUE.equals(sys)
                ? " · lookup OK · " + systemAge + " s"
                : Boolean.FALSE.equals(sys) ? " · lookup failed" : ""),
        stranded
            ? DANGER
            : Boolean.TRUE.equals(sys) && owned && validated && systemAge < 60 ? GOOD : WARN);
    row(5, "Resume after restart", desired ? "Requested" : "Off", desired ? GOOD : SECONDARY);
    boolean restricted = getSystemService(ActivityManager.class).isBackgroundRestricted();
    boolean exempt =
        getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
    row(
        6,
        "Background activity",
        restricted ? "Restricted" : exempt ? "Battery exemption" : "System managed",
        restricted ? DANGER : exempt ? GOOD : WARN);
    row(
        7,
        "Status notification",
        notifications() ? "Enabled" : "Hidden",
        notifications() ? GOOD : WARN);
    boolean changed =
        !host.getText().toString().trim().equals(prefs.getString("host", ""))
            || !address.getText().toString().trim().equals(prefs.getString("address", ""));
    boolean pending = !command.isEmpty() && SystemClock.elapsedRealtime() - commandTime < 900;
    start.setText(live ? "Apply changes" : "Start monitoring");
    start.setEnabled(!pending && (!live || changed));
    stop.setVisibility(live || desired || stranded ? View.VISIBLE : View.GONE);
    stop.setEnabled(!pending);
  }

  private void details(int i) {
    switch (i) {
      case 0:
        dialog(
            "Monitoring service",
            "Start runs the background service. Closing this screen leaves it running. Stop"
                + " releases private DNS when it belongs to this app.",
            null,
            null);
        break;
      case 1:
        String adb =
            "adb shell pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS";
        dialog(
            "DNS permission",
            grant()
                ? "Android allows this app to change Private DNS."
                : "Connect to your own computer with USB debugging and run:\n\n" + adb,
            grant() ? null : "Copy ADB command",
            () ->
                getSystemService(android.content.ClipboardManager.class)
                    .setPrimaryClip(ClipData.newPlainText("ADB command", adb)));
        break;
      case 2:
        dialog(
            "VPN connection",
            "Connect the VPN that routes to your resolver. For internet during fallback, leave"
                + " Block connections without VPN off unless your device policy requires it.",
            "VPN settings",
            () -> open(new Intent(Settings.ACTION_VPN_SETTINGS)));
        break;
      case 3:
        dialog(
            "Private resolver",
            "The service verifies the TLS hostname and asks the VPN resolver for an IPv4 DNS answer"
                + " on port 853. If this fails, check the hostname, VPN address, firewall and"
                + " certificate.",
            "Edit resolver",
            () -> host.requestFocus());
        break;
      case 4:
        dialog(
            "Android Private DNS",
            "The mode is read from Android. Lookup OK means a separate uncached system DNS query"
                + " succeeded. Android does not expose the validated DoT address list through this"
                + " app's public API.",
            "Network settings",
            () -> open(new Intent(Settings.ACTION_WIRELESS_SETTINGS)));
        break;
      case 5:
        dialog(
            "Resume after restart",
            "Start enables automatic resume at boot and after app updates. Stop disables it. The"
                + " phone's own autostart permission is managed in system settings. After Force"
                + " stop, open this app and start monitoring again.",
            "Startup settings",
            this::autostartSettings);
        break;
      case 6:
        dialog(
            "Background activity",
            "In app settings, allow background activity if the phone stops monitoring. On Vivo,"
                + " also check iManager or Settings for Autostart. Vendor permissions must be"
                + " checked on the phone.",
            "App settings",
            this::appInfo);
        break;
      case 7:
        dialog(
            "Status notification",
            "Allow notifications and enable the Resolver status channel to see the current mode"
                + " outside the app.",
            "Notification settings",
            this::notificationSettings);
        break;
      default:
        throw new IllegalArgumentException();
    }
  }

  private void dialog(String title, String description, String action, Runnable run) {
    var d =
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(description)
            .setNegativeButton("Close", null);
    if (action != null) d.setPositiveButton(action, (v, w) -> run.run());
    d.show();
  }

  private void open(Intent intent) {
    try {
      startActivity(intent);
    } catch (ActivityNotFoundException | SecurityException e) {
      appInfo();
    }
  }

  private void autostartSettings() {
    if ("vivo".equalsIgnoreCase(Build.MANUFACTURER)
        || "iqoo".equalsIgnoreCase(Build.MANUFACTURER)) {
      open(new Intent("com.iqoo.secure.BGSTARTUPMANAGER").setPackage("com.vivo.permissionmanager"));
    } else appInfo();
  }

  private void notificationSettings() {
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        && !prefs.getBoolean("notificationAsked", false)) {
      prefs.edit().putBoolean("notificationAsked", true).apply();
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 3);
    } else
      open(
          new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
              .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
  }

  private void appInfo() {
    try {
      startActivity(
          new Intent(
              Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
              Uri.parse("package:" + getPackageName())));
    } catch (ActivityNotFoundException | SecurityException e) {
      Toast.makeText(
              this, "Open Android Settings → Apps → Private DNS Fallback.", Toast.LENGTH_LONG)
          .show();
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    main.removeCallbacks(update);
    update.run();
  }

  @Override
  protected void onPause() {
    main.removeCallbacks(update);
    super.onPause();
  }
}
