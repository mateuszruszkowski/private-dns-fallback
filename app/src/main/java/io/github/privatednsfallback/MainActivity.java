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
  private int INK = 0xff162d2c,
      SECONDARY = 0xff435956,
      GOOD = 0xff086950,
      WARN = 0xff895416,
      DANGER = 0xffab3035;
  private LinearLayout page, overview, setup, hero;
  private TextView provider, serviceControl;
  private Button statusTab, setupTab;
  private ScrollView scroll;
  private boolean configuring;
  private int background, surface, track, stroke;
  private RouteDiagram route;
  private final LinearLayout[] lines = new LinearLayout[8];
  private final TextView[] names = new TextView[8];
  private final View[] lights = new View[8];
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
    boolean night =
        (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    background = night ? 0xff101b1b : 0xfff3f6f4;
    surface = night ? 0xff1a2a29 : Color.WHITE;
    track = night ? 0xff263936 : 0xffe1e9e5;
    stroke = night ? 0xff344844 : 0xffd6dfda;
    INK = night ? 0xffeff7f4 : 0xff132c28;
    SECONDARY = night ? 0xffb8c9c3 : 0xff466059;
    GOOD = night ? 0xff81dabc : 0xff11634c;
    WARN = night ? 0xffffce87 : 0xff8a5316;
    DANGER = night ? 0xffffa7a1 : 0xffa32b26;
    LinearLayout root = column();
    root.setBackgroundColor(background);
    root.setFocusableInTouchMode(true);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          var bars =
              insets.getInsets(
                  WindowInsets.Type.systemBars()
                      | WindowInsets.Type.displayCutout()
                      | WindowInsets.Type.ime());
          v.setPadding(dp(20) + bars.left, bars.top, dp(20) + bars.right, bars.bottom);
          return insets;
        });
    setContentView(root);
    LinearLayout toolbar = new LinearLayout(this);
    toolbar.setGravity(Gravity.CENTER_VERTICAL);
    toolbar.setPadding(0, dp(9), 0, dp(7));
    root.addView(toolbar);
    ImageView icon = new ImageView(this);
    icon.setImageResource(R.drawable.ic_dns);
    icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    toolbar.addView(icon, dp(23), dp(23));
    TextView name = text("Private DNS Fallback", 18, true);
    name.setPadding(dp(10), 0, 0, 0);
    toolbar.addView(name);
    toolbar.setMinimumHeight(dp(54));
    LinearLayout tabs = new LinearLayout(this);
    tabs.setPadding(dp(3), dp(3), dp(3), dp(3));
    tabs.setBackground(shape(track, 14));
    root.addView(tabs);
    statusTab = tab(tabs, "Overview", false);
    setupTab = tab(tabs, "Setup", true);
    scroll = new ScrollView(this);
    scroll.setVerticalScrollBarEnabled(false);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout body = column();
    body.setPadding(0, dp(15), 0, dp(20));
    scroll.addView(body);
    overview = column();
    setup = column();
    body.addView(overview);
    body.addView(setup);
    hero = column();
    hero.setPadding(dp(18), dp(16), dp(18), dp(12));
    hero.setBackground(shape(0xff173e35, 20));
    overview.addView(hero);
    TextView caption = text("DNS connection", 13, true);
    caption.setTextColor(0xffb6d7c9);
    hero.addView(caption);
    summary = text("", 28, true);
    summary.setTextColor(Color.WHITE);
    summary.setPadding(0, dp(4), 0, dp(4));
    summary.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    hero.addView(summary);
    provider = text("", 15, false);
    provider.setTextColor(0xffd4ebe1);
    hero.addView(provider);
    route = new RouteDiagram(this);
    LinearLayout.LayoutParams drawing =
        new LinearLayout.LayoutParams(
            -1, dp(41) + (int) (16 * getResources().getDisplayMetrics().scaledDensity));
    drawing.topMargin = dp(12);
    hero.addView(route, drawing);
    section(overview, "Connection checks");
    LinearLayout networkRows = group(overview);
    for (int i : new int[] {0, 2, 3, 4}) buildRow(networkRows, i);
    serviceControl = text("", 15, true);
    serviceControl.setGravity(Gravity.CENTER);
    serviceControl.setMinHeight(dp(48));
    serviceControl.setBackground(clickable(surface, 12));
    serviceControl.setFocusable(true);
    serviceControl.setOnClickListener(
        v -> {
          if (DnsService.running) selectTab(true);
          else if (grant() && !prefs.getString("host", "").isEmpty()) save();
          else selectTab(true);
        });
    LinearLayout.LayoutParams controlLp = new LinearLayout.LayoutParams(-1, -2);
    controlLp.topMargin = dp(12);
    overview.addView(serviceControl, controlLp);
    section(setup, "Resolver");
    page = group(setup);
    page.setPadding(dp(14), dp(14), dp(14), dp(14));
    host = input("TLS hostname", "dns.example.net", "host", R.string.suggested_resolver_hostname);
    address =
        input("Address inside VPN", "192.0.2.53", "address", R.string.suggested_resolver_address);
    start = new Button(this);
    start.setAllCaps(false);
    start.setTextSize(15);
    start.setMinHeight(dp(48));
    start.setTextColor(Color.WHITE);
    start.setBackground(clickable(0xff17694e, 12));
    start.setOnClickListener(v -> save());
    page.addView(start);
    section(setup, "Permissions & startup");
    LinearLayout deviceRows = group(setup);
    for (int i : new int[] {1, 5, 6, 7}) buildRow(deviceRows, i);
    stop = new Button(this);
    stop.setAllCaps(false);
    stop.setText("Stop monitoring");
    stop.setTextColor(DANGER);
    stop.setBackground(clickable(surface, 12));
    stop.setMinHeight(dp(48));
    stop.setOnClickListener(v -> send(DnsService.STOP, null, null));
    LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(-1, -2);
    stopLp.topMargin = dp(12);
    setup.addView(stop, stopLp);
    root.requestFocus();
    selectTab(saved != null ? saved.getBoolean("setup") : prefs.getString("host", "").isEmpty());
    render();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putBoolean("setup", configuring);
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private GradientDrawable shape(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  private android.graphics.drawable.Drawable clickable(int color, int radius) {
    return new android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(0x2279a38d),
        shape(color, radius),
        shape(Color.WHITE, radius));
  }

  private Button tab(LinearLayout tabs, String label, boolean edit) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(14);
    b.setMinHeight(dp(48));
    b.setMinimumWidth(0);
    b.setStateListAnimator(null);
    b.setOnClickListener(v -> selectTab(edit));
    tabs.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
    return b;
  }

  private void selectTab(boolean edit) {
    configuring = edit;
    overview.setVisibility(edit ? View.GONE : View.VISIBLE);
    setup.setVisibility(edit ? View.VISIBLE : View.GONE);
    for (Button b : new Button[] {statusTab, setupTab}) {
      boolean active = b == (edit ? setupTab : statusTab);
      b.setSelected(active);
      b.setTextColor(active ? INK : SECONDARY);
      b.setTypeface(Typeface.create(active ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
      b.setBackground(clickable(active ? surface : track, 11));
    }
    if (!edit) {
      getSystemService(android.view.inputmethod.InputMethodManager.class)
          .hideSoftInputFromWindow(scroll.getWindowToken(), 0);
      host.clearFocus();
      address.clearFocus();
    }
    scroll.scrollTo(0, 0);
  }

  private void section(LinearLayout parent, String title) {
    TextView v = text(title, 18, true);
    v.setPadding(dp(2), dp(16), 0, dp(8));
    parent.addView(v);
  }

  private LinearLayout group(LinearLayout parent) {
    LinearLayout l = column();
    l.setBackground(shape(surface, 16));
    parent.addView(l);
    return l;
  }

  private void buildRow(LinearLayout group, int i) {
    if (group.getChildCount() > 0) {
      View divider = new View(this);
      divider.setBackgroundColor(stroke);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
      lp.leftMargin = dp(32);
      group.addView(divider, lp);
    }
    LinearLayout l = new LinearLayout(this);
    l.setGravity(Gravity.CENTER_VERTICAL);
    l.setPadding(dp(14), dp(8), dp(12), dp(8));
    l.setMinimumHeight(dp(50));
    l.setFocusable(true);
    l.setBackground(clickable(Color.TRANSPARENT, 12));
    l.setOnClickListener(v -> details(i));
    group.addView(l);
    View light = new View(this);
    l.addView(light, dp(8), dp(8));
    lights[i] = light;
    LinearLayout words = column();
    words.setPadding(dp(10), 0, dp(6), 0);
    l.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    TextView title = text("", 15, true);
    words.addView(title);
    names[i] = title;
    TextView info = text("", 13, false);
    info.setTextColor(SECONDARY);
    words.addView(info);
    checks[i] = info;
    lines[i] = l;
    TextView arrow = text("›", 20, false);
    arrow.setTextColor(SECONDARY);
    l.addView(arrow);
    for (View child : new View[] {light, words, title, info, arrow})
      child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
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
    edit.setTextColor(INK);
    edit.setHintTextColor(SECONDARY);
    edit.setTextSize(16);
    edit.setMinHeight(dp(48));
    var inputBox = shape(background, 9);
    inputBox.setStroke(dp(1), stroke);
    edit.setBackground(inputBox);
    edit.setPadding(dp(12), dp(8), dp(12), dp(8));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp(3);
    lp.bottomMargin = dp(12);
    page.addView(edit, lp);
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
      selectTab(true);
      host.requestFocus();
      host.setError("Enter a fully qualified TLS hostname");
      return;
    }
    String ip = address.getText().toString().trim();
    if (!InetAddresses.isNumericAddress(ip)) {
      selectTab(true);
      address.requestFocus();
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
    if (!title.contentEquals(names[i].getText())) names[i].setText(title);
    if (!value.contentEquals(checks[i].getText())) {
      checks[i].setText(value);
      lines[i].setContentDescription(title + ", " + value + ". Show details");
    }
    if (!Integer.valueOf(color).equals(lights[i].getTag())) {
      lights[i].setBackground(shape(color, 8));
      lights[i].setTag(color);
    }
  }

  private void render() {
    boolean live = DnsService.running, desired = prefs.getBoolean("enabled", false);
    boolean strict = "hostname".equals(Config.read(this, Config.MODE));
    boolean owned = Config.ownsStrict(this);
    boolean stranded = !live && owned;
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
            : (probe ? "TLS verified" : "Unreachable") + " · " + age(probeAge),
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
                ? " · lookup OK · " + age(systemAge)
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
    boolean healthy =
        live
            && owned
            && validated
            && Boolean.TRUE.equals(sys)
            && Boolean.TRUE.equals(probe)
            && systemAge < 60
            && probeAge < 60;
    String headline =
        stranded
            ? "Action needed"
            : !live
                ? "Monitoring stopped"
                : healthy
                    ? "Private DNS active"
                    : !grant() ? "Permission needed" : !strict ? "Automatic DNS" : "Checking DNS";
    if (!headline.contentEquals(summary.getText())) summary.setText(headline);
    String sub =
        stranded
            ? "Open Setup to restore Automatic DNS"
            : healthy
                ? prefs.getString("host", "")
                : !live
                    ? (desired ? "Resume monitoring in Setup" : "Start monitoring in Setup")
                    : !strict
                        ? (vpn
                            ? "Network resolver · VPN connected"
                            : "Network resolver · VPN disconnected")
                        : DnsService.message;
    if (!sub.contentEquals(provider.getText())) provider.setText(sub);
    route.route(vpn, healthy);
    serviceControl.setText(live ? "Manage monitoring   ›" : "Start monitoring   ›");
    boolean changed =
        !host.getText().toString().trim().equals(prefs.getString("host", ""))
            || !address.getText().toString().trim().equals(prefs.getString("address", ""));
    boolean pending = !command.isEmpty() && SystemClock.elapsedRealtime() - commandTime < 900;
    start.setText(live ? "Apply changes" : "Start monitoring");
    start.setEnabled(!pending && (!live || changed));
    start.setText(
        live && !changed ? "Settings saved" : live ? "Apply changes" : "Start monitoring");
    start.setAlpha(start.isEnabled() ? 1f : .65f);
    stop.setVisibility(live || desired || stranded ? View.VISIBLE : View.GONE);
    stop.setEnabled(!pending);
  }

  private String age(long s) {
    return s < 5
        ? "just now"
        : s < 15
            ? "<15 s ago"
            : s < 30 ? "<30 s ago" : s < 60 ? "<1 min ago" : (s / 60) + " min ago";
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
            () -> {
              selectTab(true);
              host.requestFocus();
            });
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
