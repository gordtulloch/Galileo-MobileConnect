# Giving the Android app to testers

The app is handed out as a signed APK (a GitHub Release, or any download link). There is no store and no account involved;
testers install it by hand and install each new build over the last.

## One-time: make the release key

The release key signs every build you hand out. **Back it up** (a password manager and one other copy): Android refuses to
update an app signed with a different key, so if you lose it every tester must uninstall and lose their settings. **Never
commit it**: `*.jks`, `*.keystore` and `android/keystore.properties` are gitignored.

In PowerShell (the `&` is needed to run a quoted path; the folder must exist first):

```powershell
New-Item -ItemType Directory -Force C:\Users\<you>\keys
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore C:\Users\<you>\keys\galileo-release.jks -alias galileo -keyalg RSA -keysize 2048 -validity 10000
```

It asks for a keystore password, some name details (Enter skips them; answer `yes` to the final check) and a key password
(Enter reuses the keystore password). Keep the key outside the repository. Then create `android/keystore.properties` (gitignored):

```
storeFile=C:/Users/<you>/keys/galileo-release.jks
storePassword=<the keystore password>
keyAlias=galileo
keyPassword=<the key password>
```

`storeFile` may be absolute, or relative to `android/`. Without this file the release build falls back to the debug key,
which is fine for your own devices but not for testers.

## Each release

1. Raise `versionCode` (it must go up by at least one, or Android refuses the install as a downgrade) and `versionName` in
   `android/app/build.gradle.kts`.
2. Build: from `android/`, `gradlew :app:assembleRelease`. The APK is
   `android/app/build/outputs/apk/release/app-release.apk`.
3. Check it is signed with the release key, not the debug one:
   `apksigner verify --print-certs app-release.apk` (in the SDK's `build-tools`); the certificate must not say
   "Android Debug".
4. Give it a clear name, e.g. `Galileo-MobileConnect-0.1.1.apk`, and upload it (with the matching MobileConnect plugin zip)
   to a GitHub Release. Say which Galileo version it needs.

## What to tell testers

- Open the download link on the phone or tablet (Android 8.0 or later) and tap the APK. Android asks once to allow
  "Install unknown apps" for the browser or file manager used; allow it.
- An update installs over the old one and keeps its settings. If Android says the app conflicts with an existing package,
  the old copy was signed with a different key (a debug build): uninstall it first.
- **No Galileo to hand?** Choose demo mode on the connect screen; it runs against a built-in simulated observatory.
- **With Galileo:** install the MobileConnect plugin from its zip through Options > Plugins, restart Galileo, then pair
  from the app. The Master and the app must be a matching release.
