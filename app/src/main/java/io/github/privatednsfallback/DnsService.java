package io.github.privatednsfallback;

import android.app.*;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.*;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import java.io.IOException;
import java.net.Socket;

public final class DnsService extends Service {
    private static final String CHANNEL = "resolver";
    private static final int NOTIFICATION = 1;
    private HandlerThread thread;
    private Handler worker;
    private ConnectivityManager cm;
    private String lastStatus = "";
    private volatile boolean closing;
    private final Runnable refresh = this::check;

    private final ConnectivityManager.NetworkCallback changes = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { schedule(0); }
        @Override public void onLost(Network n) { schedule(0); }
        @Override public void onLinkPropertiesChanged(Network n, LinkProperties lp) { schedule(200); }
    };
    private final ConnectivityManager.NetworkCallback defaultChanges = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { schedule(0); }
        @Override public void onLost(Network n) { schedule(0); }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Resolver status", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION, notification("Checking network"));
        thread = new HandlerThread("DNS monitor");
        thread.start();
        worker = new Handler(thread.getLooper());
        cm = getSystemService(ConnectivityManager.class);
        NetworkRequest vpn = new NetworkRequest.Builder().clearCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN).build();
        cm.registerNetworkCallback(vpn, changes, worker);
        cm.registerDefaultNetworkCallback(defaultChanges, worker);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Config.prefs(this).getBoolean("enabled", false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        schedule(0);
        return START_STICKY;
    }

    private void schedule(long delay) {
        if (!closing) {
            worker.removeCallbacks(refresh);
            worker.postDelayed(refresh, delay);
        }
    }

    private Network vpn() {
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return n;
        }
        return null;
    }

    private void check() {
        SharedPreferences p = Config.prefs(this);
        if (closing || !p.getBoolean("enabled", false)) return;
        String host = p.getString("host", "");
        Network n = vpn();
        boolean healthy = false;
        if (n != null) {
            try (Socket socket = n.getSocketFactory().createSocket()) {
                healthy = DotProbe.resolves(socket, p.getString("address", ""), host);
            } catch (IOException | IllegalArgumentException ignored) {
                // DNS timeout, invalid certificate, or disappearing VPN all require fallback.
            }
        }
        if (closing || !p.getBoolean("enabled", false)) return;
        try {
            String selected = Settings.Global.getString(getContentResolver(), Config.SPECIFIER);
            if ("hostname".equals(Settings.Global.getString(getContentResolver(), Config.MODE))
                    && !host.equals(selected)) {
                p.edit().putBoolean("enabled", false).apply();
                stopSelf();
                return;
            }
            if (healthy) {
                if (!host.equals(selected) && !Settings.Global.putString(getContentResolver(), Config.SPECIFIER, host)) {
                    throw new SecurityException("Private DNS hostname write rejected");
                }
                Config.setMode(this, "hostname");
            } else {
                Config.setMode(this, "opportunistic");
            }
            status(healthy ? "Private DNS active" : n == null ? "Automatic DNS · VPN disconnected" : "Automatic DNS · resolver unavailable");
        } catch (SecurityException e) {
            status("Permission required · open application");
        }
        schedule(30000);
    }

    private Notification notification(String message) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("Private DNS Fallback").setContentText(message)
                .setOngoing(true).setContentIntent(pending).build();
    }

    private void status(String message) {
        if (!message.equals(lastStatus)) {
            lastStatus = message;
            Config.prefs(this).edit().putString("status", message).apply();
            getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(message));
        }
    }

    @Override public void onDestroy() {
        closing = true;
        cm.unregisterNetworkCallback(changes);
        cm.unregisterNetworkCallback(defaultChanges);
        worker.removeCallbacksAndMessages(null);
        thread.quitSafely();
        try { Config.release(this); } catch (SecurityException ignored) {}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
