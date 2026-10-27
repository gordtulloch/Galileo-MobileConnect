# Quest star-atlas performance spike

Standalone Meta Quest 2 app: a dome of real Hipparcos stars (117,955, V −1.44 to 14.08)
drawn as GLES3 point sprites through native OpenXR. It exists to answer the question in
[`docs/analysis/QuestVR.md`](../../docs/analysis/QuestVR.md) §5 and §7.6 — *can a Quest 2 render
a 100,000-star dome smoothly?* — before the rendering stack is chosen. No Galileo Master is
involved; there is no networking.

## What it measures

An on-headset HUD (head-locked, lower field) and a once-a-second logcat line show:

| Field | Meaning |
|---|---|
| FPS / HZ | frames submitted per second against the runtime's target refresh |
| WORST | longest gap between two frames in the last second (a dropped frame shows as ~2× the period) |
| CPU | time from `xrWaitFrame` returning to `xrEndFrame` returning, per frame (logcat also splits wait / work / swapchain-wait / draw / end) |
| GPU | `EXT_disjoint_timer_query` time for both eyes (0.0 if the extension is absent) |
| STARS | points actually drawn after the magnitude cut |

## Controls

| Input | Action |
|---|---|
| Left stick X | advance / retard time, **proportional to deflection** (small push = slow, full push = 1 hour of sky per second) |
| Left stick Y | **zoom**: forward magnifies (up to 40x), back reduces. Centred on the locked object, otherwise on where you were looking when you started zooming |
| Right stick Y | magnitude limit |
| Right trigger or right stick click | **lock** the Messier / Caldwell / NGC object under the laser, or release (point at empty sky, or at the locked object again) |
| A | star-count tier: 0 = Hipparcos, naked-eye (magnitude 5 and brighter, ~1,600 stars; raise with the right stick), 1 = 500k, 2 = 1M, 3 = 2M **synthetic** |
| B | night mode (red only, PSD G10) |
| X | back to now (zero the time offset) |
| Y | constellation outlines and labels on/off |
| Left grip held + X | Messier / Caldwell / NGC layer on/off |
| Left grip held + Y | cycle display refresh rate (starts at 72 Hz; the runtime offers 60/72/80/90/120) |
| Left stick click | all-sky background on/off |
| Left menu | show/hide HUD |
| Trigger / grip / thumb-touch | curl the virtual index finger / other fingers / thumb |

Each controller is drawn as a **virtual hand** (procedural, no model files) posed from the controller and
posed fingers following the trigger, grip and thumb touch. A **laser pointer** runs from each controller to the
dome, ending in a ring. The HUD reads out what the right controller (left if the right is not tracked) is
pointing at: altitude/azimuth, J2000 RA/Dec, the nearest constellation centre, and the deep-sky object under
the beam (a grey ring marks it).

**Deep-sky objects.** All 109 Messier and 109 Caldwell objects are always drawn, labelled (M31, C14) within ~60 degrees
of your gaze; the ~7,100 other NGC objects fade in by magnitude (about 270 at 1x, more as you zoom; NGC labels appear from 3x). Symbols by type:
ellipse = galaxy, dashed circle = open cluster, circle with a cross = globular, small circle with ticks = planetary nebula,
square = nebula/remnant. Symbol size follows the object's real size and grows with the zoom.

**Lock and zoom.** Locking pins the object at the spot in your view where you pointed and keeps it there as time passes
(the sky turns around it instead of drifting away). Zoom is an azimuthal-equidistant magnification about that object, so angular distances
from it are multiplied by the zoom factor; the HUD shows `LOCK`, `OVER` and `ZOOM`. The constellation lines are drawn as short arcs so they bend correctly.

Synthetic tiers spawn 16 fainter neighbours around every real star (fixed seed) so the point count
can be pushed well past the real catalog. They are a load test, not sky.

The sky is the **real sky now**: the headset clock plus the stick's offset gives local sidereal time
at the site, and star positions are precessed from J2000 to the date. The HUD shows site name, latitude,
longitude and elevation, UTC, local time (site timezone), LST, and whether time is live or offset.
The dome shows the horizon, 30° and 60° altitude rings, N/E/S/W letters and the Mellinger all-sky Milky Way
map behind the stars. The **ground is opaque**: a dark plane 1.65 m below the session's start eye height with a 2 m grid that fades
into horizon haze (it is rendered with real per-eye parallax, which is what gives the dome its scale). Stars, lines and labels below the horizon are hidden.
The sky is drawn on a 1000-unit dome (10x larger than before) so the lasers' landing point barely moves with your head.

Constellation labels are IAU three-letter abbreviations (And, UMa, CVn …) set in Arial.

## Build and install

Toolchain (installed under `C:\Android`, nothing global): JDK 17, Android SDK 34, NDK 26.3, CMake
3.22.1, Gradle 8.7 wrapper, OpenXR loader 1.1.43 from Maven Central.

```powershell
# one-off: star data from VizieR I/239 (needs astroquery; the Galileo venv has it)
C:\Projects\Galileo\.venv\Scripts\python.exe -I tools\make_stars.py

C:\Projects\Galileo\.venv\Scripts\python.exe -I tools\make_background.py   # Mellinger all-sky from CDS hips2fits
C:\Projects\Galileo\.venv\Scripts\python.exe -I tools\make_font.py         # Arial glyph atlas (needs arial.ttf)
C:\Projects\Galileo\.venv\Scripts\python.exe -I tools\make_dso.py          # Messier/Caldwell/NGC from Galileo's cached OpenNGC catalogue
C:\Projects\Galileo\.venv\Scripts\python.exe -I tools\make_site.py         # observing site from Galileo's DB (name/lat/lon/elev/tz only)

# build, sideload, launch, stream metrics
.\deploy.ps1
```

Headset prerequisites: developer mode on (Meta Horizon phone app → Devices → Developer Mode), USB
cable, accept the "Allow USB debugging" prompt inside the headset. The app appears under
**Library → Unknown Sources**.

`app-release.apk` is native-optimised but signed with the debug key — a measurement build, not a
store build. `com.oculus.supportedDevices` lists Quest 2 and Pro only; add Quest 3 values when needed.

## Not done / known limits

- **Never run on a headset yet.** It builds, and the manifest declares the VR features, but the first
  device run is the first real test. Expect to tune point sizes and brightness on the device.
- No MSAA, fixed foveated rendering, or CPU/GPU performance-level hints, so numbers are the
  unoptimised baseline. Those are the next levers if a tier misses 72 Hz.
- Star brightness/size is a simple magnitude ramp, not a photometric model.
- No Sun, Moon or planets yet, so "the sky as it is" is the fixed stars and the DSS2 backdrop.
- Background is the Mellinger colour optical survey (smooth, artifact-free). DSS2 colour was tried first and
  rejected: it is a patchwork of plates with a different background per tile. It is still available with
  `make_background.py --hips CDS/P/DSS2/color --flatten`.
- Licences: Mellinger's panorama is published for non-commercial use with credit (check before anything
  public); DSS2 is free for research/education with acknowledgement (STScI); Arial is Monotype's - keep
  `fontarial.bin` out of anything redistributed (`make_font.py --font` takes Liberation Sans / Arimo);
  the constellation figures are KStars GPL-2 data.
- Deep-sky data is OpenNGC (CC-BY-SA 4.0), via Galileo's cache (open the Star Atlas once, or run Galileo, to create it).
- Zoom and lock were verified numerically (round-trip, centre pinned, angular scaling) and the shaders compile, but have not been
  run on a headset yet. Zoom magnifies the sky layers only; the horizon rings, compass and ground stay put.
- Atmosphere is not modelled: no twilight, extinction or light pollution.
- The virtual hands and lasers have been built and run, but not yet tried with controllers in hand: finger poses
  assume the aim-pose frame (+X right, +Y up, -Z forward) and may want tuning once seen.
