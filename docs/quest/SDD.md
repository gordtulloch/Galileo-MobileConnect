# Galileo MobileConnect — Meta Quest SDD

| | |
|---|---|
| **Project** | Galileo MobileConnect — Meta Quest edition |
| **Document** | SDD (Software Design Description), v0.1 — **Meta Quest platform design** |
| **Status** | **Decisions QD1-QD5 agreed 2026-11-13.** Built: `:app-ui` (shared screens), `:quest-logic` (headset-free rules, 24 tests) and **`:quest`, the complete app, compiled to an APK with its native library (2026-11-13) but never run on a headset**: on the emulator it installs, links its JNI functions and starts, then exits because there is no OpenXR runtime. Everything that needs a headset (the OpenXR loop, the renderer, Compose panes on surface swapchains, pointing, performance) is untested; Section 9 lists what to check first. The spike in `spikes/quest-atlas/` was the source of the OpenXR, EGL, shader and input code |
| **Date** | 2026-11-13 |
| **Upstream documents** | [PSD](../PSD.md) §4a, [SRS](../SRS.md) §3.22 `MAPP-QST`, [Meta Quest VR analysis](../analysis/QuestVR.md), [Shared SDD](../SDD.md), [Android SDD](../android/SDD.md) |
| **Downstream document** | [RTM](../RTM.md) |

---

## 1. Introduction

### 1.1 What this edition is

The same app as the tablet, for a headset, with one difference of substance. **The Sky screen is replaced by a full virtual star field**: the user stands inside the sky, looks around, zooms, moves through time, points at an object and presses the trigger, and the menu the tablet's Star Atlas gives (Goto, Goto and Centre, Sync, Add to Session) pops up beside it. **Everything else is a pane**: a **floating dock** in front of the user has a button for each of the usual Galileo areas, and pressing one opens that area as a floating window that can be moved, resized and closed. The Emergency button lives on the dock and is always within reach.

Components are named `Q-*`. The shared layer (`S-*` in the [Shared SDD](../SDD.md), `:core` on Android) does the transport, state, control safety, sky data and images; the Android app's Compose screens are the panes (PSD Q-1). This document designs only what is the Quest's own.

### 1.2 What exists

- **`:core`** (built, Kotlin/JVM, 115 tests): link, pairing, stream, live state, operations and holds, the sky (`SkyGeometry`, `SkyStore`, `SkySurvey`, HEALPix tiles), the mock Master. It has no Android imports, so it runs unchanged on the headset (Android SDD §1.3).
- **`:app`'s screens** (built): Compose, themed, tested. They are not yet separable from `MainActivity` and the `AppViewModel` (Section 3.4).
- **`spikes/quest-atlas/`**: a stand-alone native OpenXR/GLES3 app (3,489 lines of C++) that drew a dome of 118,000 Hipparcos stars, Messier/Caldwell/NGC objects with DSS2 pictures, the Sun, Moon, planets, asteroids and satellites, a horizon and the Milky Way, with controller models, a laser and an object card. It is a **performance proof**, and it was run on a Quest 2 with good results (PSD §4a). It **ships its catalogues and computes its own ephemeris, which the product must not** (PSD Q-13); and its measurements were never written down: Section 5 asks the owner to record them.

### 1.3 What this document decides, and what it leaves to the owner

Section 2 records five decisions with a recommendation each. They are the owner's to settle before any build (PSD Q6 is still open): Section 8 lists them.

---

## 2. Architecture decisions

| # | Decision | Recommendation | Why |
|---|---|---|---|
| QD1 | **Rendering stack** | **Native OpenXR with OpenGL ES 3 in C++, hosted by a Kotlin app.** The star field, labels and object markers are drawn natively as in the spike; everything else is Kotlin. | The spike proved this stack on a Quest 2 at the star counts the dome needs. Unity would add a second runtime and a bridge to `:core`; Meta Spatial SDK has not been shown to draw a 100,000-point field at Quest 2 rates. A native renderer still lets `:core` and the Compose screens stay in Kotlin (Section 3). |
| QD2 | **How panes are drawn** | **Compose renders each pane into an Android `Surface` that OpenXR shows as a quad composition layer** (`XR_KHR_android_surface_swapchain`); pointer rays are turned into touch events on that surface. | A pane is then the tablet's own screen, themed by the same tokens, tested by the same UI tests, and a quad layer is sampled by the compositor at full panel resolution, which keeps text sharp at a distance. **This is the one part the spike did not prove**, so it is the first thing built and measured (Section 6, step 1). |
| QD3 | **Pane content** | **Split a shared `:app-ui` library out of `:app`**: the screens, components and `AppViewModel`'s state, with no `Activity`. `:app` and `:quest` both use it. | Required by Android SDD §1.3 and A10. The Sky screen stays in `:app` only. |
| QD4 | **Where the sky comes from** | **Streamed from the Master through `SkyStore`, as on the tablet** (PSD Q-13); the headset ships no catalogue and computes no ephemeris. Survey pictures come from `/sky/survey/tile`. | The spike's baked data would let the headset disagree with the Master. The cost is that the star budget is set by what the Master streams per look (Section 5). |
| QD5 | **The dock** | **World-locked, grabbable, in front of and below the gaze at start**, one button press to bring it back; not head-locked. | `MAPP-QST-190`/`-360`: the viewpoint and the furniture never move unless the user moves them; a head-locked bar is the commonest cause of discomfort and sits in the middle of the sky. |

---

## 3. Structure

```
 :quest (Android application)
  ├─ Q-HOST   Activity: lifecycle, headset on/off, JNI bridge, permissions            [Kotlin]
  ├─ Q-XR     OpenXR session, frame loop, controllers/hands, quad layers             [C++]
  ├─ Q-SKY    the star field: stars, deep-sky, bodies, horizon, pointing, FOV        [C++, GLES3]
  ├─ Q-DATA   SkyStore/ephemeris/constellations → renderer buffers over JNI          [Kotlin → C++]
  ├─ Q-PICK   laser/hand pick, selection ring, the object menu pane                  [C++ pick, Kotlin menu]
  ├─ Q-DOCK   the dock: buttons, state, grab, recentre                               [C++ geometry, Compose face]
  ├─ Q-PANE   pane host: Compose → Surface → quad layer, pointer → touch, grab/resize [Kotlin + C++]
  ├─ Q-INPUT  buttons for holds, thumbsticks for time and zoom, hand pinch            [C++, Kotlin]
  ├─ Q-SAFE   first-run notice, passthrough, headset-removed handling                [Kotlin + C++]
  ├─ Q-IMG    the image pane shown large, tiles from S-IMG                           [Kotlin]
  └─ Q-REL    package, signing, SideQuest guide, Horizon Store                       [Gradle, docs]
 :app-ui   (to extract from :app)   screens, components, theme, bridge state          [Kotlin/Compose]
 :core     S-LINK, S-STATE, S-CTL, S-SKY, S-IMG, S-MOCK                               [Kotlin/JVM]
```

### 3.1 Q-HOST and Q-XR

A native activity cannot easily share a process with Compose, so the app is an ordinary Android `Activity` that loads the native library and hands it a window. The Kotlin side owns the `ObservatorySession` (from `:core`) and its lifecycle; the native side owns the OpenXR instance, session and frame loop. `onPause`/headset-off pauses the frame loop, stops any held operation through `enteredBackground()` and, on wear, resynchronises through `enteredForeground()` (`MAPP-QST-090`). The Quest build uses no Google services (`MAPP-QST-100`): the manifest declares the Meta features the spike found it needs (render model, hand tracking, passthrough) and nothing else.

### 3.2 Q-SKY and Q-DATA — the star field

Rendering follows the spike: stars as point sprites on a dome, one pass, magnitude-cut by level of detail, constellation lines as arcs, deep-sky symbols by type, Sun/Moon/planets as discs and dots, an opaque ground with the Observatory's horizon profile, N/E/S/W, and the live pointing cross and field-of-view frame. What changes is where the data comes from. Q-DATA runs a `SkyStore` look centred on the gaze (the same call the tablet makes, level of detail from the field of view), converts each page to the renderer's vertex format and hands it over JNI as a direct buffer; the renderer keeps what it has while the next page arrives, so the sky never blanks. Positions are J2000 unit vectors; the renderer applies the rotation to the headset's horizon frame (`SkyGeometry.equatorialToHorizon`, held to the Star Atlas by `projection.json`), so the Quest agrees with the tablet.

**Time.** The left thumbstick moves the sky, never the viewer, proportionally to its deflection (spike behaviour); a button returns to now. A time offset asks the Master for `ephemeris` samples as the tablet does, and the headset interpolates between them.

**Night.** Red-only (`night` tokens) with a dim default; star size, not colour, carries magnitude (`MAPP-QST-150`).

### 3.3 Q-PICK — selecting an object

The laser ray from the right controller (or the pinch ray from a hand) is intersected on the CPU with the objects drawn: deep-sky objects first, then bright stars, then bodies, within an angular reach that grows as the view narrows. Objects the ground hides cannot be picked. A trigger press marks the object (a ring) and asks `:core` to identify it (`identify`), check its position (`checkPosition`) and, for deep-sky objects, fetch details (`sky.object`): the same three calls the tablet makes. The reply opens the **object menu** as a pane beside the object: name, details, altitude now, then Goto (with the Master's confirmation question), Goto and Centre, Sync and Add to Session, for an Operator who can operate this Pier and not at all for a Viewer. Goto goes through `ObservatorySession.goToQuestion`, so nothing is sent until the user accepts, and a slew still needs the lease (`MAPP-QST-320`).

### 3.4 Q-DOCK and Q-PANE — the dock and the panes

The dock is a rounded bar of large targets (at least 56 dp at a pane's scale, sized for pointing at a distance): Overview, Equipment, Imaging, Focus, Solve, Guiding, Flip, Sessions, Safety, Settings, then the Pier name, connection and control state, passthrough, recentre, and Emergency, which is red, always last, and works as on the tablet (open, then hold for a second). It is a Compose surface like any pane, so its buttons are labelled and testable the same way; it is placed in front of and below the gaze when the app opens, grabbed with the grip button and moved, and brought back by the left menu button.

A pane is one of the tablet's screens (`:app-ui`) in a Compose surface of fixed pixel size shown on a quad at a chosen distance. Panes are grabbed by their title edge, resized from a corner, closed by a button, and stack in depth so that none covers the dock. Several can be open. Pressing the same button again brings that pane to the front. The Imaging pane shows its picture large; the viewer's blink and side-by-side compare are P2 (`MAPP-QST-180`).

**Input into a pane.** The pointer ray is intersected with each quad; the hit becomes a touch at that point (down on trigger press, move while held, up on release). Text goes through the system virtual keyboard, which the Quest provides to a focused field. Held operations (focus, jog) need a **controller button hold** and stop on release, tracking loss or headset removal (`MAPP-QST-060`); the pane's hold buttons are driven by the button, not by a hand pinch.

### 3.5 Q-SAFE

The first-run notice (use indoors or seated) blocks the star field until acknowledged and is remembered. Passthrough is one button on the dock and on the right controller's menu. Headset off pauses the scene and releases any held operation. The first-run notice and the Pi-class Master message (`MAPP-QST-200`) are panes.

---

## 4. Requirements to components

| Requirement | Q-component |
|---|---|
| `MAPP-QST-010` to `-020` | Q-HOST, Q-REL |
| `MAPP-QST-030`, `-340` | Q-PANE |
| `MAPP-QST-040`, `-110` to `-150`, `-310` | Q-SKY, Q-DATA |
| `MAPP-QST-050`, `-060` | Q-INPUT |
| `MAPP-QST-070`, `-080`, `-090` | Q-SAFE |
| `MAPP-QST-100`, `-210` to `-230` | Q-REL |
| `MAPP-QST-160` | Q-SKY (measured, Section 5) |
| `MAPP-QST-170`, `-180` | Q-IMG |
| `MAPP-QST-190`, `-360` | Q-DOCK, Q-XR |
| `MAPP-QST-200` | Q-HOST, Q-PANE |
| `MAPP-QST-300`, `-320` | Q-PICK |
| `MAPP-QST-330`, `-350` | Q-DOCK |

---

## 5. Performance budget

The Quest 2 is the design budget (PSD Q-12): the frame rate its runtime offers (72 Hz default, up to 90), two eyes, a fixed-foveation-free render, mobile GPU and memory. **The spike's measurements are not in the repository.** Before the star-field build starts the owner is asked for: the refresh rate held, the star count at which it held (the spike offered 1,600 naked-eye stars up to 2 million synthetic), the GPU time per frame, and the worst frame gap. The product's budget is then `MAPP-QST-160` rather than a guess.

Streaming adds a second limit: the stars on the headset are what the Master sends per look, bounded by `SkyStore`'s 80,000-object memory limit and the Master's per-client rate. If the dome needs more at wide field than that allows, the limit is raised on the headset only, behind the Master's `sky_stream` capability (`max_page`, `rate_per_s`), and measured.

---

## 6. Build order

Each step can be checked on its own, and the first two settle the unproven parts before the rest depends on them.

1. **Panes on a headset (proves QD2).** `:app-ui` extracted from `:app`; `:quest` with the OpenXR loop; one Compose screen (Overview, against the mock Master) on a quad layer with pointer-to-touch input. Measure text sharpness and frame time with the panel open. *Needs the headset.*
2. **The dock and several panes**, all areas, with grab/resize/close and the Emergency hold.
3. **The star field from the Master**: Q-DATA over `SkyStore`, the renderer ported from the spike without its baked data, horizon, pointing and FOV, time stick. *Measured on the headset against Section 5.*
4. **Selection and the object menu** (Q-PICK) with Goto / Goto and Centre / Sync / Add to Session through `ObservatorySession`.
5. Passthrough, first-run notice, headset-off handling, packaging and the side-loading guide.
6. Survey pictures behind the stars (`/sky/survey/tile`), the image pane at large size, then P2 items.

**What can be verified without a headset.** The pick maths, dock layout and placement, pane stacking and the pointer-to-touch mapping are plain Kotlin/C++ with JVM tests; the star-field data path runs against the mock Master; the Compose panes run as tablet screens on the emulator. OpenXR, the frame loop, the quad layers and every performance figure can only be checked on a headset, which this repository's build machine does not have: **each build step is therefore finished only when the owner has run it on the Quest 2**, and the changelog says which steps have been.

---

## 7. Risks

| # | Risk | Mitigation |
|---|---|---|
| QR1 | Compose into a surface swapchain is slower or blurrier than expected | Step 1 measures it before anything depends on it; fall back to rendering panes to a texture and sampling it in the main pass |
| QR2 | The streamed star field is thinner than the spike's baked one | Level of detail by magnitude; the Quest-only raise in Section 5; the data tier measured on the headset |
| QR3 | A mis-picked object slews the mount | Operator, lease and the Master's confirmation and horizon check (`MAPP-SKY-040`); nothing is sent before the user accepts |
| QR4 | Pointer-to-touch is too imprecise for sliders | Larger targets, step buttons beside sliders (PSD R10); the tablet layout reused, not shrunk |
| QR5 | Work cannot be verified without a headset | Section 6: each step is finished on the owner's Quest 2; logic is kept testable off-device |

---

## 8. Decisions for the owner

OQ1 to OQ3 were settled on 2026-11-13 as recommended. OQ4 (the spike's measurements) and OQ5 (who runs each step on the Quest 2) remain open.

| # | Question | Recommendation |
|---|---|---|
| OQ1 | Rendering stack (PSD Q6): native OpenXR in C++ as the spike, Meta Spatial SDK, or Unity? | Native OpenXR (QD1) |
| OQ2 | Panes: reuse the Compose screens by extracting `:app-ui`, or rebuild the panes natively? | Extract `:app-ui` (QD2, QD3) |
| OQ3 | Dock: world-locked and grabbable, head-locked, or attached to the hand? | World-locked and grabbable (QD5) |
| OQ4 | Please record the spike's measurements (refresh rate, star count held, GPU ms, worst gap) so `MAPP-QST-160` can be set | — |
| OQ5 | Build order and who tests: steps 1 to 3 each need the Quest 2 in hand | Step 1 first, tested before the rest is built |

## 9. First run on the Quest 2 (what is untested, in the order to check it)

The `:quest` app was written without a headset and with the spike's measurements unrecorded (OQ4): the 72 Hz refresh request and the star budgets are the spike's settings carried over as they were, not re-measured. Install with `adb install -r quest-debug.apk` (developer mode on), start from Unknown Sources, or `adb shell am start -n com.gordtulloch.galileo.mobileconnect.quest/com.galileo.mobileconnect.quest.QuestActivity --es galileo.demo normal-night` to run against the simulated observatory without pairing. `adb logcat -s GalileoQuest GalileoQuestHost` shows what each step reports.

1. **A star field appears and follows the head** (native renderer; nothing else depends on it). If it is black, the log says which OpenXR call failed.
2. **The dock appears** in front of and below the gaze. This is the riskiest part: each pane is a Compose screen in a `Presentation` on a `VirtualDisplay` whose surface is an `XR_KHR_android_surface_swapchain`. If the log says `pane 0: surface swapchain failed` or `surface dock could not be shown`, the Compose-pane approach needs a different route (render the view to a texture), and the star field and picking still work.
3. **Pointing and the trigger press dock buttons and pane controls**; a held button (hold-to-confirm, slew) ends when the trigger is released, the pointer leaves the control, tracking is lost or the headset comes off.
4. **Picking**: a deep-sky object under the pointer opens the object menu; Goto asks before moving.
5. **Time (left stick), zoom (right stick up/down), grip to move a pane or the dock, A passthrough, B recentre the dock, X constellations, Y brightness.**
6. **Performance** against `MAPP-QST-160`.

Known gaps: no hand tracking (controllers only); text entry in a pane has no keyboard, so pairing a new Master (which needs a typed code) is not possible in the headset: pair on the tablet or use the demo launch for now; the ground is an opaque disc under the user, with passthrough as the way to see the room; panes cannot be resized in the headset yet (the logic exists and is tested).

