import GalileoCore
import SwiftUI

/// The sky above the Observatory, from the Master's data: no catalog here, and no ephemeris (`MAPP-SKY-*`, `MAPP-STR-*`).
struct SkyScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var sky: SkyModel?

    var body: some View {
        Group {
            if let reason = unavailable {
                ScreenScroll("Sky") { Card { Text(reason) } }
            } else if let sky, sky.siteFailed {
                ScreenScroll("Sky") {
                    Card {
                        Text("The sky data couldn't be fetched from the Master.")
                        Note("Galileo may need updating, or the link may have dropped.")
                        Button("Try again") {
                            Task { await sky.start(now: Date(), rate: model.snapshot.capabilities?.skyStreamRate) }
                        }
                        .buttonStyle(ActionStyle(kind: .prominent))
                        .accessibilityIdentifier("sky.retry")
                    }
                }
            } else if let sky {
                content(sky)
            } else {
                ScreenScroll("Sky") { ProgressView().tint(theme.text) }
            }
        }
        .background(theme.background)
        .task(id: model.workingPier) {
            await sky?.stop()
            sky = model.makeSkyModel()
            await sky?.start(now: Date(), rate: model.snapshot.capabilities?.skyStreamRate)
        }
        .onDisappear {
            // Nobody is looking: don't keep asking the Master.
            let leaving = sky
            Task { await leaving?.stop() }
        }
    }

    /// `MAPP-CON-090`: a Master that can't do this says why, and it isn't an error.
    private var unavailable: String? {
        if model.snapshot.capabilities?.skyStreamAvailable == false {
            let reason = model.snapshot.capabilities?.skyStreamReason
            return reason == "master_hardware"
                ? "This Master's computer isn't fast enough to stream the sky."
                : reason ?? "This Master doesn't offer the sky view."
        }
        if sky?.siteMissing == true { return "This Observatory has no location set. Set one on the desktop to see its sky." }
        return nil
    }

    @ViewBuilder private func content(_ sky: SkyModel) -> some View {
        if sizeClass == .regular {
            HStack(spacing: 0) {
                SkyCanvas(sky: sky)
                ScrollView { SkyPanel(sky: sky).padding(Layout.spacing) }
                    .frame(width: 340)
            }
        } else {
            VStack(spacing: 0) {
                SkyCanvas(sky: sky).frame(minHeight: 280)
                ScrollView { SkyPanel(sky: sky).padding(Layout.spacing) }
                    .frame(maxHeight: 340)
            }
        }
    }
}

/// The picture: stars from Metal, and over them everything that needs a label or a shape.
private struct SkyCanvas: View {
    let sky: SkyModel
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @State private var pinchStart: Double?
    @State private var lastDrag: CGSize = .zero

    var body: some View {
        GeometryReader { geometry in
            let size = geometry.size
            let now = model.now
            let moment = sky.time(now: now)
            ZStack(alignment: .top) {
                if let site = sky.site {
                    SkyMetalView(stars: sky.stars, starsVersion: sky.starsVersion, camera: sky.camera, site: site, time: moment)
                    overlay(site: site, size: size, moment: moment)
                }
                if !model.snapshot.isConnected {
                    Banner(icon: "wifi.slash", text: "Not live: showing the last sky received").padding(8)
                        .accessibilityIdentifier("sky.notlive")
                } else if sky.loading {
                    ProgressView().tint(theme.text).padding(8).frame(maxWidth: .infinity, alignment: .trailing)
                }
            }
            .contentShape(Rectangle())
            .gesture(drag(size: size, now: now))
            .simultaneousGesture(pinch(now: now))
            .simultaneousGesture(
                SpatialTapGesture().onEnded { value in tap(at: value.location, size: size, moment: moment, now: now) }
            )
        }
        .clipped()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Sky view")
        .accessibilityValue("Looking at altitude \(Int(sky.camera.altitude)), azimuth \(Int(sky.camera.azimuth)). Field \(Int(sky.camera.fieldOfView)) degrees.")
        .accessibilityHint("Use the search and time controls beside it.")
        .accessibilityIdentifier("sky.view")
    }

    private func drag(size: CGSize, now: Date) -> some Gesture {
        DragGesture(minimumDistance: 4)
            .onChanged { value in
                let dx = value.translation.width - lastDrag.width
                let dy = value.translation.height - lastDrag.height
                lastDrag = value.translation
                sky.camera.pan(deltaX: dx, deltaY: dy, viewShorterSide: min(size.width, size.height))
            }
            .onEnded { _ in
                lastDrag = .zero
                sky.cameraMoved(now: now)
            }
    }

    private func pinch(now: Date) -> some Gesture {
        MagnifyGesture()
            .onChanged { value in
                let start = pinchStart ?? sky.camera.fieldOfView
                pinchStart = start
                sky.camera.fieldOfView = start
                sky.camera.zoom(by: value.magnification)
            }
            .onEnded { _ in
                pinchStart = nil
                sky.cameraMoved(now: now)
            }
    }

    private func screen(_ point: CGPoint?, size: CGSize) -> CGPoint? {
        point.map { CGPoint(x: size.width / 2 + $0.x, y: size.height / 2 - $0.y) }
    }

    private func place(ra: Double, dec: Double, site: SkySite, size: CGSize, moment: Date) -> CGPoint? {
        let horizontal = SkyGeometry.horizontal(raDeg: ra, decDeg: dec, site: site, at: moment)
        return screen(
            sky.camera.project(altitude: horizontal.altitude, azimuth: horizontal.azimuth, viewShorterSide: min(size.width, size.height)),
            size: size)
    }

    private func tap(at location: CGPoint, size: CGSize, moment: Date, now: Date) {
        guard let site = sky.site else { return }
        // Deep-sky objects win over stars: they're what people aim at.
        func nearest(_ objects: [SkyObject], within reach: CGFloat) -> SkyObject? {
            var best: (SkyObject, CGFloat)?
            for object in objects {
                guard let at = place(ra: Double(object.raDeg), dec: Double(object.decDeg), site: site, size: size, moment: moment) else { continue }
                let distance = hypot(at.x - location.x, at.y - location.y)
                if distance < reach, distance < (best?.1 ?? .infinity) { best = (object, distance) }
            }
            return best?.0
        }
        if let hit = nearest(sky.deepSky, within: 32) ?? nearest(sky.bright, within: 28) {
            Task { await sky.tapped(hit, now: now) }
        } else {
            sky.clearSelection()
        }
    }

    private func overlay(site: SkySite, size: CGSize, moment: Date) -> some View {
        let camera = sky.camera
        let shorter = min(size.width, size.height)
        let color = theme.text
        let dim = theme.textSecondary
        let live = model.snapshot.isConnected && sky.timeOffset == 0
        let bodies = EphemerisInterpolator.bodies(at: moment, in: sky.samples)
        let pointing = live ? pointingPosition : nil
        let frame = sky.fieldOfView
        let selection = sky.selection
        let deepSky = sky.deepSky
        let figures = sky.showConstellations ? sky.constellations : SkyConstellations()
        return Canvas { context, _ in
            func plot(_ alt: Double, _ az: Double) -> CGPoint? {
                screen(camera.project(altitude: alt, azimuth: az, viewShorterSide: shorter), size: size)
            }
            func at(ra: Double, dec: Double) -> CGPoint? {
                let h = SkyGeometry.horizontal(raDeg: ra, decDeg: dec, site: site, at: moment)
                return plot(h.altitude, h.azimuth)
            }

            // Under everything else: they're for finding your way, not for looking at.
            var outlines = Path()
            for line in figures.lines {
                var previous: CGPoint?
                for point in line {
                    guard let p = at(ra: point.raDeg, dec: point.decDeg),
                        p.x > -size.width, p.x < size.width * 2, p.y > -size.height, p.y < size.height * 2
                    else {
                        previous = nil
                        continue
                    }
                    if previous != nil { outlines.addLine(to: p) } else { outlines.move(to: p) }
                    previous = p
                }
            }
            context.stroke(outlines, with: .color(dim.opacity(0.45)), lineWidth: 1)
            for name in figures.names {
                if let p = at(ra: name.raDeg, dec: name.decDeg), size.width > 0, CGRect(origin: .zero, size: size).contains(p) {
                    context.draw(Text(name.name).font(.caption2).foregroundStyle(dim.opacity(0.8)), at: p)
                }
            }

            // The horizon profile, and the true horizon under it.
            var skyline = Path()
            var started = false
            for az in stride(from: 0.0, through: 360.0, by: 3.0) {
                guard let p = plot(skylineHeight(site.horizon, azimuth: az), az) else {
                    started = false
                    continue
                }
                if started { skyline.addLine(to: p) } else { skyline.move(to: p) }
                started = true
            }
            context.stroke(skyline, with: .color(color), lineWidth: 1.5)
            for (name, az) in [("N", 0.0), ("E", 90.0), ("S", 180.0), ("W", 270.0)] {
                if let p = plot(0, az) {
                    context.draw(Text(name).font(.caption.bold()).foregroundStyle(color), at: CGPoint(x: p.x, y: p.y + 12))
                }
            }

            for object in deepSky {
                guard let p = at(ra: Double(object.raDeg), dec: Double(object.decDeg)) else { continue }
                let box = CGRect(x: p.x - 5, y: p.y - 5, width: 10, height: 10)
                context.stroke(Path(ellipseIn: box), with: .color(dim), lineWidth: 1)
            }

            for body in bodies {
                guard let p = at(ra: body.raDeg, dec: body.decDeg) else { continue }
                let box = CGRect(x: p.x - 6, y: p.y - 6, width: 12, height: 12)
                context.fill(Path(ellipseIn: box), with: .color(color))
                context.draw(Text(body.name).font(.caption2).foregroundStyle(color), at: CGPoint(x: p.x, y: p.y + 14))
            }

            if let selection, let p = at(ra: selection.raDeg, dec: selection.decDeg) {
                let box = CGRect(x: p.x - 14, y: p.y - 14, width: 28, height: 28)
                context.stroke(Path(ellipseIn: box), with: .color(color), style: StrokeStyle(lineWidth: 2, dash: [4, 3]))
                context.draw(Text(selection.name).font(.caption.bold()).foregroundStyle(color), at: CGPoint(x: p.x, y: p.y - 24))
            }

            // Hidden, never frozen, when not live (`MAPP-STR-060`).
            if let pointing, let p = at(ra: pointing.ra, dec: pointing.dec) {
                var cross = Path()
                cross.move(to: CGPoint(x: p.x - 12, y: p.y))
                cross.addLine(to: CGPoint(x: p.x + 12, y: p.y))
                cross.move(to: CGPoint(x: p.x, y: p.y - 12))
                cross.addLine(to: CGPoint(x: p.x, y: p.y + 12))
                context.stroke(cross, with: .color(color), lineWidth: 2)
                if let frame {
                    let perDegree = 2 * camera.scale(viewShorterSide: shorter) * .pi / 180 / 2
                    let width = max(8, frame.width * perDegree)
                    let height = max(8, frame.height * perDegree)
                    context.stroke(
                        Path(CGRect(x: p.x - width / 2, y: p.y - height / 2, width: width, height: height)), with: .color(color),
                        lineWidth: 1.5)
                }
            }
        }
        .allowsHitTesting(false)
    }

    private var pointingPosition: (ra: Double, dec: Double)? {
        guard let ra = model.value("mount.position.ra_deg")?.value.doubleValue,
            let dec = model.value("mount.position.dec_deg")?.value.doubleValue
        else { return nil }
        return (ra, dec)
    }

    /// For drawing only: the check that decides anything is the Master's.
    private func skylineHeight(_ horizon: [SkySite.HorizonPoint], azimuth: Double) -> Double {
        guard !horizon.isEmpty else { return 0 }
        let points = horizon.sorted { $0.azimuth < $1.azimuth }
        let before = points.last { $0.azimuth <= azimuth } ?? SkySite.HorizonPoint(azimuth: points[points.count - 1].azimuth - 360, altitude: points[points.count - 1].altitude)
        let after = points.first { $0.azimuth > azimuth } ?? SkySite.HorizonPoint(azimuth: points[0].azimuth + 360, altitude: points[0].altitude)
        let span = after.azimuth - before.azimuth
        return span > 0 ? before.altitude + (after.altitude - before.altitude) * (azimuth - before.azimuth) / span : before.altitude
    }
}

/// Search, time and the selected target.
private struct SkyPanel: View {
    let sky: SkyModel
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @State private var goToMessage: String?

    var body: some View {
        @Bindable var bindable = sky
        VStack(spacing: Layout.spacing) {
            Card("Find") {
                TextField("", text: $bindable.query, prompt: Text("Name or designation").foregroundStyle(theme.textSecondary))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .foregroundStyle(theme.text)
                    .padding(.horizontal, 12)
                    .frame(minHeight: Layout.minimumTarget)
                    .background(theme.raised, in: RoundedRectangle(cornerRadius: 8))
                    .accessibilityLabel("Search the sky")
                    .accessibilityIdentifier("sky.search")
                    .onSubmit { Task { await sky.search() } }
                ForEach(sky.results.indices, id: \.self) { index in
                    let object = sky.results[index]
                    Button(object["name"]?.stringValue ?? "Object") {
                        Task { await sky.select(object, now: model.now) }
                    }
                    .buttonStyle(ActionStyle())
                    .accessibilityIdentifier("sky.result.\(index)")
                }
            }
            if let selection = sky.selection { selected(selection) }
            timeCard
            Card("Show") {
                Button {
                    sky.showConstellations.toggle()
                } label: {
                    Label(sky.showConstellations ? "Hide constellations" : "Show constellations", systemImage: "point.3.connected.trianglepath.dotted")
                }
                .buttonStyle(ActionStyle(kind: sky.showConstellations ? .normal : .prominent))
                .accessibilityIdentifier("sky.constellations")
                if sky.showConstellations, sky.constellations.lines.isEmpty {
                    Note("This Master has no constellation figures to send.")
                }
            }
        }
    }

    private func selected(_ selection: SkyModel.Selection) -> some View {
        Card(selection.name) {
            ForEach(selection.details, id: \.self) { Note($0) }
            if let check = selection.check {
                Text(String(format: "Altitude now: %.1f°", check.altitude)).fontWeight(.semibold).accessibilityIdentifier("sky.altitude")
                if let reason = check.reason { Note(reason) }
            }
            if model.canOperate {
                Button {
                    Task { goToMessage = await model.goTo(name: selection.name, raDeg: selection.raDeg, decDeg: selection.decDeg) }
                } label: {
                    Label("Go to", systemImage: "location.fill")
                }
                .buttonStyle(ActionStyle(kind: .prominent))
                .accessibilityIdentifier("sky.goto")
                if let goToMessage {
                    Note(goToMessage)
                } else if let record = model.record(of: "mount.slew") {
                    OutcomeNote(record: record)
                }
            }
        }
    }

    private var timeCard: some View {
        Card("Time") {
            let moment = sky.time(now: model.now)
            Text(moment.formatted(date: .abbreviated, time: .shortened)).fontWeight(.semibold).accessibilityIdentifier("sky.time")
            Note(sky.timeOffset == 0 ? "Now" : "Not now: the mount's position isn't shown.")
            HStack(spacing: 6) {
                step("−1 d", -86400)
                step("−1 h", -3600)
                step("+1 h", 3600)
                step("+1 d", 86400)
            }
            ThemedSlider(
                label: "Through the night",
                value: Binding(
                    get: { max(-12, min(12, sky.timeOffset / 3600)) },
                    set: { change(to: $0 * 3600) }),
                range: -12...12)
            Button("Now") { change(to: 0) }
                .buttonStyle(ActionStyle(kind: sky.timeOffset == 0 ? .normal : .prominent))
                .disabled(sky.timeOffset == 0)
                .accessibilityIdentifier("sky.now")
        }
    }

    private func step(_ title: String, _ seconds: TimeInterval) -> some View {
        Button(title) { change(to: sky.timeOffset + seconds) }.buttonStyle(ActionStyle())
    }

    private func change(to offset: TimeInterval) {
        sky.timeOffset = offset
        let now = model.now
        sky.cameraMoved(now: now)
        Task { await sky.fetchSamples(around: sky.time(now: now)) }
    }
}
