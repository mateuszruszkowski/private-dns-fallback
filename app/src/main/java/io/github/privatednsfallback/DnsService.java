package io.github.privatednsfallback;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.*;
import android.os.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DnsService extends Service {
  static final String CHANNEL = "resolver";
  static final String START = "start", STOP = "stop", APPLY = "apply";
  // Accessed exclusively on the main thread, alongside lifecycle and network callbacks.
  static boolean running;
  static Boolean directOk, systemOk;
  static String message = "Stopped";
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService probes = Executors.newSingleThreadExecutor();
  private final Runnable refresh = this::check;
  private static final long PROBE_BUDGET_MS = 6000;
  private final Runnable deadline = this::probeTimedOut;
  private ConnectivityManager cm;
  private SharedPreferences p;
  private Socket socket;
  private CancellationSignal query;
  private int generation, latestStart;
  private long strictSince, retryAfter;
  static long probeAt, systemAt;
  private long retryDelay = 30000;
  private boolean closing, registered;
  private final ConnectivityManager.NetworkCallback changes =
      new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network n) {
          networkChanged();
        }

        @Override
        public void onLost(Network n) {
          networkChanged();
        }
      };
  private final ConnectivityManager.NetworkCallback defaultChanges =
      new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network n) {
          networkChanged();
        }

        @Override
        public void onLost(Network n) {
          networkChanged();
        }
      };

  @Override
  public void onCreate() {
    super.onCreate();
    p = Config.prefs(this);
    Config.migrateLegacyOwnership(this);
    cm = getSystemService(ConnectivityManager.class);
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(
        new NotificationChannel(CHANNEL, "Resolver status", NotificationManager.IMPORTANCE_LOW));
    try {
      startForeground(1, notification("Checking network"));
    } catch (IllegalStateException | SecurityException e) {
      closing = true;
      backgroundLaunchBlocked(this);
      stopSelf();
      return;
    }
    cm.registerNetworkCallback(
        new NetworkRequest.Builder()
            .clearCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build(),
        changes,
        main);
    cm.registerDefaultNetworkCallback(defaultChanges, main);
    registered = true;
  }

  static void backgroundLaunchBlocked(Context context) {
    String notice = "Background launch blocked · open the app to resume";
    try {
      Config.migrateLegacyOwnership(context);
      Config.yieldForRestart(context);
    } catch (SecurityException denied) {
      notice = "Background launch blocked · check Android Private DNS";
    }
    Config.prefs(context).edit().putString("notice", notice).apply();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (closing) {
      stopSelfResult(startId);
      return START_NOT_STICKY;
    }
    latestStart = startId;
    Config.recover(this);
    cancel();
    String action = intent == null ? null : intent.getAction();
    if (STOP.equals(action)) {
      stopMonitoring("Stopped");
      return START_NOT_STICKY;
    }
    if (START.equals(action) || APPLY.equals(action)) {
      String host = intent.getStringExtra("host"), ip = intent.getStringExtra("address");
      if (host == null || ip == null) return START_NOT_STICKY;
      DnsPair expected;
      try {
        expected = Config.prepareProfile(this, host);
      } catch (SecurityException ex) {
        stopMonitoring("Check DNS permission and Android provider settings");
        return START_NOT_STICKY;
      }
      Config.observe(p.edit(), expected)
          .putString("host", host)
          .putString("address", ip)
          .remove("draft_host")
          .remove("draft_address")
          .putBoolean("enabled", true)
          .remove("notice")
          .commit();
    }
    if (!p.getBoolean("enabled", false)) {
      stopMonitoring("Stopped");
      return START_NOT_STICKY;
    }
    if (!p.getBoolean("observing", false)) {
      DnsPair restored = Config.actual(this);
      if (!Config.matches(this)
          || ("hostname".equals(restored.mode) && !p.getString("host", "").equals(restored.host))) {
        stopMonitoring("Monitoring stopped · Android DNS was changed");
        return START_NOT_STICKY;
      }
      Config.observe(p.edit(), restored).commit();
    }
    running = true;
    directOk = systemOk = null;
    strictSince = SystemClock.elapsedRealtime();
    retryAfter = 0;
    retryDelay = 30000;
    status("Checking network");
    check();
    return START_STICKY;
  }

  private void cancel() {
    generation++;
    main.removeCallbacks(deadline);
    main.removeCallbacks(refresh);
    if (socket != null) {
      try {
        socket.close();
      } catch (IOException ignored) {
      }
      socket = null;
    }
    if (query != null) {
      query.cancel();
      query = null;
    }
  }

  private void probeTimedOut() {
    if (closing || !running || socket == null) return;
    cancel();
    probeAt = SystemClock.elapsedRealtime();
    directOk = false;
    automatic("Automatic DNS · resolver timed out");
  }

  private void stopMonitoring(String reason) {
    cancel();
    running = false;
    directOk = systemOk = null;
    p.edit().putBoolean("enabled", false).commit();
    try {
      Config.release(this);
    } catch (SecurityException ex) {
      reason = "Stopped · check Android Private DNS";
    }
    message = reason;
    p.edit().putString("notice", reason).commit();
    stopSelfResult(latestStart);
  }

  private void schedule(long ms) {
    main.removeCallbacks(refresh);
    if (!closing && running) main.postDelayed(refresh, ms);
  }

  private void networkChanged() {
    if (closing || !running) return;
    cancel();
    systemOk = directOk = null;
    retryDelay = 30000;
    retryAfter = 0;
    strictSince = SystemClock.elapsedRealtime();
    schedule(0);
  }

  static Network vpn(ConnectivityManager cm) {
    Network n = cm.getActiveNetwork();
    NetworkCapabilities caps = cm.getNetworkCapabilities(n);
    return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? n : null;
  }

  private void check() {
    if (closing || !running) return;
    cancel();
    if (!Config.matches(this)) {
      stopMonitoring("Monitoring stopped · Android DNS was changed");
      return;
    }
    int token = generation;
    Network n = vpn(cm);
    if (n == null) {
      probeAt = SystemClock.elapsedRealtime();
      directOk = null;
      automatic("Automatic DNS · VPN disconnected");
      return;
    }
    String host = p.getString("host", ""), ip = p.getString("address", "");
    try {
      Socket s = n.getSocketFactory().createSocket();
      socket = s;
      long expires = SystemClock.elapsedRealtime() + PROBE_BUDGET_MS;
      main.postDelayed(deadline, PROBE_BUDGET_MS);
      probes.execute(
          () -> {
            boolean ok = false;
            try (Socket raw = s) {
              ok = DotProbe.resolves(raw, ip, host);
            } catch (IOException | RuntimeException ignored) {
            }
            boolean result = ok;
            main.post(
                () -> {
                  if (!current(token, n)) return;
                  if (SystemClock.elapsedRealtime() >= expires) {
                    probeTimedOut();
                    return;
                  }
                  main.removeCallbacks(deadline);
                  socket = null;
                  directOk = result;
                  probeAt = SystemClock.elapsedRealtime();
                  if (!result) {
                    automatic("Automatic DNS · resolver unavailable");
                    return;
                  }
                  if (SystemClock.elapsedRealtime() < retryAfter) {
                    schedule(retryAfter - SystemClock.elapsedRealtime());
                    return;
                  }
                  boolean wasStrict =
                      "hostname".equals(Config.read(this, Config.MODE))
                          && host.equals(Config.read(this, Config.SPECIFIER));
                  try {
                    Config.select(this, host);
                  } catch (SecurityException ex) {
                    denied();
                    return;
                  }
                  if (!wasStrict) {
                    strictSince = SystemClock.elapsedRealtime();
                    systemOk = null;
                    status("Checking Android DNS");
                    schedule(2000);
                  } else verifySystem(token, n);
                });
          });
    } catch (IOException | SecurityException ex) {
      probeAt = SystemClock.elapsedRealtime();
      directOk = false;
      automatic("Automatic DNS · VPN unavailable");
    }
  }

  private boolean current(int token, Network n) {
    if (!running || closing || token != generation) return false;
    if (!Objects.equals(n, vpn(cm))) {
      networkChanged();
      return false;
    }
    if (!Config.matches(this)) {
      stopMonitoring("Monitoring stopped · Android DNS was changed");
      return false;
    }
    return true;
  }

  private void verifySystem(int token, Network n) {
    CancellationSignal signal = new CancellationSignal();
    query = signal;
    Runnable timedOut =
        () -> {
          if (query == signal && current(token, n)) {
            signal.cancel();
            query = null;
            systemResult(false);
          }
        };
    main.postDelayed(timedOut, 4000);
    DnsResolver.Callback<List<InetAddress>> callback =
        new DnsResolver.Callback<>() {
          private void finish(boolean ok) {
            main.removeCallbacks(timedOut);
            if (query != signal || !current(token, n)) return;
            query = null;
            systemResult(ok);
          }

          @Override
          public void onAnswer(List<InetAddress> addrs, int rcode) {
            finish(rcode == 0 && !addrs.isEmpty());
          }

          @Override
          public void onError(DnsResolver.DnsException e) {
            finish(false);
          }
        };
    try {
      DnsResolver.getInstance()
          .query(
              null,
              "example.org",
              DnsResolver.TYPE_A,
              DnsResolver.FLAG_NO_CACHE_LOOKUP,
              getMainExecutor(),
              signal,
              callback);
    } catch (RuntimeException ex) {
      callback.onError(null);
    }
  }

  private void systemResult(boolean ok) {
    systemOk = ok;
    systemAt = SystemClock.elapsedRealtime();
    if (ok) {
      retryDelay = 30000;
      status("Strict DNS selected · Android lookup OK");
      schedule(30000);
    } else if (SystemClock.elapsedRealtime() - strictSince < 15000) {
      status("Waiting for Android DNS");
      schedule(2000);
    } else {
      retryAfter = SystemClock.elapsedRealtime() + retryDelay;
      retryDelay = Math.min(retryDelay * 2, 15 * 60000L);
      automatic("Automatic DNS · Android lookup failed");
    }
  }

  private void denied() {
    status("Permission required · open app");
    schedule(30000);
  }

  private void automatic(String msg) {
    if (!Config.matches(this)) {
      stopMonitoring("Monitoring stopped · Android DNS was changed");
      return;
    }
    try {
      Config.select(this, null);
    } catch (SecurityException ex) {
      denied();
      return;
    }
    systemOk = null;
    status(msg);
    schedule(30000);
  }

  private Notification notification(String text) {
    PendingIntent open =
        PendingIntent.getActivity(
            this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_lock_lock)
        .setContentTitle("Private DNS Fallback")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(open)
        .build();
  }

  private void status(String text) {
    if (!message.equals(text))
      getSystemService(NotificationManager.class).notify(1, notification(text));
    message = text;
  }

  @Override
  public void onDestroy() {
    closing = true;
    cancel();
    running = false;
    directOk = systemOk = null;
    if (registered) {
      cm.unregisterNetworkCallback(changes);
      cm.unregisterNetworkCallback(defaultChanges);
    }
    probes.shutdownNow();
    try {
      if (registered) Config.yieldForRestart(this);
    } catch (SecurityException ignored) {
    }
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
