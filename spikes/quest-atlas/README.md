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
| Left grip held + B | Sun / Moon / planets / asteroids / satellites on/off |
| X | back to now (zero the time offset) |
| Y | constellation outlines and labels on/off |
| Left grip held + X | Messier / Caldwell / NGC layer on/off |
| Left grip held + Y | cycle display refresh rate (starts at 72 Hz; the runtime offers 60/72/80/90/120) |
| Left stick click | all-sky background on/off |
| Left menu | show/hide the diagnostic HUD (hidden by default) |
| Trigger / grip / thumb-touch | curl the virtual index finger / other fingers / thumb |

Each controller is drawn as a **virtual hand** (procedural, no model files) posed from the controller and
posed fingers following the trigger, grip and thumb touch. A **laser pointer** runs from the right controller to the
dome, ending in a ring. The HUD reads out what the right controller is
pointing at: altitude/azimuth, J2000 RA/Dec, the nearest constellation centre, and the deep-sky object under
the beam (a grey ring marks it).

**Deep-sky objects.** All 109 Messier and 109 Caldwell objects are always drawn, labelled (M31, C14) within ~60 degrees
of your gaze; the ~7,100 other NGC objects fade in by magnitude (about 270 at 1x, more as you zoom; NGC labels appear from 3x). Symbols by type:
ellipse = galaxy, dashed circle = open cluster, circle with a cross = globular, small circle with ticks = planetary nebula,
square = nebula/remnant. Symbol size follows the object's real size and grows with the zoom.

**DSS2 pictures.** Zoom in on any Messier, Caldwell or NGC object (all 7,366 have one) and a DSS2 survey image fades in over the
sky. It is not a flat sticker: the shader runs every displayed ray back through the zoom, the sky rotation and precession to
J2000 and projects it onto the object's tangent plane, so the picture is oriented by the sky itself (north up, east left at
the object, rotating with the view as the sky turns), its stars sit exactly under the catalogue stars, and it carries no frame:
the plate's own sky level is subtracted and what is left is added to the background, with a radial fade at the edge. It fades in
as the object's displayed size passes ~2.5 to 5 degrees (M31 at ~2x, a small planetary nebula at ~20x+); with nothing locked
it is the pictured object nearest your gaze.

Resolution fits the object: the zoom stops at 40x and the Quest 2 shows ~20 pixels per degree, so 800 px/deg (4.5"/px) is the most
the screen can use. Small objects (7,098) need only 256 px; larger ones get 512 (180), 1024 (58) or 2048 px (30, e.g. M31). The
field is 2.4x the object's size (0.2 to 6 degrees). Pictures are grayscale JPEGs (125 MB for all) decoded by the NDK's
`AImageDecoder` on a worker thread, so nothing stalls a frame: the coarse 256 px picture shows as soon as the object is
hovered or locked (it is decoded then, before any zoom), and the fine one swaps in when it has decoded. Only one picture is
in memory at a time, however many objects there are.

**Controller models.** Each hand is drawn with the runtime's own Touch controller model (`XR_FB_render_model`, loaded as a GLB
and flattened to coloured triangles in grip space; needs both the `com.oculus.permission.RENDER_MODEL` permission and the `com.oculus.feature.RENDER_MODEL` uses-feature in the manifest; without the feature the runtime hides the extension). Meta's
textures are KTX2 and are not decoded, so the models use material colours without texture detail. If the extension, the
permission or the model is missing, or the GLB cannot be parsed, the procedural hands are drawn instead; `adb logcat | findstr
"render model"` shows which happened, with the model's bounds so a mis-aligned model is easy to spot.

**Music.** Every `.mp3` (also `.mp4` / `.m4a`) in `app/src/main/assets/music/` plays in a random order while the app runs (a shuffle that plays
each track once before repeating, and never repeats the track that just ended). Drop your files in and rebuild; they are
gitignored. Decoding is Java-free (NDK `AMediaExtractor` + `AMediaCodec`, output through AAudio) on its own thread, volume is
`kVolume` in `music.h` (0.6), and it pauses when the headset is taken off. Needs an audio track the headset can decode
(MP3 and AAC both are). `adb logcat -s GalileoMusic` shows what was found and played.

**Read aloud.** With an object's panel open, the **right grip (second trigger)** reads it aloud in a female voice; press again
to stop. The Quest has no text-to-speech engine and the app has no Java, so the speech is pre-rendered on the PC
(`tools/make_speech.py`: Windows "Microsoft Zira" voice, ffmpeg to 12 kbps mono Opus/Ogg, ~300 MB for all 7,375 objects) and played from
`assets/speech/`. The clip says the panel's text: name, what it is, constellation, designations, magnitude, size and
description; altitude/azimuth change with the sky so they are not read. The music is ducked to 25% while it speaks, and
changing or releasing the object, or taking the headset off, stops it.

**Object panel.** Locking an object pops up a small card on the right controller: its bottom edge runs along the pointer axis and it faces your head, so turning your palm towards you reads it like a wrist display. It shows name and common name, type and constellation, other designations, magnitude and size, RA/Dec, current altitude/azimuth, a one-line summary built from the full OpenNGC table for every object (galaxy type, distance from redshift or parallax, physical size), and the opening of the Wikipedia article for the 3,032 objects that have one. The card is a fixed 11 cm window; **right stick Y scrolls it** (down reads on) while it is open (the stick sets the star magnitude limit otherwise), with a bar on its right edge showing the position. It shrinks away on release. The Sun, Moon and planets have a short description; asteroids and satellites just the numbers.

**Solar system.** The Sun, Moon, the seven planets, the 250 brightest asteroids and 70 bright satellites (ISS, Tiangong, the
CelesTrak "visual" group) move with the simulated time, so the left stick runs them forward and back like the stars. They can be
pointed at, locked and zoomed like any deep-sky object.

| Body | How | Accuracy (checked against astropy / JPL Horizons) |
|---|---|---|
| Planets, Sun | JPL approximate Keplerian elements, on the headset | under 5 arcmin |
| Moon | Schlyter perturbed elements, **topocentric** (parallax up to a degree), disc brightness follows the phase | under 5 arcmin |
| Asteroids | osculating elements from JPL SBDB, plain Kepler propagation, H-G magnitudes; labelled from 2x | tens of arcmin a year from the element epoch |
| Satellites | SGP4 baked on the PC into 5-minute position/velocity samples, Hermite-interpolated, topocentric; shown faint while in Earth's shadow | tens of arcsec over the TLE's first days, degrading over two weeks |

Symbols: filled disc = Sun / Moon, soft dot = planet, plus = asteroid, filled square = satellite. Asteroids obey the same
magnitude limit as NGC objects (about 9.5 at 1x, fainter as you zoom).

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
The sky is drawn on a 1000-unit dome (10x larger than before) so the laser's landing point barely moves with your head.

Constellation labels are IAU three-letter abbreviations (And, UMa, CVn …) set in Arial.

## Build and install

Toolchain (installed under `C:\Android`, nothing global): JDK 17, Android SDK 34, NDK 26.3, CMake
3.22.1, Gradle 8.7 wrapper, OpenXR loader 1.1.43 from Maven Central.

```powershell
# one-off: star data from VizieR I/239 (needs astroquery; the Galileo venv has it)
C:\Projects\Galileo\.venv\Scripts\python.exe -I tools\make_stars.py

C:\Projects\Galileo\.venv\Scripts\python.exe -I tools\make_background.py   # Mellinger all-sky from CDS hips2fits
C:\Projects\Galileo\.venv\Scripts\python.exe -I tools\make_font.py         # Arial glyph atlas (needs arial.ttf)
C:\Projects\Galileo\.venv\Scripts\python.exe -I tools\make_bodies.py       # asteroid elements (JPL SBDB) + satellite tracks (CelesTrak, SGP4) - REBAKE every week or two
C:\Projects\Galileo\.venv\Scripts\python.exe -I tools\make_dso.py          # Messier/Caldwell/NGC from Galileo's cached OpenNGC catalogue
C:\Projects\Galileo\.venv\Scripts\python.exe -I tools\make_dss2.py         # DSS2 pictures for all 7,366 objects, adaptive resolution (NASA SkyView; ~1 hour, 125 MB)
C:\Projects\Galileo\.venv\Scripts\python.exe -I tools\make_info.py         # panel text for every object: names, constellation, OpenNGC facts, Wikipedia (~1 hour; needs tools\.openngc_*.csv)
C:\Projects\Galileo\.venv\Scripts\python.exe -I tools\make_speech.py       # spoken clips for every object (Windows Zira voice + ffmpeg/Opus; ~10 minutes, 300 MB; run after make_info.py)
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

- **Not yet run on a headset with the zoom, DSS2 pictures or panel.** It builds, and the manifest declares the VR features, but the first
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
- DSS2 cutouts come from NASA SkyView (STScI POSS-II plates, research/education use) and the panel text from Wikipedia (CC BY-SA 4.0); both are baked, gitignored and not for redistribution. Deep-sky data is OpenNGC (CC-BY-SA 4.0), via Galileo's cache (open the Star Atlas once, or run Galileo, to create it).
- Zoom and lock were verified numerically (round-trip, centre pinned, angular scaling) and the shaders compile, but have not been
  run on a headset yet. Zoom magnifies the sky layers only; the horizon rings, compass and ground stay put.
- **Satellite tracks are a 14-day window** (1 day back, 13 ahead of the day `make_bodies.py` ran); outside it the satellites
  vanish, and their accuracy decays with TLE age. Asteroid elements also age. Rerun `tools/make_bodies.py` before a session.
  `tools/test_ephem.py` rebuilds `tools/test_ephem.cpp` with MSVC and compares `ephem.h` to astropy and JPL Horizons.
- No comets, no Saturn rings or moons, no light-time or aberration (a few arcsec), satellite magnitudes are nominal
  (stations 0, others 3.5), not phase- or range-dependent.
- Atmosphere is not modelled: no twilight, extinction or light pollution.
- The virtual hands and lasers have been built and run, but not yet tried with controllers in hand: finger poses
  assume the aim-pose frame (+X right, +Y up, -Z forward) and may want tuning once seen.
