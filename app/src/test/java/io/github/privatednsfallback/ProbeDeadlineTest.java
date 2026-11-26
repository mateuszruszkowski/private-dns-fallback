package io.github.privatednsfallback;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.*;
import android.os.Looper;
import android.provider.Settings;
import java.net.Socket;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.SocketFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class ProbeDeadlineTest {
  @Test
  public void unfinishedProbeCannotKeepStrictBeyondWholeAttemptDeadline() throws Exception {
    Context c = RuntimeEnvironment.getApplication();
    Settings.Global.putString(c.getContentResolver(), Config.MODE, "opportunistic");
    Config.prepareProfile(c, "dns.example");
    Config.prefs(c)
        .edit()
        .putBoolean("enabled", true)
        .putString("host", "dns.example")
        .putString("address", "192.0.2.53")
        .commit();
    Config.select(c, "dns.example");
    ConnectivityManager fake = mock(ConnectivityManager.class);
    Network vpn = mock(Network.class);
    NetworkCapabilities caps = mock(NetworkCapabilities.class);
    when(fake.getActiveNetwork()).thenReturn(vpn);
    when(fake.getNetworkCapabilities(vpn)).thenReturn(caps);
    when(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).thenReturn(true);
    SocketFactory factory = mock(SocketFactory.class);
    Socket stalled = mock(Socket.class);
    when(vpn.getSocketFactory()).thenReturn(factory);
    when(factory.createSocket()).thenReturn(stalled);
    CountDownLatch entered = new CountDownLatch(1), closed = new CountDownLatch(1);
    doAnswer(
            call -> {
              entered.countDown();
              closed.await(20, TimeUnit.SECONDS);
              throw new java.io.IOException("Closed");
            })
        .when(stalled)
        .connect(any(SocketAddress.class), anyInt());
    doAnswer(
            call -> {
              closed.countDown();
              return null;
            })
        .when(stalled)
        .close();
    var service = Robolectric.buildService(DnsService.class).create();
    var original = ReflectionHelpers.getField(service.get(), "cm");
    try {
      ReflectionHelpers.setField(service.get(), "cm", fake);
      service.get().onStartCommand(null, 0, 1);
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7));
      assertEquals("opportunistic", Config.read(c, Config.MODE));
      assertEquals(0, closed.getCount());
    } finally {
      closed.countDown();
      ReflectionHelpers.setField(service.get(), "cm", original);
      service.destroy();
    }
  }
}
