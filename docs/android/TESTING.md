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

Releases are made on this computer by `scripts/release_android.py`; the signing key is never put on GitHub.

1. Raise `versionCode` (it must go up by at least one, or Android refuses the install as a downgrade) and `versionName` in
   `android/app/build.gradle.kts`.
2. Update `docs/android/release-notes.md` for this version (it becomes the GitHub release description).
3. Commit and push.
4. `python scripts/release_android.py` runs the unit tests, builds the signed APK into `dist/`, and checks it is signed
   with the release key (it refuses a debug-signed build). Nothing is published.
5. `python scripts/release_android.py --publish` then tags `v<version>`, creates the GitHub pre-release with the APK, and
   lists it on the web site's download page (`apps` in Galileo.web's `downloads.json`, committed and pushed). It needs
   `gh` logged in and a clone of Galileo.web next to this repository (or `--web PATH`), and it refuses to run with
   uncommitted or unpushed changes, a missing note for this version, or an existing release of the same version.

Say in the release notes which Galileo version and MobileConnect plug-in version the app needs, and upload the plug-in's
zip with the plug-in's own release (Galileo-Plugins builds it when a plug-in's version is pushed).

## What to tell testers

- Open the download link on the phone or tablet (Android 8.0 or later) and tap the APK. Android asks once to allow
  "Install unknown apps" for the browser or file manager used; allow it.
- An update installs over the old one and keeps its settings. If Android says the app conflicts with an existing package,
  the old copy was signed with a different key (a debug build): uninstall it first.
- **No Galileo to hand?** Choose demo mode on the connect screen; it runs against a built-in simulated observatory.
- **With Galileo:** install the MobileConnect plugin from its zip through Options > Plugins, restart Galileo, then pair
  from the app. The Master and the app must be a matching release.
