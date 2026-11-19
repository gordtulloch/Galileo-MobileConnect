# Giving the iOS app to testers

iOS apps reach outside testers only through **TestFlight** (Apple's beta service). Testers install Apple's free TestFlight
app, tap an invitation link, and get Galileo and every later build from it. It needs an Apple Developer Program
membership (the build is signed under the team in `ios/project.yml`) and a Mac with Xcode.

## One-time setup

1. **Sign in to Xcode** (Settings > Accounts) with the Apple ID that holds the membership. The script signs automatically
   with that account; it creates the distribution certificate and App Store profile itself.
2. **Create the app record** in [App Store Connect](https://appstoreconnect.apple.com) > Apps > New App: platform iOS,
   bundle ID `com.gordtulloch.galileo.mobileconnect` (it appears in the list once Xcode has registered it; archive once
   from Xcode if it does not), a SKU of your choice, and the **app name**. The name must be unique across the App Store,
   so *Galileo* alone is probably taken; the on-device name (`CFBundleDisplayName`) can stay short. The name is
   decided here (PSD Q5), but can be changed before the first public release.
3. In the app record, under **App Privacy**, answer **Data Not Collected**, and give a **privacy policy URL** (required
   for external testers).
4. Under **TestFlight > Test Information**, add a beta description, a feedback email, and **demo-mode instructions** for
   Apple's reviewers: no Galileo or hardware is needed, choose *demo mode* on the connect screen.

`Info.plist` already declares the local-network permission and the `_galileo-link._tcp` service, and
`ITSAppUsesNonExemptEncryption` is `false`, so uploads are not held for export-compliance questions.

## Each build

1. Raise `CURRENT_PROJECT_VERSION` in `ios/project.yml` (must increase on **every** upload, even for the same
   `MARKETING_VERSION`) and `MARKETING_VERSION` for a new release. Run `xcodegen` in `ios/`, then commit and push both
   files.
2. `python scripts/release_ios.py` runs the `GalileoCore` tests, archives, and exports a signed build into
   `dist/ios-<version>-<build>/`. Nothing is uploaded. It stops if `project.yml` and the Xcode project disagree.
3. `python scripts/release_ios.py --upload` does the same and sends the build to App Store Connect.
4. Wait for processing (typically 5 to 30 minutes, then an email). In **TestFlight**, fill in **What to Test** for the
   build: which Galileo and MobileConnect plug-in versions it needs, and what changed.

## Getting testers in

- **Internal testers** (up to 100): add people as App Store Connect users, then add them to an internal group. No review;
  builds are available as soon as they finish processing.
- **External testers** (up to 10,000): create an external group and add the build. The first build of each version goes
  through **Beta App Review** (usually about a day). Then either invite by email or switch on the group's **public
  link**, which anyone can open to join; put that link on the website's Downloads page.
- Builds **expire after 90 days**, so upload a fresh one before then.

## What to tell testers

- Install **TestFlight** from the App Store, then open the invitation link on the iPhone or iPad (iOS 17 or later) and tap
  *Install*. Updates arrive through TestFlight.
- **No Galileo to hand?** Choose demo mode on the connect screen; it runs against a built-in simulated observatory.
- **With Galileo:** the app asks to find devices on the local network; **allow it** (it is how the Master is discovered; if
  it was refused, switch it on in Settings > Galileo > Local Network, or use *Enter address*). Install the MobileConnect
  plug-in through Options > Plugins, restart Galileo, then pair from the app. The Master and the app must be a matching
  release.
- Feedback and crash reports can be sent from TestFlight itself (screenshot, then *Send Beta Feedback*).
