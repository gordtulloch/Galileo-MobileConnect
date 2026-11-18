# HOWTO: get the Android client running with Galileo

A quick path from nothing to the Android app operating a Galileo observatory. For handing the app to other testers (signing, release builds) see [TESTING.md](TESTING.md).

You need: a phone or tablet on **Android 8.0 or later**, on the **same network** as the computer running Galileo (the Master). There is no cloud relay.

## 1. Try the demo (optional)

After installing the app (step 2), choose **demo mode** on the connect screen. It runs against a built-in simulated observatory, so you can look around without Galileo.

## 2. Get the app onto the device

**Download the signed APK** from the Galileo website's Downloads page (Mobile app, MobileConnect for Android). It links to the latest release on GitHub. Open the link on the device, tap the APK and allow "Install unknown apps" for that browser or file manager when asked. An update installs over the old copy and keeps its settings. If Android reports a conflicting package, the old copy was signed with a different key, so uninstall it first.

## 3. Set up the Master (the Galileo computer)

1. Use a **current Galileo**. The apps rely on its Connect Link and Control Surface, and newer screens need newer Galileo. If a screen says *This Master doesn't offer this yet*, Galileo or the plugin is out of date.
2. Install the **MobileConnect plugin** from the Marketplace: in Galileo, open **Options > Plugins**, find **mobileconnect** and install it. Keep it up to date, since the app and plugin need to be a matching release.
3. In **Options > Equipment**, enable **Hold devices** in the device service.
4. **Restart Galileo.** After any plugin update you must update the plugin and restart, or new app features show nothing.
5. Make sure **Connect Link** is enabled, and that the desktop application is running (it owns the schedule and queue).
6. Check the computer's firewall allows the Connect Link port from your LAN.

## 4. Pair the app

1. On the device, open the app. Under local discovery it should list your Master; if not (or if it denies the Wi-Fi/nearby permission), choose **Enter address** and type the Master's IP and port. The app only speaks HTTPS and pins the Master's certificate, so plain `http://` addresses will not work.
2. Request to pair. A short **confirmation code** appears on both the device and the Master. Check that they match.
3. On the Master, open **Options > MobileConnect** and **approve** the request. Give the app a role: **Operator** to drive equipment, **Viewer** to watch only.
4. The app signs in with its own token from then on. Revoke it any time from the same options page.

## 5. Check it works

- **Overview** shows your Piers and node health.
- Try something harmless as an Operator: **Equipment > Connect**, or **Overview > Connect All Devices**.
- Only one controller can drive a Pier at a time (the *control lease* is shared with the desktop and browser). If a control is greyed out, the desktop may hold the lease.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Master is not listed | Different Wi-Fi/VLAN, multicast filtered by the router, or the nearby-devices permission was denied. Use **Enter address** |
| Pairing request never arrives | Connect Link not enabled, firewall, or the plugin not loaded. Check the plugin appears in **Options > Plugins** and Galileo was restarted |
| Pairing code is not offered for approval | Open **Options > MobileConnect** on the Master itself |
| A feature does nothing, or *This Master doesn't offer this yet* | Stale installed plugin or old Galileo. Update the plugin from the Marketplace, update Galileo, restart |
| Devices won't connect or drop out | **Hold devices** is not enabled in the device service (Options > Equipment) |
| Connected but controls are disabled | App role is Viewer, or another controller holds the lease |
| Sessions or queue show an error | The desktop application must be running; it owns the queue |
| Install fails with a package conflict | Existing copy signed with a different key: uninstall it first |

## Status

The Android app builds and runs in demo mode on emulators and has been run against a real Master during development, but it has not been used with real equipment. See the [README](../../README.md#status).
