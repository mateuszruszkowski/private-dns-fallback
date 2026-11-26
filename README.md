# Private DNS Fallback for Android

Use your VPN's DNS-over-TLS resolver when it answers. If the VPN disconnects or the resolver fails its health check, the app switches Android Private DNS to **Automatic**. It retries while the VPN is connected and restores the private provider after a successful check.

This is useful when a Pi-hole is reachable only through a private network such as Tailscale. Android's strict Private DNS setting persists after the tunnel drops. Mobile data can still work by IP while apps cannot resolve new hostnames.

## Requirements

- Android 11 or later and a computer with Android Platform Tools (ADB) for one-time setup.
- An existing Android VPN with a route to the resolver. The app can use an existing VPN slot and does not start the tunnel.
- A DNS-over-TLS endpoint on TCP 853 with an Android-trusted certificate for its hostname. A plain Pi-hole on port 53 needs a TLS frontend. The Android system must be able to bootstrap that hostname to the intended resolver. The health check connects to the address you enter and validates the certificate against that hostname.
- The resolver must answer the IPv4 lookup for `example.org`. Devices with a policy that locks Private DNS settings might reject changes.

## Install and configure

1. Download the signed APK from [Releases](../../releases), or build it below. Enable USB debugging on the intended device and approve your computer.
2. In the directory containing the APK, run the commands below. If several devices are attached, use `adb -s SERIAL` for each command.

   ```sh
   adb install -r private-dns-fallback.apk
   adb shell pm grant io.github.privatednsfallback android.permission.WRITE_SECURE_SETTINGS
   ```

3. Open **Private DNS Fallback**. Allow notifications to see the current state. Enter your provider hostname and VPN-reachable IP address. The addresses shown as placeholders are examples.
4. Connect the VPN, then tap **Start monitoring**. If a different strict provider is already active, select Automatic in Android's Private DNS settings before handing control to the app.
5. Check filtering with mobile data while Wi-Fi is off. Disconnect the VPN and open a previously unused hostname. Reconnect the VPN and confirm that the app shows **Private DNS active** again. Check your resolver log for a query from the phone if you need end-to-end evidence.

## Service controls and startup

Open **Overview** to see the current DNS mode and connection checks. Use **Setup** for the resolver, permissions and startup controls. Tap a row for its evidence and a relevant action. The **Monitoring service** row shows whether the service is running in the current process. **Resume after restart** shows the saved request to restart after boot or an app update. Vendor autostart controls can still affect delivery.

**Start monitoring** starts an independent foreground service. Back, Home and closing the control screen leave monitoring active. To change an endpoint while monitoring, edit the fields and tap **Apply changes**. **Stop monitoring** ends monitoring, disables resume after boot, and releases the strict setting if the app still owns it.

**Private resolver** reports a TLS/DNS probe through the VPN. **Android DNS** shows the system setting and the outcome of a separate uncached Android lookup. A successful lookup does not identify which IP Android validated for DoT. Repeated system lookup failures trigger Automatic DNS and a retry delay. Open **Background activity** and **Resume after restart** for setup guidance if your phone restricts the service.

## Remembered settings and optional local defaults

Both fields remember your edits on the device, even if you leave before tapping **Start monitoring**. Starting the service validates and saves the active configuration. Reopening the app or installing a signed update preserves it. Clearing application data or uninstalling removes saved settings.

Public builds start with empty fields and example placeholders. To prefill your own resolver on a fresh installation, create the following file in your local checkout:

`app/src/personal/res/values/resolver_defaults.xml`

```xml
<resources>
    <string name="suggested_resolver_hostname" translatable="false">dns.example.net</string>
    <string name="suggested_resolver_address" translatable="false">192.0.2.53</string>
</resources>
```

Replace both examples with your endpoint. Run `./gradlew :app:assemblePersonal`, sign the APK from `app/build/outputs/apk/personal/` with your installation's signing key and install it with `adb install -r`. The personal APK contains the values as readable resources. Keep that APK local. Git ignores the personal source directory, and the standard debug and release builds always use the neutral defaults. Saved device settings take precedence over suggestions.

## What the health check does

| Event | Action |
| --- | --- |
| VPN available | Connect through the VPN to the configured IP. Verify the TLS certificate and hostname and send a length-prefixed DNS query. |
| Valid DNS answer | Select the configured strict Private DNS provider in Android. |
| VPN lost, TLS error, DNS timeout or unusable answer | Select Automatic DNS. Normal resolution is subject to the current network's own DNS availability. |
| VPN recovers | Restore the configured hostname after a successful probe. |

The foreground service responds to Android network callbacks and rechecks approximately every 30 seconds while running. Socket connect and read timeouts are three seconds each. Background scheduling, a sleeping device and Android's own provider validation can delay transitions. During a failure, a lookup may time out before fallback completes. Automatic mode allows network-provider DNS, so filtering and encrypted DNS are not guaranteed during fallback. Browsers or apps with their own DNS can also bypass Android's setting.

## Permissions and recovery

**WRITE_SECURE_SETTINGS** is a broad Android development permission. The app uses it for two global settings, `private_dns_mode` and `private_dns_specifier`. The resolver configuration stays in the app's device-protected storage so monitoring can resume at boot. The app sends its health query to the chosen resolver and has no analytics or external control service.

In **Setup**, tap **Stop monitoring** before uninstalling. Force-stop, process termination, revoking the grant and uninstalling can prevent cleanup while strict DNS is active. If that happens, select **Automatic** in Android's Private DNS settings, or run:

```sh
adb shell settings put global private_dns_mode opportunistic
adb shell pm revoke io.github.privatednsfallback android.permission.WRITE_SECURE_SETTINGS
adb uninstall io.github.privatednsfallback
```

If ordinary traffic must work outside the VPN, Android's **Block connections without VPN** setting must be off. Managed devices can enforce that policy. On devices that restrict background activity, allow the foreground service to run in the background.

## Build and test

Use JDK 17 and Android SDK Platform 36. Set `ANDROID_HOME` to your SDK directory.

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds use your local debug signing key. Android requires matching signatures for in-place updates, so use the same installation source for subsequent versions.

[Apache License 2.0](LICENSE)
