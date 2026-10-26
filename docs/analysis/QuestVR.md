# Meta Quest Edition — Analysis of VR Opportunities

| | |
|---|---|
| **Document** | Analysis (not a requirements document), v0.4, draft — updated for the decision that Quest 2 is the primary target |
| **Date** | 2026-10-27 |
| **Written for** | The product owner, to decide which Galileo facets the Meta Quest 2/3 edition adapts beyond the two required scenes |
| **Context** | [PSD](../PSD.md) §4a (Meta Quest edition). The immersive **planetarium** (Q-9) and **image viewer** (Q-10) are required; this analysis looks at everything else |
| **Basis** | Galileo's current screens and planning modules (Star Atlas, Framing Assistant, What's Up Tonight, Dashboard timelines, Imaging, Live Stack, Guiding, Focus, Polar Align, Solve, Sessions, Schedule, Library, History, Safety, plugins such as AllSkyCam and VSTarget), and the operating scope of the MobileConnect plugin PSD §6.4 |

---

## 1. What VR is actually good for

A headset is a poor place to type or fine-tune a number, and a very good place to do four things. The assessment of every facet below is made against these:

| Strength | Why it matters here |
|---|---|
| **Spherical space** | The sky *is* a sphere with the viewer at its centre. A flat planetarium has to project it; a headset can simply show it |
| **Scale** | A wall-sized image, or a dozen panels, with no monitor size limit |
| **Presence and attention** | Dark surroundings, no stray light, nothing else on screen — good for judging a faint image or a subtle trend |
| **Spatial memory** | Things placed around you (each Pier's timeline, each safety input) are found by turning your head, not by navigating menus |

Its weaknesses define what *not* to adapt: text entry, long lists, dense forms, precise sliders, and anything done while moving. Comfort limits (sessions of an hour or two; battery) mean the headset is a **place you go to look and decide**, not an all-night console — the panels and the tablet app remain the everyday tool.

## 2. Summary: facets ranked

Value = how much VR improves the task over the tablet. Effort is relative (S/M/L/XL). **Data** is what the Master must supply beyond what the Android app already needs (Section 4).

| # | Facet (Galileo area) | VR adaptation | Value | Effort | Phase |
|---|---|---|---|---|---|
| 1 | **Planetarium** (Star Atlas) | Full dome around the user; horizon from the Observatory profile; live mount pointing; time scrub; look-and-select, confirm-and-slew | Very high | L | **Required, v1** |
| 2 | **Image viewer** (Imaging, Live Stack, Library) | Wall-sized display; pan/zoom; stretch; blink or side-by-side compare; frame-by-frame subframe comparison | Very high | L | **Required, v1** |
| 3 | **Framing Assistant** (Framing, mosaics) | Place the sensor rectangle on the dome with a controller: grab, rotate, size to the optical train, lay out mosaic panels, see overlap against the real sky | Very high | M (on #1) | v1.1 |
| 4 | **What's Up Tonight / visibility** | Altitude arcs for the night drawn across the dome; ranked targets as markers; scrub the night and watch targets rise, culminate and set against the horizon profile; the Moon and its glow | High | M (on #1) | v1.1 |
| 5 | **Observatory overview** (Dashboard timelines, multiple Piers) | One floating timeline per Pier arranged in an arc; spoke nodes at other sites reached through the Master as extra panels; glance to see which Pier is guiding badly | High | M | v1.1 |
| 6 | **All-sky camera** (AllSkyCam plugin) | The live all-sky image mapped onto the dome, overlaid on the computed sky: clouds visibly drifting over the target | High | M (on #1; needs the plugin's image) | v1.2 |
| 7 | **Safety and weather** | Ambient status: a coloured horizon ring / sky tint for safe, caution, unsafe; each input as a small object in view; acknowledge by pointing | Medium–high | S | v1.1 |
| 8 | **Equipment twin** (mount, dome, tube) | A 3D model of the mount, tube and dome showing live pointing, dome slit alignment, and a **meridian flip** played out as it happens; collision/cable-wrap warnings are a later possibility | Medium–high | L | v1.2 |
| 9 | **Session and schedule** (Sessions, Schedule canvas) | Timeline laid out spatially: the night as an arc, jobs as segments over their targets' altitude curves; run/pause by controller. Block *authoring* stays on the panel | Medium | M | v2 |
| 10 | **Guiding and focus** (Guider plots, Autofocus V-curve) | The guide scatter and error trace as large floating plots; V-curve as a 3D ridge; useful for diagnosis, not for operation | Medium | S–M | v1.2 |
| 11 | **Polar alignment and plate solving** | The solved field overlaid on the dome (where the mount actually points vs the target); the polar-axis error as an arrow at the pole | Medium | M (on #1) | v1.2 |
| 12 | **Library and history** | A "frame wall" of a night's subs; quality metrics as height or colour; reject by pointing; session history as a gallery | Medium | M (on #2) | v2 |
| 13 | **Solar-system objects** (minor bodies, planets, Moon) | Show moving objects with their paths across the dome; comets and asteroids as tracks | Medium | S (on #1) | v1.1 |
| 14 | **Variable-star work** (VSTarget plugin) | The target field with comparison stars marked; light curves floating beside it | Low–medium | M | v2 |
| 15 | **Outreach / shared viewing** | A guest in the Viewer role in their own headset sees the same dome and the same live image | Medium (new) | L | v2+ |
| 16 | **Immersive 3D "depth" of space** | Distances from parallax: stars and nearby galaxies placed at their real distance, a fly-through for education. Not an operating tool | Low (for operating) | L | Out of scope unless wanted |

## 3. Notes on the main candidates

### 3.1 Planetarium and Framing (the strongest pair)
These are the same scene. Once the dome exists, framing is a *tool inside it* rather than a second screen: the sensor rectangle is the same object the desktop's Framing Assistant draws, but here it can be taken in the hand and turned. Mosaic tiles, overlap and rotation are naturally spatial. Select-to-go-to is the highest-risk action (it moves the mount); it needs an Operator role, the lease, a confirmation panel showing target, altitude and horizon check, and nothing happens on a hover or a stray click.

### 3.2 Image viewer
The advantage is judging faint structure, gradients and star shape at large size in a dark room, and comparing frames. The headset can *show* far more than the Master should *send*: the Master delivers a tiled, scaled preview pyramid (MobileConnect plugin C4), not FITS, and the headset requests only the visible tiles. Stretch is applied on the node (so statistics are consistent) or on the headset for interactive tweaks; this is an SDD decision.

### 3.3 Observatory overview and safety
These cost little and give the best everyday value *after* the two required scenes, because they reuse the panel work and the existing event stream. The ambient safety cue is worth singling out: a sense of "something is wrong" from the periphery is something a tablet cannot give.

### 3.4 Equipment twin
A convincing mount model needs per-equipment geometry that Galileo's equipment profiles don't hold today (a tube is a length and diameter; a mount is a type). A generic mount/tube/dome that follows the true pointing, dome azimuth and flip state is feasible from the telemetry the Master already has; accurate collision detection is not, without new profile data, and should not be implied. The meridian-flip visualisation is the useful part.

### 3.5 What to leave on the panels
Equipment connection, exposure parameters, numeric settings, session block authoring and schedule editing are forms-and-lists work. They stay on the floating panels (the tablet layout) and are reached from the immersive scenes, not rebuilt in 3D.

## 4. What the Master must supply

**Decided (PSD Q-13): the Master streams; the headset renders.** The tables below are therefore the list of streams the Master offers rather than options for local data. Nothing here is rendered video: the Master sends data and the headset draws it, so head movement stays smooth even when the link is slow. Because this puts real work on the Master, **VR is served only by a Master on PC/Mac-class hardware** (PSD Q-14); that is an accepted minimum, and Raspberry Pi nodes remain usable as spokes behind such a Master. It also means the Master can afford richer streams than a Pi could: finer catalog levels of detail, server-computed ephemeris series and tile pyramids generated on demand.

The immersive scenes change the data requirements more than the feature list does.

| Need | Used by | Source today | Gap |
|---|---|---|---|
| **Star and deep-sky catalog** for rendering | Planetarium, framing | Galileo's bundled sky-atlas catalog database | **Streamed** by sky region, limiting magnitude and level of detail as the user looks; bright stars first, fainter ones as they arrive. A short-lived cache covers dropouts; no catalog is stored on the headset |
| **Ephemeris** (Sun, Moon, planets, minor bodies) | Planetarium, solar-system objects | Computed in the Master (`galileo.planning.visibility`, `minor_bodies`) | **Streamed**: positions for the displayed time and, for scrubbing, a time series fetched ahead of the scrub. The headset does no ephemeris computation |
| **Observatory location, horizon profile** | Dome, visibility | Master (`SKY-040`) | Exposed read-only through the Control Surface |
| **Live pointing, FOV, optical train, rotation** | Planetarium, framing, twin | Master (mount, profile) | Event stream; no new data |
| **Tiled preview pyramid** | Image viewer | Planned (plugin C4) | Tiling and scaling support in the node's preview renderer |
| **All-sky image** | All-sky overlay | AllSkyCam plugin on the Master | A route to relay the latest image when the plugin is present |
| **Equipment geometry** | Twin | Not held | New optional profile fields, or a generic model |
| **Ranked targets, altitude curves** | What's Up Tonight view | Master (`WUT-*`) | Control Surface route |

None of these changes the principle that the headset operates, never configures: all are reads, and every action still goes through the Control Surface.

## 5. Platform and technical considerations

- **Rendering stack** (PSD Q6). The dome and image wall need real-time 3D. A native Android route (Meta's Android SDK, Kotlin) keeps the shared Kotlin layer and the Compose panel screens in one app and one language; a game engine (Unity) eases heavy 3D and tooling at the cost of a second runtime and a bridge to the shared layer. Recommend a short spike of the dome with 100,000 stars on a Quest 2 before deciding. The star count, not the UI, is the performance risk.
- **Quest 2 is the target; Quest 3 follows by demand (PSD Q-12).** Quest 2's GPU and memory are the design budget, not a limit to apologise for: level-of-detail by magnitude, point-sprite stars, a bounded tile cache and a modest scene count are requirements from the start, and the same build then runs well on a Quest 3. Quest 3-specific features (colour passthrough, mixed-reality anchoring of the sky to the room, higher-resolution textures) are only worth building once Quest 2 use justifies them. Because the Quest 2 is cheap and available second-hand, it is realistic to plan for a **dedicated astronomy headset**, so first-run setup, pairing and reconnection should be quick and need no other apps.
- **Dark adaptation.** The red-only palette (PSD G10) applies to the 3D scenes, and so do dim defaults: a bright sky texture in an otherwise dark room defeats the purpose. Star colour is lost in red-only mode, so the dome needs a mode that keeps stars legible without white.
- **Comfort.** No camera motion not initiated by the user: the dome is fixed around them, time scrubbing moves the *sky*, not the viewer, and the horizon stays level. Short, seated sessions; text kept large (PSD PC10).
- **Input.** Controllers are precise for placing a framing rectangle; hand tracking is acceptable for browsing. Anything that moves hardware needs a deliberate confirmation (and a controller button hold for held motion, PSD Q-5).
- **Wearer safety.** Passthrough one gesture away (PSD Q-6); the immersive scenes should be unavailable until the first-run safety notice is acknowledged.
- **No Google services** (PSD Q-7): relevant to anything that assumed Maps, Firebase or Play-delivered assets.
- **Connectivity.** Headsets sleep when taken off. The scenes must resynchronise on resume, as the other apps do (PSD PC4); a headset removed mid-session must change nothing on the node.

## 6. Risks specific to the immersive scenes

| # | Risk | Mitigation |
|---|---|---|
| R1 | Quest 2, the primary target, cannot render a large catalog smoothly | The Quest 2 budget is the design budget: level-of-detail by magnitude, point sprites, bounded caches; spike first on a real Quest 2 |
| R2 | A mis-selected go-to slews the mount toward an obstruction | Operator role, lease, confirmation panel with horizon/limit check; the Master's safety and mount limits remain the authority |
| R3 | Scope creep: the "wow" features (flythroughs, 3D space) crowd out operating value | Section 2 ranking; only items tied to an operating task are scheduled before v2 |
| R4 | The Master's streaming load (several viewers, imaging at the same time) or a weak link starves the scenes or the session | VR is served only by a PC/Mac-class Master (PSD Q-14); per-client limits, compact delta-coded binary protocol, streaming yields to the session, progressive detail, short-lived local cache |
| R5 | Image tiles over a weak link make the viewer sluggish | Progressive tiles (coarse first); local cache; the same budget as the other apps |
| R6 | The equipment twin implies safety information (collision) it cannot guarantee | Label it a visualisation; no collision claims without real geometry |
| R8 | Quest 2 passthrough is low-resolution and monochrome, a weaker safety aid than Quest 3's | Do not rely on it alone; seated/indoor guidance and the first-run gate |
| R7 | Using the headset in the dark at the telescope is a trip hazard | Seated/indoor guidance, passthrough, first-run gate |

## 7. Recommendation

1. **v1:** the immersive planetarium and the image viewer, with the panel mode for everything operational (as already scoped), plus the safety ring and the Pier timelines, since they are inexpensive reuse.
2. **v1.1:** framing on the dome, What's Up Tonight on the dome (altitude arcs and time scrub), solar-system objects.
3. **v1.2:** all-sky overlay, the equipment twin with flip visualisation, guiding/focus diagnostics, plate-solve and polar-alignment overlays.
4. **v2:** spatial schedule, Library frame wall, variable-star views, shared viewing.
5. Treat the Quest 2 as the acceptance device throughout; plan Quest 3-specific features (colour passthrough, mixed-reality, higher resolution) only after Quest 2 utilisation justifies them.
6. Decide the rendering stack from a Quest 2 spike **before** writing the Quest SDD; the streaming protocol for the data in Section 4 (PSD Q-13) belongs in that SDD and drives the wire-API additions Galileo needs.

## 8. Questions for the owner

| # | Question |
|---|---|
| Q1 | *Decided:* Quest 2 is the primary target and the design budget; Quest 3 support follows by demand. Open: how is "utilisation" measured given there are no in-app analytics (store install figures and user feedback are the proposal)? |
| Q2 | *Decided:* stream as much as possible from the Master (PSD Q-13); nothing is shipped on the headset except the app, and no catalog or ephemeris is computed there. Open: the cache policy (PSD Q9) |
| Q3 | Is shared viewing for outreach (a guest in their own headset) of interest, or out of scope for a personal-observatory product? |
| Q4 | Would the equipment twin justify adding optional geometry fields to the equipment profiles, or should it stay generic? |
