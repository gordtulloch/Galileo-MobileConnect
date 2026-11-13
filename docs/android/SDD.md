# Galileo MobileConnect — Android SDD

| | |
|---|---|
| **Project** | Galileo MobileConnect — Android app (and the base of the Meta Quest edition) |
| **Document** | SDD (Software Design Description), v0.1 — **Android platform design** |
| **Status** | Draft — for review. Only `:api-client` and most of `:core` (link, pairing, stream, live state, control, session, vector runner) are built |
| **Date** | 2026-11-12 |
| **Upstream documents** | [PSD](../PSD.md), [SRS](../SRS.md), [Shared SDD](../SDD.md) |
| **Sibling documents** | [iOS SDD](../ios/SDD.md); [Quest SDD](../quest/SDD.md) (draft) |
| **Downstream document** | [RTM](../RTM.md) |

---

## 1. Introduction

### 1.1 Purpose and scope

How the Android app puts a native Jetpack Compose interface on the core specified in the [Shared SDD](../SDD.md). Components are named `A-*`. This document does not repeat that design: wherever the app needs transport, state, control safety, session logic or a mock Master, it implements the `S-*` component in Kotlin (module `:core`, on top of the generated `:api-client`) and says so. Where the Android app does the same as the iOS app in different words, this document gives the Android mechanism and refers to the [iOS SDD](../ios/SDD.md) for the behaviour, rather than describing the behaviour a second time. If the two disagree about *behaviour*, the shared SDD and SRS decide, and one of the two platform documents has a defect.

### 1.2 Status

**The app is not built.** What exists in `android/` is the Gradle 8.7 wrapper, a version catalog, the `:core` module (the first slice of `S-LINK`, Section 6 step 2) and the `:api-client` module, which generates a Kotlin client from `api/openapi.yaml` (OkHttp, kotlinx.serialization) and, since 2026-11-12, compiles on Windows with JDK 17 (Section 6, step 1). There is no `:app`. The iOS app is far ahead (its SDD §1.2 lists what it covers), and **Android follows it**: the screens, wording and behaviour the iOS app has settled are the target, and what the iOS work learned about the Master (Shared SDD §4.2) is not rediscovered here. Choices that depend on a measurement or on the first build are marked **proposed** and listed in Section 5.

### 1.3 Relationship to the Quest edition

The Quest edition is built from this app (PSD Q-1): it reuses `:core` and the panel screens and adds its own module and SDD. That fixes three things here. `:core` must stay free of Android framework imports (Section 2.2). The screens must be separable from the `Activity` that hosts them (Section 3.1), because a Quest panel hosts them in a different shell. And nothing in `:app` may depend on Google Play Services (Section 2.6), since Quest has none.

---

## 2. Design Considerations

### 2.1 One adaptive app on Android tablets and phones (ADR-AND-001)

**Decision:** a single app for tablets and phones. The layout is built for a 10"–13" tablet in landscape first (PSD G4) with Compose's window size classes (`currentWindowAdaptiveInfo()` / `WindowSizeClass`): the Expanded and Medium classes get the multi-column content, the Compact class gets one column reached from a strip of areas across the top (`A-SHELL`). Layout follows the **window** size class, not the device type, so split-screen, freeform windows, foldables and rotation need no extra code. Which panels the phone drops is PSD Q1, decided from wireframes, and is the same decision as on iOS.

**Rationale:** one codebase and one set of screens is what parity (PSD G3) needs, and the iOS app already took the same approach (iOS ADR-IOS-001).

### 2.2 Kotlin core in a pure-JVM module; Compose over it (ADR-AND-002)

**Decision:** the hand-written `S-*` components live in `:core`, a **pure Kotlin/JVM module with no Android dependencies** (as the Shared SDD §2.1 layout already says). It exposes `StateFlow` and `suspend` functions. `A-BRIDGE` turns that into Compose state through one `ViewModel` and `collectAsStateWithLifecycle()`. Composables never touch the generated types directly. Hand-written code is Kotlin 2.x with coroutines and kotlinx.serialization (already in `:api-client`); no Hilt, Room, or other framework is adopted until a need is shown.

**Rationale:** `:core` runs under plain `gradle test` on Windows, macOS or Linux with no emulator, replays the `S-TEST` vectors in CI, and is the part Quest reuses unchanged. The seam between `:core` and `:app` is the same one iOS has between `GalileoCore` and the app.

**Minimum Android:** Android 10 (API 29) (`MAPP-PLT-010`, confirmed against the device base per SRS §5 item 3). **`compileSdk`/`targetSdk`** track the level Google Play requires at the time of each release (proposed: 35 now); `minSdk` 29 is not raised by that. The Quest spike used `minSdk 30` for an image-decoder call that `:app` does not need, and `targetSdk 32` because it is side-loaded; neither carries over.

### 2.3 Network stack: OkHttp, one client, one trust policy (ADR-AND-003)

**Decision:** the generated client uses `jvm-okhttp4` (Shared SDD ADR-APP-001), so the platform stack is **OkHttp**, and the event stream uses OkHttp's `WebSocket` on a client derived from the same builder. One `OkHttpClient` is configured with:

- an `X509TrustManager` and `HostnameVerifier` that implement `PinPolicy` — accept a leaf certificate only if its SHA-256 fingerprint equals the stored pin, or, **during the pairing exchange only**, accept the presented certificate and compare it with the fingerprint in the response body (Shared SDD §3.1). The standard CA store is not consulted for the Master, and there is no "trust all" fall-back; hostname verification is replaced by the fingerprint check, because the Master is addressed by LAN IP and its certificate is self-signed;
- an interceptor that adds `Authorization: Bearer <token>` and `User-Agent: GalileoMobileConnect/<version> (android)` (`MAPP-CTL-080`);
- a logging hook that redacts `Authorization` (ADR-APP-007). Request and response bodies are not logged in release builds.

The trust manager and interceptors are `:core`'s (they use `javax.net.ssl` and OkHttp, which are JVM, not Android). The Android app only supplies the `SecureStore` and the `Discovery` implementation.

**Cleartext.** `network_security_config.xml` sets `cleartextTrafficPermitted="false"` as the base config, with no domain exceptions (the Master speaks TLS only; Shared SDD §3.11). This is Android's counterpart to the iOS `NSAllowsLocalNetworking` arrangement and needs no weakening, because the app does its own pinning rather than relying on the system trust store.

**Rationale:** one TLS callback is the thing most worth getting right once. OkHttp is Google-services-free, so it runs on Quest unchanged (PSD Q-7).

### 2.4 Rendering the sky with OpenGL ES (ADR-AND-004, proposed)

**Decision (proposed; gated on a prototype on a low-end supported tablet):** the sky view is a `GLSurfaceView`/`TextureView` hosted in Compose through `AndroidView`, drawing stars as instanced point sprites from the streamed catalog with **OpenGL ES 3.0**, and deep-sky objects, solar-system bodies, horizon and overlays as separate passes, as the iOS Metal view does. Compose `Canvas` is rejected for the star field. Vulkan is not used.

**Rationale:** tens of thousands of points at 60 fps on pan and pinch is a GPU job; `Canvas` redraws on the CPU. GL ES 3.0 is available on every API 29 device, the Quest spike already proved this exact technique (GLES3 point sprites, 118,000 stars) on the weakest target, and the projection is shared work (Section 3.5). Vulkan would buy little for point sprites and costs driver variance across the Android device range.

**Open:** whether the Android view and the Quest immersive dome share their star-drawing code is the Quest SDD's decision; this SDD only requires that the projection, culling and the `SkyCamera` maths live in `:core` (pure Kotlin) so either renderer can use them.

### 2.5 The image viewer is tile-based (ADR-AND-005)

**Decision:** as iOS ADR-IOS-005: a low-resolution full frame immediately, higher-resolution tiles for the visible region when a zoom or pan settles. Implemented as a custom Compose composable using `Modifier.transformable`/pointer input for pinch, pan and fling, drawing the tiles to a `Canvas` through `ImageBitmap`s from a bounded tile cache (decoded off the main thread with `BitmapFactory`/`ImageDecoder`). Stretch is applied by the Master; changing it re-requests tiles with the current image kept until the new ones arrive.

**Rationale:** a full-resolution frame would be many megabytes over a weak link (PC7). Compose has no tiled image viewer; one component that owns the transform and the tile set is simpler than wrapping a view-system widget, and it is also what the Quest panel mode needs.

### 2.6 No Google Play Services (ADR-AND-006)

**Decision:** the app depends on no Google Play Services library, no Firebase, no Google-signed-in anything — not for location, discovery, maps, crash reporting, or notifications. Android's own framework APIs are used (`NsdManager`, `AndroidKeyStore`, `NotificationManager`).

**Rationale:** it is a hard requirement of the Quest edition (PSD Q-7, PC10) and keeps the Android app small; it also follows from "no analytics" (`MAPP-PLT-060`). A dependency that pulls Play Services in transitively is a build failure (Section 3.12 checks it).

### 2.7 Dark-adapted by construction (ADR-AND-007)

**Decision:** two themes from the shared tokens (`S-DSN`): Standard (system light/dark) and Night (red only). The Night theme is applied at the **root of the composition** through a `CompositionLocal` that every component reads, so a component can only get colours from it. **Material 3 components are not used raw.** `MaterialTheme` is not the source of colour; the app has its own small set of themed components (button, switch, slider, text field, chip, dialog, sheet, progress), built on `foundation` primitives, in `:app`'s `ui/` package. A lint check (custom Lint rule or `scripts/check_android.sh` source scan) refuses `androidx.compose.material3` imports and literal `Color(...)` values outside `ui/theme/`, as `scripts/check_ios.sh` does for iOS.

**System surfaces that can't be held to red.** Some Android surfaces are drawn by the system and ignore the app's colours: the **keyboard** (IME), **system dialogs** (permission prompts, the runtime notification permission), the **share sheet** and **file picker**, the **status and navigation bars** (partially controllable) and the notification shade. The app draws its own dialogs, sheets and menus in the composition, hides the system bars in Night (immersive mode, with transient reveal), and uses the system picker only where a file must be chosen (`.gses` import). The remaining surfaces are listed in risk A5 with the advice to enable Android's *Colour correction* / a red screen filter (or *Extra dim*) from Accessibility. The window itself is kept dark: `window.setBackgroundDrawable` and a dark `windowBackground` in the theme so that a slow first frame, a rotation or a transition never flashes white.

**Brightness.** The app may set `WindowManager.LayoutParams.screenBrightness` for its own window while Night is on (no permission needed, and it ends when the app leaves the foreground); it never raises it, and it leaves the system brightness setting untouched (`MAPP-LAY-030`).

**Rationale:** one stray white surface destroys dark adaptation. Material's defaults produce non-red tints in states (ripples, disabled, focus, elevation overlays) that are impractical to chase one by one, so the app does not use them.

### 2.8 The app runs only while it is visible (ADR-AND-008)

**Decision:** as on iOS, the Master is the authority and the app is a window onto it. The app keeps its connection while a screen is visible. It does **not** run a foreground service, hold a wake lock, or claim to stay connected in the background. When the activity stops, `A-LIFE` stops every hold and drops the stream; on return it reconnects and resyncs (`MAPP-CON-070`).

**Rationale:** a persistent connection would need a foreground service of a type Google Play does not allow for this purpose, would drain a tablet left in a cold dome, and would turn "never show stale as live" into a harder claim. `MAPP-PLT-080` asks the app to behave within the background rules; the simplest way is to do nothing in the background. Notifications (`MAPP-NOT-010`) are therefore limited as in Section 3.7.

The screen is kept on **while connected to a Master and a screen that is being watched is showing**, with `FLAG_KEEP_SCREEN_ON` (no permission, released when the window goes), because a tablet that sleeps on the tripod drops the session view. This is a user setting in Settings (*Keep screen on while connected*), on by default; Night's low brightness keeps its cost small.

---

## 3. Component Design

```
 A-SHELL  navigation, Pier switcher, connection banner, safety indicator, emergency button
   │
   ├─ A-CONN   discovery (NSD) · pairing · handshake messages · multicast/permission handling
   ├─ A-OPS    operating screens: devices · imaging · solve · focus · guiding · flip · calibration · safety
   ├─ A-SKY    sky view (OpenGL ES) · targets · framing
   ├─ A-IMG    image viewer · histogram · Library
   ├─ A-SES    session and schedule authoring and running
   ├─ A-SAFE   lease · confirmations · emergency · roles
   ├─ A-THEME  tokens · layouts · accessibility · strings
   ├─ A-LIFE   activity lifecycle · holds · notifications
   ├─ A-TRUST  Keystore SecureStore · certificate pin
   ├─ A-BRIDGE ViewModel over :core
   └─ A-REL    packaging · privacy · release
```

### 3.0 Modules

| Module | Kind | Contents | Android imports |
|---|---|---|---|
| `:api-client` | JVM library (exists) | Generated request layer from `api/openapi.yaml` | none |
| `:core` | JVM library (to build) | The `S-*` components: `S-LINK` (including `PinPolicy`, the stream client, the reconnect loop), `S-API` facade, `S-STATE`, `S-CTL`, `S-SES`, `S-SCH`, `S-SKY` (decoder, cache, camera maths), `S-IMG`, `S-SEC` interface, `S-MOCK` player, vector runner | **none** — enforced by the module's plugin set (`kotlin("jvm")`) |
| `:design` | JVM library (to build) | Output of `scripts/gen_tokens.py`: colour tokens, type scale, spacing, strings table. Pure data classes | none |
| `:app` | Android application (to build) | Everything named `A-*`: Compose UI, `AndroidKeyStore` store, `NsdManager` discovery, lifecycle, manifest, packaging | yes |
| `:quest` | Android application (Quest SDD) | Later; depends on `:core`, `:design` and `:app`'s screens | yes |

For Quest to reuse the screens without the `Activity`, the screens go in a library, so the proposed split is `:app-ui` (Android library: themed components, screens, `A-BRIDGE`) and `:app` (the phone/tablet `Activity`, manifest and release config), with `:quest` as a second host. **Proposed**, decided with the Quest SDD; until then `:app` is one module with the screens in a separate package that has no `Activity` references, so the split is a move, not a rewrite.

### 3.1 `A-SHELL` — App shell and navigation

- **Structure.** Behaviour as [iOS §3.1](../ios/SDD.md): a themed menu of areas, the top bar carrying the working Pier and switcher, connection state with the age of the oldest value on screen, role and lease, the safety indicator, and the emergency button; the same areas, appearing when they work. On Expanded/Medium windows the menu slides over from a top-bar button and hides itself after use, or is pinned beside the content by *Keep open* (remembered); on Compact windows it is a themed strip of areas. Neither uses Material's `NavigationDrawer`, `NavigationRail` or `TopAppBar`, which can't be held to Night (Section 2.7).
- **One activity, composable destinations.** A single `MainActivity` hosts a simple state-driven navigation (a sealed `Area` and a back stack in the `ViewModel`) rather than Navigation-Compose, because the back stack is shallow, no area is deep-linked, and the shell's own components decide what Back does. System Back closes the menu or a sheet first, then returns to the previous area, and on the root of the app asks for confirmation before leaving only while a hold or an operation is `Pending`.
- **Edge-to-edge.** The app draws edge to edge (`enableEdgeToEdge()`), consumes display cutout and system-bar insets in the shell, and uses `windowInsetsPadding` on the top bar and the bottom edge; it supports the gesture-navigation back swipe without its own edge gestures (the sky's pan starts inside the content area).
- **Overview, No configuration.** As iOS §3.1 (`MAPP-OVR-010`–`070`, `MAPP-CFG-010`, `020`). The Settings screen holds only app preferences (theme, data saver, units display, *Keep screen on while connected*) and the paired Masters with *Unpair*.
- **Satisfies:** `MAPP-OVR-*`, `MAPP-CFG-010`, `020`, `MAPP-LAY-010`, `020`.

### 3.2 `A-CONN` — Connection, discovery and pairing

- **Discovery.** Implements the `Discovery` interface with **`NsdManager`** (`discoverServices("_galileo-link._tcp", PROTOCOL_DNS_SD)` and `resolveService` — on API 34+ `registerServiceInfoCallback` — wrapped as a `callbackFlow`). TXT records `name`, `node`, `proto`, `role` are read from `NsdServiceInfo.attributes` (the same set iOS decodes, iOS IO1). `NsdManager` resolves one service at a time on older releases, so the wrapper serialises resolves through a queue and handles `FAILURE_ALREADY_ACTIVE` by retrying. Discovery starts when the discovery list is shown and stops when it leaves the screen. Manual entry is validated by `S-LINK` (`MAPP-CON-010`, `020`).
- **Permissions (`MAPP-PLT-080`).** On the app's `minSdk` 29 up to API 32, local discovery needs no runtime permission; the manifest declares `INTERNET`, `ACCESS_NETWORK_STATE` and `CHANGE_WIFI_MULTICAST_STATE` (the last for a `MulticastLock` held only while discovery runs, since some tablets filter multicast without it). **API 33+** introduces `NEARBY_WIFI_DEVICES` for some local-network operations, declared with `android:usesPermissionFlags="neverForLocation"`. **Android 17 (API 37)** is expected to add a local-network access permission (`ACCESS_LOCAL_NETWORK`) for apps targeting it **[to verify on release; not assumed]**. Whichever a given OS version requires, the app asks in context, after its own explanation screen ("Galileo needs to find your observatory computer on your Wi-Fi network"), and never asks for a permission it doesn't use: no location, no storage, no contacts, no microphone, no camera. If a permission is denied, the screen explains what is lost and offers *Enter address*, which needs only `INTERNET`, so the app is never unusable (the equivalent of iOS I2).
- **Pairing screen, connection banner, capabilities, demonstration mode, multiple Masters, unpair.** As [iOS §3.2](../ios/SDD.md), in behaviour and wording (shared strings). The *update the app* message links to the app's page in Google Play (`market://details?id=…` with an `https://play.google.com` fallback), or to the download page for a side-loaded Quest build (`MAPP-PLT-090`).
- **Satisfies:** `MAPP-CON-*`, `MAPP-PLT-080`, `MAPP-PLT-090`.

### 3.3 `A-TRUST` — Secure storage and certificate pinning

- **Keystore.** Implements `SecureStore`. An AES-256-GCM key is generated in the **Android Keystore** (`KeyGenParameterSpec`, `setUserAuthenticationRequired(false)` — the app must reconnect after a reboot without the screen being unlocked, as iOS's *after first unlock*; hardware-backed where the device has it, **StrongBox requested and optional**). The token and pinned fingerprint for each Master are encrypted with it and the ciphertext stored in a private file or `SharedPreferences`. The key is never exportable, and not in any backup: `android:allowBackup="false"` and `dataExtractionRules` exclude the store from cloud backup and device-to-device transfer, so a restored app starts unpaired rather than holding a token that no longer decrypts. If decryption fails (key invalidated, restored data), the entry is treated as absent and the app returns to unpaired with an explanation. The token is never written to preferences in the clear, files, logs, the clipboard or a crash report (`MAPP-CON-040`).
- **Library choice.** `androidx.security:security-crypto` (`EncryptedSharedPreferences`) is deprecated, so the store is written directly against `AndroidKeyStore` and `javax.crypto.Cipher`; it is small, and testable on the JVM by substituting a JCE keystore behind the same interface (the in-memory `SecureStore` used in `:core` tests remains for logic tests).
- **Pinning.** `PinPolicy` as in Section 2.3. It never returns a trust manager that accepts everything, and no code path catches a pin mismatch and carries on; a mismatch raises `IdentityChanged` from `S-LINK` (`MAPP-CON-050`).
- **Satisfies:** `MAPP-CON-040`, `050`, `060`.

### 3.4 `A-OPS` — Operating screens

One Compose screen per area, each built from the catalogue (Shared SDD ADR-APP-004), exactly as [iOS §3.4](../ios/SDD.md) lays out in its table of screens, with these Android specifics:

- **Jog pad and held controls.** `pointerInput` with `awaitEachGesture { awaitFirstDown(); … }` calls `HoldSession.start` on touch-down and `.stop` on release, cancellation (a lost pointer, a system gesture taking the touch), or when the composable leaves the composition (`DisposableEffect`). It also stops when the activity stops (Section 3.7) and when the window loses focus (`onWindowFocusChanged`; a notification shade or a multi-window switch). Multitouch: a second finger does not extend a hold; only the pointer that started it counts (`MAPP-DEV-030`).
- **Charts.** There is no Compose chart library the app can adopt without Material or Play Services dependencies it doesn't want. The guide trace, V-curve and history charts are drawn with `Canvas` by one small `Chart` component in `:app` (lines, points, axes, the tokens' colours), fed by `S-STATE`/`S-API`. Its tick and label layout is unit-tested on the JVM.
- **Controls sized for gloves.** 56 dp minimum target (Section 3.8); `minimumInteractiveComponentSize` is not relied on, since the components are the app's own.
- **Satisfies:** `MAPP-DEV-*`, `MAPP-IMG-010`, `020`, `050`, `060`, `MAPP-SOL-*`, `MAPP-FOC-*`, `MAPP-GUI-*`, `MAPP-FLP-*`, `MAPP-CAL-*`, `MAPP-SAF-030`.

### 3.5 `A-SKY` — Sky view

- Hosts the OpenGL ES view of ADR-AND-004 with a Compose overlay for search, time controls and the object's detail card. Behaviour — layers, the sliding panel, the object menu (*Goto*, *Goto and Centre*, *Sync*, *Add to Session*), the confirmation before go-to, the "Not live" banner — is [iOS §3.5](../ios/SDD.md), and is not restated. Gestures: drag to pan, pinch to zoom, tap to select, double-tap to centre, via `pointerInput` on the host composable; the GL view receives only the resulting camera, never raw touch.
- **Projection (`MAPP-SKY-090`).** `SkyCamera` and `SkyGeometry` are pure Kotlin in `:core`, written to the same specification as iOS's and held to the same `api/vectors/sky/projection.json` (Section 3.12). The shader receives the camera as uniforms; it contains no projection constants of its own.
- **Survey (`MAPP-SKY-070`).** The HiPS tile planner (`SkySurvey.plan`: order from the pixel scale, at most 48 tiles, one order coarser otherwise, nothing new fetched below 3° of view) is in `:core` and shared with Quest. Tiles are decoded to textures off the main thread; four requests at a time, the latest view first, 96 held. In Night they are drawn through a red-only tint in the fragment shader.
- **Ground and horizon (`MAPP-SKY-080`).** A per-fragment pass after the stars, as iOS, with the `landNear`/`landFar`/`horizonFill` tokens.
- **Context loss.** A GL context can be lost when the activity stops or on some devices when the screen turns off; the renderer re-uploads its buffers and textures from `:core`'s cache (the cache is not in GL memory). A lost context never leaves a black view: the previous frame is kept in a bitmap and shown behind the "Not live" banner until the first new frame.
- **Satisfies:** `MAPP-SKY-*`, `MAPP-STR-*`.

### 3.6 `A-IMG` — Image viewer and Library

- Behaviour as [iOS §3.6](../ios/SDD.md): the viewer (ADR-AND-005), stretch control, histogram, star readout, *Reject frame* for Operators, live-stack progress, *Download FITS*, the data-saver setting, and the Library.
- **FITS download (`MAPP-IMG-080`).** Shows the size, then runs Connect Link's chunked, resumable transfer in a coroutine tied to the screen, not to a service; it can be cancelled and resumed. The finished file is written through the **Storage Access Framework** (`ActivityResultContracts.CreateDocument`) to a place the user chooses, so the app needs no storage permission at all. Leaving the screen cancels a transfer in progress (it is resumable from where it stopped).
- **Memory.** Tile and preview bitmaps are in a bounded LRU sized from `ActivityManager.getMemoryClass()` and trimmed in `onTrimMemory`; a low-RAM tablet gets a smaller cache and a lower default preview width (Section 5, A6).
- **Satisfies:** `MAPP-IMG-030`–`090`, `MAPP-LIB-*`.

### 3.7 `A-LIFE` — Lifecycle, holds and notifications

- **Lifecycle.** `A-LIFE` observes the process/activity `Lifecycle` (`ProcessLifecycleOwner` and the activity's `ON_STOP`/`ON_START`). On `ON_PAUSE`/window-focus loss: stop every active hold immediately (the shared `HoldSession.stop`). On `ON_STOP`: also pause previews and sky requests and close the stream. On `ON_START`: reconnect, replay or resync, and show state only when fresh (`MAPP-CON-070`, PC4). Multi-window and picture-in-picture are handled by `ON_PAUSE` semantics: a window that is visible but not focused keeps its stream but holds nothing. `onConfigurationChanged` for rotation, size and dark-mode changes is declared in the manifest (`configChanges`) so that a rotation never recreates the activity and drops a hold; the state lives in the `ViewModel` regardless.
- **Network changes.** A `ConnectivityManager.NetworkCallback` triggers `S-LINK`'s immediate reconnect attempt when Wi-Fi returns, and binds the process to the Wi-Fi network when the user is on a Master's LAN with mobile data also on (`bindProcessToNetwork`), so traffic to a LAN address never leaves by a mobile network that can't reach it. Android's "this Wi-Fi has no internet" auto-switch otherwise sends traffic away from an observatory network with no uplink — the common case in a field setup.
- **Local notifications (P2, `MAPP-NOT-010`).** While the activity is running, important alerts post as notifications through `NotificationManager` on one channel, "Observatory alerts". On API 33+ the `POST_NOTIFICATIONS` permission is requested with a plain explanation, in context, and only when the user turns the feature on; denial just disables the feature. Because the app holds no connection in the background (ADR-AND-008), notifications cover the interval in which Android keeps the process alive after the activity stops, and the app says so rather than implying monitoring. **No FCM, no push token, no server** (PSD §5).
- **Satisfies:** `MAPP-CON-070`, `MAPP-DEV-030` (stop on leaving foreground), `MAPP-CTL-060`, `MAPP-NOT-010`.

### 3.8 `A-THEME` — Layout, theme, accessibility, localisation

- **Layouts.** Window-size-class layouts as ADR-AND-001: several panels side by side on Expanded/Medium windows, in landscape and portrait, using `BoxWithConstraints` where a panel must decide on width (`MAPP-LAY-010`, `020`).
- **Night theme.** ADR-AND-007. The factory default is Night with low-brightness advice, then the user's last choice; the app prompts once to lower the screen brightness and never raises it (`MAPP-LAY-030`).
- **Accessibility.** Font scaling honoured everywhere (text in `sp`; charts' labels scale within a clamp), `Modifier.semantics` with `contentDescription`/`stateDescription`/custom actions on every control including the jog pad (explicit *Hold to move* actions for TalkBack, which cannot hold a pointer), a contrast check over the tokens as a JVM test in `:design`, minimum target 56 dp, spacing from tokens (`MAPP-LAY-040`, `050`). Compose UI tests assert that every clickable has a semantic label.
- **Strings.** The shared string table is compiled by `scripts/gen_tokens.py` into `res/values/strings.xml` (and `values-xx` later); composables use `stringResource` with keys only, never literals. Placeholders keep the shared table's form (`MAPP-LAY-060`).
- **Satisfies:** `MAPP-LAY-*`.

### 3.9 `A-SAFE` — Roles, lease, confirmations and emergency

As [iOS §3.9](../ios/SDD.md), with the same behaviour: Viewer controls are not composed at all rather than disabled; the lease chip and its take/release/ask sheet; one reusable confirmation dialog drawn by the app in a layer under the top bar, fed by `S-CTL`'s summary, with *Cancel* as the default; pending / `OutcomeUnknown` / refused states; the Safety screen with *Acknowledge* and no override of any kind.

Android specifics:

- **Emergency.** The control sits in the top bar above the dialog layer. *Stop everything*, *Park* and *Close dome* each need a **press-and-hold** (about one second, a visible progress ring) or swipe-to-confirm. TalkBack users get an equivalent that doesn't need a timed hold: a custom accessibility action that opens a *double-confirm* step (a second explicit "Confirm stop everything" button), so the control is reachable without a gesture that assistive technology can't perform (`MAPP-EMG-010`, `020`).
- **Back and dialogs.** System Back never confirms an operation. It dismisses a dialog (equivalent to *Cancel*), and a confirmation dialog without `OutcomeUnknown` pending cannot be left half-sent.
- **Satisfies:** `MAPP-CTL-*`, `MAPP-SAF-*`, `MAPP-EMG-*`, `MAPP-SKY-040`.

### 3.10 `A-SES` — Sessions and scheduler

As [iOS §3.10](../ios/SDD.md): the run view with live progress tree and a job queue first (MVP), the block editor and `.gses` import/export later (P2). Android specifics: drag-to-reorder uses the app's own `pointerInput` reorderable list (no third-party dependency), indent/outdent are buttons and a swipe; the inspector form is generated from each block's parameter schema; `.gses` import uses `ActivityResultContracts.OpenDocument` and export `CreateDocument`/`ShareCompat`-free save (no share sheet needed; the file picker saves it), so neither needs a storage permission. Draft sessions are held in the `ViewModel`, and as an unsaved-draft convenience in app-private storage, never discarded on a failed save (`MAPP-SES-050`).

**Satisfies:** `MAPP-SES-010`–`070`, `MAPP-SCH-*`.

### 3.11 `A-BRIDGE` — ViewModel layer

`A-BRIDGE` is one `AppViewModel` holding the latest immutable `SessionSnapshot` from `ObservatorySession` as a `StateFlow`, collected by composables with `collectAsStateWithLifecycle()`. Screens read it and bind controls to catalogue operations through it; form state stays in the composables (`rememberSaveable` where a rotation must keep it). This is the same single-snapshot arrangement iOS settled on, for the same reason: every screen shows the same snapshot. `viewModelScope` owns the connection, so it survives rotation and is cancelled with the activity's final destruction. Launch intents (`--es galileo.demo <scenario>`, `--es galileo.theme night`) inject the `S-MOCK` player for Compose UI tests and for `adb`-driven screenshots.

### 3.12 `A-REL` — Packaging, privacy, release and build

- **Identifiers.** Application id `com.gordtulloch.galileo.mobileconnect`, the same as the iOS bundle id (PSD Q5). **Decided 2026-11-12** to match iOS. The Google Play application id cannot change after first publication, so it is fixed before the first upload. Display name *Galileo* on the launcher, matching iOS. The Quest build, which is side-loaded and listed separately, gets its own id so the two can be installed side by side (decided in the Quest SDD).
- **Manifest.** `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `NEARBY_WIFI_DEVICES` (API 33+, `neverForLocation`) and, if the feature is enabled, `POST_NOTIFICATIONS`. No location, storage, camera, microphone, foreground-service or wake-lock permission. `android:allowBackup="false"`, `android:usesCleartextTraffic="false"`, a `networkSecurityConfig`, and `android:exported` explicit on the single launcher activity. Landscape and portrait both supported; `resizeableActivity="true"`.
- **Play Console data safety form:** "No data collected, no data shared"; states that the app talks only to the user's own Master. Content rating, target audience and the review notes follow the iOS review notes: describe the app plainly, point at demonstration mode (no hardware needed), and explain the local-network permission (PSD R7). The permissions declaration stays empty of sensitive permissions.
- **Distribution.** Free, no ads, no in-app purchases (`MAPP-PLT-050`); Google Play with an internal-testing track first, then closed testing (Google Play's requirement of a testing period for new personal developer accounts applies **[verify against the current policy before the first submission]**). **Play App Signing** is used: Google holds the app signing key and the upload key is kept by the owner in an offline keystore that is not in the repository. A side-loadable APK is also published on the Galileo website with a checksum, as the Quest edition requires for its channel (PSD Q-8); whether that APK shares Play's signing key is the decision recorded in the Quest SDD (SRS §5 item 9). The licence text is added before the first release (`MAPP-PLT-100`).
- **Minification.** R8 with full-mode shrinking on release; `keep` rules for kotlinx.serialization models and OkHttp are in the repository; the release build is run in the UI tests because shrinking breaks serialization, not the debug build.
- **Build.** Gradle with the Kotlin DSL and a **version catalog** (`gradle/libs.versions.toml`), a **committed Gradle wrapper**, JDK 17, Android Gradle Plugin current at the time (the Quest spike built with AGP 8.5.2 and Gradle 8.7; `:api-client` uses Kotlin 2.0.21, so the AGP chosen must support it), pinned exactly. `:api-client` generates from `api/openapi.yaml` through the OpenAPI Generator Gradle plugin; `scripts/sync_api.py --check` also guards the Android copy so the description cannot drift (Shared SDD §6). `gradlew :core:test` replays the `S-TEST` vectors on the JVM; `gradlew :app:connectedDebugAndroidTest` runs Compose UI tests on an emulator or tablet against `S-MOCK`. Building, signing and Play upload all work on **Windows**, macOS or Linux (Shared SDD §6).
- **`scripts/check_android.sh` (and a PowerShell twin for Windows).** Runs the whole Android check as `scripts/check_ios.sh` does for iOS: sync check, tokens, vectors, Lint, the no-Material and no-literal-colour rule (ADR-AND-007), no Play Services in the dependency graph (`gradlew :app:dependencies` scanned for `com.google.android.gms` and `com.google.firebase`), `:core:test`, then the app tests, and the Night pixel check over the UI tests' screenshots (`scripts/check_night.py` is platform-neutral).
- **Satisfies:** `MAPP-PLT-010`, `020`, `050`, `060`, `080`, `100`.

---

## 4. Platform Risks

| # | Risk | Mitigation |
|---|---|---|
| A1 | Fragmentation across vendors: Wi-Fi power saving, aggressive background killing and NSD quirks differ by manufacturer | The app needs nothing in the background (ADR-AND-008); discovery has a manual-address fall-back; the target-device list in the test plan includes at least one Samsung, one Pixel/near-stock and one budget tablet |
| A2 | `NsdManager` is unreliable on some releases (resolve failures, one resolve at a time, stale services) | The resolve queue and retry in `A-CONN`; the discovery list shows results as they resolve; manual entry always works; a built-in "search again" |
| A3 | The local-network permission model changes between releases (API 33 `NEARBY_WIFI_DEVICES`, a local-network permission expected in a later release) | Permissions are asked in context and a denial leaves manual entry working; the manifest and targetSdk are re-checked on each target bump; marked **[to verify]** in §3.2 |
| A4 | Android sends traffic to mobile data when the observatory Wi-Fi has no internet | `bindProcessToNetwork` to the Wi-Fi network while connected to a Master (`A-LIFE`); the connection banner names the network problem, not just "unreachable" |
| A5 | A stray non-red surface in Night (keyboard, system dialogs, share sheet, notification shade, system bars) | The app draws its own dialogs, sheets and menus and hides the system bars; the unthemeable list is documented with the advice to enable Android's red colour-correction/extra-dim from Accessibility; the Night pixel check runs over the UI tests |
| A6 | A low-RAM or old tablet runs out of memory in the image viewer and sky survey | Caches sized from `getMemoryClass()`, trimmed on `onTrimMemory`, with bounded tile counts; the data-saver setting lowers preview width; tested on a 2 GB device |
| A7 | The core's logic exists in Swift and Kotlin and could drift | `S-TEST` vectors run on both platforms in CI; the Shared SDD is the single specification; this SDD adds no behaviour |
| A8 | Play Store review and policy (new-developer testing requirements, target SDK deadlines, data-safety form accuracy) | Demonstration mode and review notes as iOS; target SDK tracked each release; the data-safety form states what the Section 3.12 checks enforce |
| A9 | OpenGL ES driver variance across Android GPUs | GLES 3.0 only; the star pass uses the plain point-sprite path the Quest spike proved; shader precision qualified explicitly; a tablet with a Mali and one with an Adreno in the test set |
| A10 | The Quest edition's needs diverge from the phone/tablet app and force a rework of the screens | `:core` is pure JVM and the screens avoid `Activity` references from the start (Section 1.3, 3.0); the `:app-ui` split is a move, not a rewrite |

---

## 5. Open Design Points

| # | Point |
|---|---|
| AO1 | Whether the screens live in `:app` or a separate `:app-ui` library (Section 3.0): settle with the Quest SDD; keep the package boundary meanwhile |
| AO2 | Sky renderer: the GL ES 3.0 view of ADR-AND-004 is **proposed**; decided by a prototype on a low-end supported tablet (a counterpart of iOS IO2), including whether Android and Quest share the star pass |
| AO3 | Emergency control: press-and-hold of one second as on iOS, with the TalkBack double-confirm alternative; user-test on a tablet at night with gloves (counterpart of iOS IO3) |
| AO4 | Which phone panels fold (PSD Q1): the same decision as iOS IO4, made once from use |
| AO5 | ~~Application id~~ **Closed 2026-11-12:** `com.gordtulloch.galileo.mobileconnect`, matching iOS (PSD Q5). The store name remains open |
| AO6 | Whether API 37's local-network permission applies to this app and how it interacts with `NsdManager`; verify on a device when the target SDK is raised (A3) |
| AO7 | `FLAG_KEEP_SCREEN_ON` default (on) and whether a dim overlay is wanted (Section 2.8) — settle after a night's use |
| AO8 | `minSdk` 29 (SRS §5 item 3) against the device base; reconsider only with evidence, and with `MAPP-PLT-010` changed first |
| AO9 | ~~Generator run~~ **Closed 2026-11-12:** the OpenAPI Generator Gradle plugin (7.10.0, pinned in `api-client/build.gradle.kts`) runs at build time straight from `api/openapi.yaml`; nothing generated is committed and no copy of the description is needed on the Android side |
| AO10 | Whether the website APK and the Play build share a signing key (SRS §5 item 9; Quest SDD) |

---

## 6. Build Order

Android follows the iOS app; the order is chosen so that each step can be checked on a Windows machine without hardware:

1. **Toolchain and wrapper. Done 2026-11-12 for `:api-client`.** The Gradle 8.7 wrapper is committed (taken from the Quest spike); `gradlew :api-client:compileKotlin` succeeds with JDK 17. The first run needed two fixes: bare `type: number` in the description is now `format: double` (28 places, so the generator emits `Double`, not an unserializable `BigDecimal`; the iOS copy was re-synced), and free-form JSON (`AnyType`) is mapped to `kotlinx.serialization.json.JsonElement` in `api-client/build.gradle.kts`. The version catalog (`android/gradle/libs.versions.toml`) is in place.
2. **`:core`. Control and session built 2026-11-13; 74 JVM tests pass and all 15 shared vectors replay.** Link layer: `MasterAddress`, `PinPolicy`/`PinningTrustManager` (checked against a real certificate), `SecureStore`, `Backoff`/`StreamUrl`, `MasterApi` (the facade over the generated client: identity, bearer token, every failure as a plain-words `LinkError`), `PairingFlow`, and the event stream (`StreamLink`, `WebSocketStreamTransport`, `StreamMessage`). `S-STATE`: `LiveState.reduce` and the staleness views. `S-CTL`: the models and catalogue filter (`core.api`), argument validation in the Master's words, `OperationLedger` (pending until the Master says otherwise, `OutcomeUnknown` on a lost link and never re-sent, a finish that overtakes the HTTP answer), `ControlPolicy` and `ConfirmationRequest` (the always-confirm list plus the Master's own `confirm` token), `HoldSession` (250 ms beats, stops on release, background, lost link, lost lease or a refused beat; a release during the start still sends the stop), and typed `OpResult`s from `MasterApi.invoke`, `acquireLease` and `releaseLease`. `ObservatorySession` now does the start-up (`prepare`: handshake, `whoami`, Piers, catalogues, capabilities, alerts; refusal and configuration-grant stops; retry until Piers appear), requests, confirmation, emergency, the lease and held motion. The vector runner (`VectorRunner`, test source set) replays `api/vectors/` through the real generated client against a Master whose answers come from the vector; `VectorTest` still fails unless its pending list (now empty) is exactly the vectors the runner cannot express. `RealTlsTest` runs the pin against a real TLS handshake. Each component's tests are the iOS cases ported, so the two cores agree. Two differences from the generated client were found and handled in `:core` rather than by editing generated code: it writes an unset optional field as `null` (iOS omits it), so `DropNullFields` removes top-level nulls from request bodies; and a non-2xx answer arrives as an exception carrying the body, which `MasterApi.invoke` turns into the same typed result iOS gets. Still to build in `:core`: `S-SKY` (sky model, tiles, search, go-to), `S-IMG` (preview and tiles), `S-SES`/`S-SCH`, and the `S-MOCK` player. Nothing has run against a real Master yet; that waits for the `:app` skeleton.
3. **`:design`. Done 2026-11-13.** `scripts/gen_tokens.py` writes `Tokens.kt`; `TokensTest` re-checks contrast and that Night is red only. Strings are not yet compiled to `strings.xml`.
4. **`:app`. Started 2026-11-13.** Theme, themed components, shell, connection screens, Keystore store, NSD discovery and all eleven screens are written and build; demo mode runs on an emulator (`--es galileo.demo normal-night`). UI tests and the Night pixel check are in (`DemoUiTest`). Open: strings resources, a look at every screen, OpenGL ES for the sky if the canvas proves too slow, real-Master test.
5. **Screens in the iOS order**: Equipment, Imaging, Focus, Solve, Guiding, Flip, Safety, Sessions, then Sky (GL), then Library/authoring/notifications (P2).
6. **Real Master.** Pair with the Galileo plugin's draft server code (as iOS did) and then a physical tablet; then a Quest 2 spike-to-app step belongs to the Quest SDD.

---

## 7. Requirement-to-Component Summary

The full per-requirement mapping is the [RTM](../RTM.md) (its Android column names these components). By area:

| SRS area | Android component | Notes |
|---|---|---|
| `CON` | `A-CONN`, `A-TRUST` | `NsdManager`, Keystore, OkHttp pinning |
| `OVR` | `A-SHELL` | |
| `DEV`, `SOL`, `FOC`, `GUI`, `CAL` | `A-OPS` | |
| `FLP` | `A-OPS`, `A-SAFE` | |
| `IMG`, `LIB` | `A-IMG`, `A-OPS` | |
| `SKY`, `STR` | `A-SKY` | |
| `SES`, `SCH` | `A-SES` | |
| `SAF`, `EMG`, `CTL` | `A-SAFE` | |
| `CFG` | `A-SHELL` | No configuration is representable (`S-API`) |
| `NOT` | `A-LIFE` | |
| `LAY` | `A-THEME` | |
| `PLT` | `A-REL` | `PLT-080` in `A-CONN` and `A-REL` |
| `QST` | — | Quest SDD |

**Satisfies (this document as a whole):** every SRS requirement whose *Platforms* column is `All`, `Mobile` or `Android`.
