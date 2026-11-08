import GalileoCore
import SwiftUI

/// Where the object menu is: where the object was tapped, or beside a search result, wherever that has come to be.
private enum MenuPlace {
    case at(CGPoint)
    case besideSelection
}

/// The sky above the Observatory, from the Master's data: no catalog here, and no ephemeris (`MAPP-SKY-*`, `MAPP-STR-*`).
struct SkyScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var sky: SkyModel?
    @State private var panelOpen = false
    @State private var menu: MenuPlace?
    @AppStorage("sky.panel.pinned") private var panelPinned = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme

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
        // The sky gets the whole screen unless asked otherwise: the panel slides over it when wanted, and only takes
        // room from it if it's been told to stay.
        let beside = sizeClass == .regular
        let layout = beside ? AnyLayout(HStackLayout(spacing: 0)) : AnyLayout(VStackLayout(spacing: 0))
        ZStack(alignment: beside ? .topTrailing : .bottom) {
            layout {
                picture(sky).frame(minHeight: 280)
                if panelOpen, panelPinned { panel(sky, beside: beside) }
            }
            if panelOpen, !panelPinned {
                panel(sky, beside: beside).transition(.move(edge: beside ? .trailing : .bottom))
            }
        }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: panelOpen)
        .onAppear { if panelPinned { panelOpen = true } }
    }

    private func picture(_ sky: SkyModel) -> some View {
        SkyCanvas(sky: sky) { hit in
            // Touching something asks about it, in a menu beside it. Touching nothing clears the way to the sky.
            menu = hit.map { .at($0) }
            if hit == nil, !panelPinned { panelOpen = false }
        } onMoved: {
            menu = nil
        }
        .overlay { objectMenu(sky) }
        .overlay(alignment: .topTrailing) {
            if !panelOpen {
                Button { panelOpen = true } label: {
                    Image(systemName: "slider.horizontal.3").frame(minWidth: Layout.minimumTarget, minHeight: Layout.minimumTarget)
                }
                .buttonStyle(ActionStyle())
                .fixedSize()
                .padding(8)
                .accessibilityLabel("Search, time and display")
                .accessibilityIdentifier("sky.panel")
            }
        }
        .overlay(alignment: .bottom) { captions(sky) }
    }

    /// Beside the object, on whichever side has the room, and kept on screen.
    @ViewBuilder private func objectMenu(_ sky: SkyModel) -> some View {
        if let menu, sky.selection != nil {
            GeometryReader { geometry in
                let width: CGFloat = 230
                let at = switch menu {
                case .at(let point): point
                case .besideSelection: placeOfSelection(sky, in: geometry.size)
                }
                let inTopHalf = at.y < geometry.size.height / 2
                SkyObjectMenu(sky: sky)
                    .frame(width: width)
                    .padding(.leading, min(max(8, at.x + 14), max(8, geometry.size.width - width - 8)))
                    .padding(.top, inTopHalf ? at.y + 14 : 0)
                    .padding(.bottom, inTopHalf ? 0 : geometry.size.height - at.y + 14)
                    .frame(width: geometry.size.width, height: geometry.size.height, alignment: inTopHalf ? .topLeading : .bottomLeading)
                    .environment(\.compactControls, true)
            }
        }
    }

    /// On screen even if the object isn't: one below the horizon can be out of the view's reach.
    private func placeOfSelection(_ sky: SkyModel, in size: CGSize) -> CGPoint {
        let middle = CGPoint(x: size.width / 2, y: size.height / 2)
        guard let selection = sky.selection, let site = sky.site else { return middle }
        let place = SkyGeometry.horizontal(raDeg: selection.raDeg, decDeg: selection.decDeg, site: site, at: sky.time(now: model.now))
        guard let point = sky.camera.project(altitude: place.altitude, azimuth: place.azimuth, viewHeight: size.height) else { return middle }
        return CGPoint(x: min(size.width, max(0, middle.x + point.x)), y: min(size.height, max(0, middle.y - point.y)))
    }

    /// What must stay visible while the panel is away: that this isn't now, and whose picture it is.
    private func captions(_ sky: SkyModel) -> some View {
        HStack(alignment: .bottom) {
            if sky.timeOffset != 0 {
                Text("Not now: \(sky.time(now: model.now).formatted(date: .abbreviated, time: .shortened))")
                    .fontWeight(.semibold)
                    .padding(6)
                    .background(theme.surface, in: RoundedRectangle(cornerRadius: 6))
                    .accessibilityIdentifier("sky.notnow")
            }
            Spacer(minLength: 8)
            if sky.showSurvey, let credit = sky.survey.credit, !lightPage {
                Text(credit).font(.caption2).foregroundStyle(theme.textSecondary).accessibilityIdentifier("sky.credit")
            }
        }
        .padding(8)
        .allowsHitTesting(false)
    }

    /// The Light theme: the survey isn't drawn on it (`SkyMetalView`), so it needs no credit.
    private var lightPage: Bool { model.theme == .standard && colorScheme == .light }

    private func panel(_ sky: SkyModel, beside: Bool) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                Button(panelPinned ? "Auto-hide" : "Keep open") { panelPinned.toggle() }
                    .buttonStyle(ActionStyle())
                    .accessibilityIdentifier("sky.panel.pin")
                if !panelPinned {
                    Button("Hide") { panelOpen = false }
                        .buttonStyle(ActionStyle())
                        .accessibilityIdentifier("sky.panel.close")
                }
            }
            .padding([.horizontal, .top], 8)
            ScrollView {
                SkyPanel(sky: sky) {
                    menu = .besideSelection
                    if !panelPinned { panelOpen = false }
                }
                .padding(8)
            }
        }
        .environment(\.compactControls, true)
        .frame(width: beside ? 280 : nil)
        .frame(maxWidth: beside ? nil : .infinity, maxHeight: beside ? .infinity : 340)
        .background(theme.background)
    }
}

/// The picture: stars from Metal, and over them everything that needs a label or a shape.
private struct SkyCanvas: View {
    let sky: SkyModel
    /// A tap landed: on an object, here, or on empty sky (`nil`).
    let onTap: (CGPoint?) -> Void
    /// The view has moved, so anything placed beside an object isn't beside it any more.
    let onMoved: () -> Void
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
                    SkyMetalView(
                        stars: sky.stars, starsVersion: sky.starsVersion, camera: sky.camera, site: site, time: moment,
                        survey: sky.showSurvey ? sky.survey : nil, showGround: sky.showGround)
                    overlay(site: site, size: size, moment: moment)
                }
                if !model.snapshot.isConnected {
                    Banner(icon: "wifi.slash", text: "Not live: showing the last sky received").padding(8).padding(.trailing, 60)
                        .accessibilityIdentifier("sky.notlive")
                } else if sky.loading {
                    ProgressView().tint(theme.text).padding(8).frame(maxWidth: .infinity, alignment: .leading)
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
                onMoved()
                sky.camera.pan(deltaX: dx, deltaY: dy, viewHeight: size.height)
            }
            .onEnded { _ in
                lastDrag = .zero
                sky.cameraMoved(now: now)
            }
    }

    private func pinch(now: Date) -> some Gesture {
        MagnifyGesture()
            .onChanged { value in
                onMoved()
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
            sky.camera.project(altitude: horizontal.altitude, azimuth: horizontal.azimuth, viewHeight: size.height),
            size: size)
    }

    private func tap(at location: CGPoint, size: CGSize, moment: Date, now: Date) {
        guard let site = sky.site else { return }
        // Deep-sky objects win over stars: they're what people aim at.
        func nearest(_ objects: [SkyObject], within reach: CGFloat) -> SkyObject? {
            var best: (SkyObject, CGFloat)?
            for object in objects {
                // What the ground hides can't be touched.
                let height = SkyGeometry.horizontal(raDeg: Double(object.raDeg), decDeg: Double(object.decDeg), site: site, at: moment).altitude
                if sky.showGround, height < 0 { continue }
                guard let at = place(ra: Double(object.raDeg), dec: Double(object.decDeg), site: site, size: size, moment: moment) else { continue }
                let distance = hypot(at.x - location.x, at.y - location.y)
                if distance < reach, distance < (best?.1 ?? .infinity) { best = (object, distance) }
            }
            return best?.0
        }
        if let hit = nearest(sky.shownDeepSky, within: 32) ?? nearest(sky.bright, within: 28) {
            onTap(location)
            Task { await sky.tapped(hit, now: now) }
        } else {
            sky.clearSelection()
            onTap(nil)
        }
    }

    private func overlay(site: SkySite, size: CGSize, moment: Date) -> some View {
        let camera = sky.camera
        let shorter = size.height
        let color = theme.text
        let dim = theme.textSecondary
        let live = model.snapshot.isConnected && sky.timeOffset == 0
        let bodies = EphemerisInterpolator.bodies(at: moment, in: sky.samples)
        let pointing = live ? pointingPosition : nil
        let frame = sky.fieldOfView
        let selection = sky.selection
        let deepSky = sky.shownDeepSky
        let figures = sky.showConstellations ? sky.constellations : SkyConstellations()
        let hideBelow = sky.showGround
        return Canvas { context, _ in
            func plot(_ alt: Double, _ az: Double) -> CGPoint? {
                screen(camera.project(altitude: alt, azimuth: az, viewHeight: shorter), size: size)
            }
            func at(ra: Double, dec: Double) -> CGPoint? {
                let h = SkyGeometry.horizontal(raDeg: ra, decDeg: dec, site: site, at: moment)
                return plot(h.altitude, h.azimuth)
            }
            // The sky's own things stop at the ground. What the user is aiming at is marked wherever it is.
            func inSky(ra: Double, dec: Double) -> CGPoint? {
                let h = SkyGeometry.horizontal(raDeg: ra, decDeg: dec, site: site, at: moment)
                return hideBelow && h.altitude < 0 ? nil : plot(h.altitude, h.azimuth)
            }

            // Under everything else: they're for finding your way, not for looking at.
            var outlines = Path()
            for line in figures.lines {
                var previous: CGPoint?
                for point in line {
                    guard let p = inSky(ra: point.raDeg, dec: point.decDeg),
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
                if let p = inSky(ra: name.raDeg, dec: name.decDeg), size.width > 0, CGRect(origin: .zero, size: size).contains(p) {
                    context.draw(Text(name.name).font(.caption2).foregroundStyle(dim.opacity(0.8)), at: p)
                }
            }

            // The horizon profile, and the true horizon under it.
            var skyline = Path()
            var started = false
            for az in stride(from: 0.0, through: 360.0, by: 3.0) {
                guard let p = plot(site.skylineAltitude(azimuth: az), az) else {
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
                guard let p = inSky(ra: Double(object.raDeg), dec: Double(object.decDeg)) else { continue }
                let box = CGRect(x: p.x - 5, y: p.y - 5, width: 10, height: 10)
                context.stroke(Path(ellipseIn: box), with: .color(dim), lineWidth: 1)
            }

            for body in bodies {
                guard let p = inSky(ra: body.raDeg, dec: body.decDeg) else { continue }
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
                    let perDegree = 2 * camera.scale(viewHeight: shorter) * .pi / 180 / 2
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
}

/// What the Star Atlas offers on an object, in the same order (`MAPP-SKY-100`). A Viewer gets the name and the altitude.
private struct SkyObjectMenu: View {
    let sky: SkyModel
    @Environment(AppModel.self) private var model
    @State private var goToMessage: String?

    var body: some View {
        if let selection = sky.selection {
            let args: [String: JSONValue] = ["ra_deg": .number(selection.raDeg), "dec_deg": .number(selection.decDeg)]
            Card(selection.name) {
                if !selection.details.isEmpty { Note(selection.details.joined(separator: " · ")) }
                if let check = selection.check {
                    Text(String(format: "Altitude now: %.1f°", check.altitude)).font(.footnote.weight(.semibold)).accessibilityIdentifier("sky.altitude")
                    if let reason = check.reason { Note(reason) }
                }
                if model.canOperate {
                    Button("Goto") {
                        Task { goToMessage = await model.goTo(name: selection.name, raDeg: selection.raDeg, decDeg: selection.decDeg) }
                    }
                    .buttonStyle(ActionStyle(kind: .prominent))
                    .accessibilityIdentifier("sky.goto")
                    if let goToMessage {
                        Note(goToMessage)
                    } else if let record = model.record(of: "mount.slew") {
                        OutcomeNote(record: record)
                    }
                    OperationButton(
                        title: "Goto and Centre", operation: "solve.centre",
                        args: args.merging(["name": .string(selection.name)]) { first, _ in first })
                    OperationButton(title: "Sync", operation: "mount.sync", args: args)
                    Button("Add to Session") {
                        model.sessionTarget = .init(name: selection.name, raDeg: selection.raDeg, decDeg: selection.decDeg)
                        model.requestedArea = .sessions
                    }
                    .buttonStyle(ActionStyle())
                    .accessibilityIdentifier("sky.addsession")
                }
            }
        }
    }
}

/// Search, time and the selected target.
private struct SkyPanel: View {
    let sky: SkyModel
    /// A search result has been chosen and centred.
    let onPicked: () -> Void
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme

    var body: some View {
        @Bindable var bindable = sky
        VStack(spacing: 8) {
            Card("Find") {
                TextField("", text: $bindable.query, prompt: Text("Name or designation").foregroundStyle(theme.textSecondary))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .foregroundStyle(theme.text)
                    .font(.footnote)
                    .padding(.horizontal, 8)
                    .frame(minHeight: Layout.compactTarget)
                    .background(theme.raised, in: RoundedRectangle(cornerRadius: 7))
                    .accessibilityLabel("Search the sky")
                    .accessibilityIdentifier("sky.search")
                    .onSubmit { Task { await sky.search() } }
                ForEach(sky.results.indices, id: \.self) { index in
                    let object = sky.results[index]
                    Button(object["name"]?.stringValue ?? "Object") {
                        onPicked()
                        Task { await sky.select(object, now: model.now) }
                    }
                    .buttonStyle(ActionStyle())
                    .accessibilityIdentifier("sky.result.\(index)")
                }
            }
            timeCard
            Card("Show") {
                HStack(spacing: 6) {
                    toggle("Sky survey", id: "sky.survey", on: sky.showSurvey) { sky.showSurvey.toggle() }
                    toggle("Ground", id: "sky.ground", on: sky.showGround) { sky.showGround.toggle() }
                }
                toggle("Constellations", id: "sky.constellations", on: sky.showConstellations) { sky.showConstellations.toggle() }
                if sky.showConstellations, sky.constellations.lines.isEmpty {
                    Note("This Master has no constellation figures to send.")
                }
                if sky.showSurvey, sky.survey.credit == nil {
                    Note("Photographs of the sky appear when the Master can fetch them.")
                }
            }
            Card("Catalog") {
                HStack(spacing: 6) {
                    ForEach(SkyModel.catalogs, id: \.self) { name in
                        toggle(name, id: "sky.catalog.\(name)", on: sky.shownCatalogs.contains(name)) {
                            if sky.shownCatalogs.contains(name) {
                                sky.shownCatalogs.remove(name)
                            } else {
                                sky.shownCatalogs.insert(name)
                            }
                        }
                    }
                }
            }
        }
    }

    private func toggle(_ title: String, id: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(title, action: action)
            .buttonStyle(ActionStyle(kind: on ? .prominent : .normal))
            .accessibilityIdentifier(id)
            .accessibilityValue(on ? "On" : "Off")
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
