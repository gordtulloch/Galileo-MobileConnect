import XCTest

/// Runs against demonstration mode (`S-MOCK`), through the real screens. Each screen is attached as a
/// screenshot, and `scripts/check_night.py` checks those taken in Night for anything that isn't red.
final class DemoUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
    }

    private func launch(scenario: String = "normal-night", theme: String = "night", surveyFolder: String? = nil) {
        app = XCUIApplication()
        app.launchArguments += ["-GalileoDemo", scenario, "-GalileoTheme", theme]
        if let surveyFolder { app.launchEnvironment["GALILEO_TEST_SURVEY_DIR"] = surveyFolder }
        app.launch()
    }

    private func element(_ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    private func waitForShell() {
        XCTAssertTrue(element("emergency").waitForExistence(timeout: 15), "the shell never appeared")
    }

    private func open(_ area: String) {
        let row = element("area.\(area)")
        // On a tablet the menu hides itself; on a phone the areas are always showing.
        let menu = element("menu")
        if menu.waitForExistence(timeout: 2), !row.exists { menu.tap() }
        XCTAssertTrue(row.waitForExistence(timeout: 5), area)
        // On a phone the areas are a strip that scrolls; a person would scroll it too.
        // A short drag rather than a swipe: a swipe's momentum flies past a button in the middle of the strip.
        let width = app.windows.firstMatch.frame.width
        let strip = app.scrollViews.firstMatch
        for _ in 0..<10 {
            let distance: CGFloat
            if row.frame.maxX > width {
                distance = -min(150, row.frame.maxX - width + 20)
            } else if row.frame.minX < 0 {
                distance = min(150, 20 - row.frame.minX)
            } else {
                break
            }
            let start = strip.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            start.press(forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: distance, dy: 0)))
            usleep(300_000)
        }
        row.tap()
    }

    private func screenshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// `MAPP-CON-110`, `MAPP-OVR-030`, `MAPP-SAF-010`, `MAPP-EMG-010`: every area, in Night, with the demo labelled.
    func testEveryScreenInNight() {
        launch()
        waitForShell()
        XCTAssertTrue(element("demo.banner").exists, "a demo must say so")
        sleep(2)  // let the first deltas arrive
        for area in ["Overview", "Equipment", "Sky", "Imaging", "Focus", "Solve", "Guiding", "Flip", "Sessions", "Safety", "Settings"] {
            open(area)
            sleep(1)
            XCTAssertTrue(element("emergency").exists, "emergency must be on \(area)")
            XCTAssertTrue(element("safety.indicator").exists, "safety must be on \(area)")
            screenshot("night-\(area)")
        }
    }

    func testStandardThemeAndSwitchingToNight() {
        launch(theme: "standard")
        waitForShell()
        open("Overview")
        sleep(1)
        screenshot("standard-Overview")
        open("Settings")
        element("theme.night").tap()
        sleep(1)
        screenshot("night-after-switch")
    }

    /// `MAPP-CTL-020`, `MAPP-CTL-040`, `MAPP-CTL-050`: without the lease the Master refuses; with it, a confirmed
    /// park runs; declining sends nothing.
    func testLeaseConfirmationAndOutcome() {
        launch()
        waitForShell()
        open("Equipment")
        let park = element("mount.park")
        XCTAssertTrue(park.waitForExistence(timeout: 5))
        park.tap()
        let refusal = app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'Take control'")).firstMatch
        XCTAssertTrue(refusal.waitForExistence(timeout: 5), "the Master's reason must be shown")

        element("lease.take").tap()
        XCTAssertTrue(element("lease.release").waitForExistence(timeout: 5))

        park.tap()
        XCTAssertTrue(element("confirm.cancel").waitForExistence(timeout: 5))
        screenshot("night-confirmation")
        element("confirm.cancel").tap()
        XCTAssertFalse(element("confirm.cancel").exists)

        park.tap()
        XCTAssertTrue(element("confirm.accept").waitForExistence(timeout: 5))
        element("confirm.accept").tap()
        XCTAssertTrue(app.staticTexts["Done."].waitForExistence(timeout: 8), "Done is only the Master's word")
    }

    /// `MAPP-SKY-010` to `040`: the sky draws; search finds; the go-to asks first, naming the target and its altitude;
    /// declining sends nothing, and accepting runs the slew. Time moves and comes back.
    func testSkySearchAndGoTo() {
        launch()
        waitForShell()
        element("lease.take").tap()
        XCTAssertTrue(element("lease.release").waitForExistence(timeout: 5))
        open("Sky")
        XCTAssertTrue(element("sky.view").waitForExistence(timeout: 10))
        sleep(3)  // let the first pages arrive
        screenshot("night-sky")
        XCTAssertFalse(element("sky.search").exists, "the panel stays out of the way until asked for")
        openSkyPanel()
        screenshot("night-sky-panel")
        let figures = element("sky.constellations")
        XCTAssertTrue(figures.waitForExistence(timeout: 5))
        for _ in 0..<3 where figures.frame.maxY > app.windows.firstMatch.frame.height { app.swipeUp() }
        XCTAssertEqual(figures.value as? String, "On")
        figures.tap()
        sleep(1)
        XCTAssertEqual(figures.value as? String, "Off")
        figures.tap()
        sleep(1)
        XCTAssertEqual(figures.value as? String, "On")
        XCTAssertEqual(element("sky.catalog.Messier").value as? String, "On")
        let ngc = element("sky.catalog.NGC")
        XCTAssertEqual(ngc.value as? String, "Off")
        ngc.tap()
        sleep(1)
        XCTAssertEqual(ngc.value as? String, "On")
        ngc.tap()
        XCTAssertTrue(element("sky.survey").exists)
        XCTAssertEqual(element("sky.ground").value as? String, "On")

        let search = element("sky.search")
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("orion\n")
        let result = element("sky.result.0")
        XCTAssertTrue(result.waitForExistence(timeout: 5), "search must find it")
        result.tap()
        XCTAssertTrue(element("sky.altitude").waitForExistence(timeout: 5), "the Master's altitude must show")
        sleep(2)
        screenshot("night-sky-selected")

        let goTo = element("sky.goto")
        XCTAssertTrue(goTo.waitForExistence(timeout: 5))
        goTo.tap()
        XCTAssertTrue(app.staticTexts["Slew to M 42"].waitForExistence(timeout: 5), "the question must name the target")
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label BEGINSWITH 'Altitude now:'")).firstMatch.exists)
        screenshot("night-sky-goto")
        element("confirm.cancel").tap()
        XCTAssertFalse(app.staticTexts["Done."].exists, "declining sends nothing")
        goTo.tap()
        XCTAssertTrue(element("confirm.accept").waitForExistence(timeout: 5))
        element("confirm.accept").tap()
        XCTAssertTrue(app.staticTexts["Done."].waitForExistence(timeout: 15))
        // The rest of what the Star Atlas offers on an object.
        XCTAssertTrue(app.buttons["Goto and Centre"].exists)
        XCTAssertTrue(app.buttons["Sync"].exists)
        element("sky.addsession").tap()
        XCTAssertTrue(element("session.target").waitForExistence(timeout: 5), "the target goes to the Sessions screen")
        screenshot("night-session-target")
        element("session.target.clear").tap()
        open("Sky")

    }

    /// `MAPP-STR-060`: a dropped link says so inside the view.
    func testSkySaysWhenItIsNotLive() {
        launch(scenario: "dropped-stream")
        waitForShell()
        open("Sky")
        XCTAssertTrue(element("sky.view").waitForExistence(timeout: 10))
        sleep(2)
        screenshot("night-sky-link")
    }

    /// The sky gets the whole screen: the panel comes when asked for, goes when told to, and can be kept.
    func testSkyPanelAutoHidesAndCanBeKeptOpen() {
        launch()
        waitForShell()
        open("Sky")
        XCTAssertTrue(element("sky.view").waitForExistence(timeout: 10))
        let wholeWidth = element("sky.view").frame.width
        XCTAssertFalse(element("sky.search").exists)
        openSkyPanel()
        XCTAssertEqual(element("sky.view").frame.width, wholeWidth, "over the sky, not beside it")
        element("sky.panel.close").tap()
        XCTAssertTrue(waitForGone(element("sky.search")))
        openSkyPanel()
        element("sky.panel.pin").tap()
        XCTAssertFalse(element("sky.panel.close").waitForExistence(timeout: 1))
        element("sky.panel.pin").tap()   // leave it as other tests expect it
        element("sky.panel.close").tap()
        XCTAssertTrue(waitForGone(element("sky.search")))
    }

    /// `MAPP-SKY-070`: real tiles through the whole path to the screen. Only with a folder of them, which the repository
    /// doesn't have: `TEST_RUNNER_GALILEO_TEST_SURVEY_DIR=<a Galileo cache's sky_survey/DSSColor>`. Look at the pictures.
    func testRealSurveyTiles() throws {
        let folder = ProcessInfo.processInfo.environment["GALILEO_TEST_SURVEY_DIR"]
        try XCTSkipIf(folder == nil, "needs a folder of survey tiles")
        launch(surveyFolder: folder)
        waitForShell()
        open("Sky")
        XCTAssertTrue(element("sky.view").waitForExistence(timeout: 10))
        XCTAssertTrue(element("sky.credit").waitForExistence(timeout: 30), "a tile must arrive, with its credit")
        sleep(12)
        screenshot("night-survey-real-tiles")
        app.terminate()
        app.launchArguments += ["-GalileoTheme", "standard"]
        app.launch()
        waitForShell()
        open("Sky")
        sleep(14)
        screenshot("standard-survey-real-tiles")   // whichever appearance the simulator is set to
    }

    private func openSkyPanel() {
        if !element("sky.search").exists { element("sky.panel").tap() }
        XCTAssertTrue(element("sky.search").waitForExistence(timeout: 5))
    }

    /// `MAPP-IMG-020`: the loop starts, keeps going, and stops when asked.
    func testLiveLoop() {
        launch()
        waitForShell()
        element("lease.take").tap()
        XCTAssertTrue(element("lease.release").waitForExistence(timeout: 5))
        open("Imaging")
        let loop = element("liveloop")
        for _ in 0..<6 where !loop.exists || loop.frame.maxY > app.windows.firstMatch.frame.height - 40 { app.swipeUp() }
        XCTAssertTrue(loop.waitForExistence(timeout: 5))
        loop.tap()
        XCTAssertTrue(app.staticTexts["Stop live loop"].waitForExistence(timeout: 5))
        sleep(2)
        screenshot("night-liveloop")
        loop.tap()
        XCTAssertTrue(app.staticTexts["Start live loop"].waitForExistence(timeout: 5))
    }

    /// `MAPP-LAY-010`: the tablet's menu hides itself once used, and can be kept open instead. The phone has no menu.
    func testMenuAutoHidesAndCanBeKeptOpen() throws {
        launch()
        waitForShell()
        let menu = element("menu")
        guard menu.waitForExistence(timeout: 3) else { throw XCTSkip("phones use the strip of areas") }
        let imaging = element("area.Imaging")
        XCTAssertFalse(imaging.exists, "hidden until asked for")
        menu.tap()
        XCTAssertTrue(imaging.waitForExistence(timeout: 3))
        screenshot("night-menu-open")
        imaging.tap()
        XCTAssertTrue(waitForGone(imaging), "choosing an area closes it")
        XCTAssertEqual(menu.label, "Menu, showing Imaging")
        screenshot("night-menu-hidden")

        menu.tap()
        XCTAssertTrue(imaging.waitForExistence(timeout: 3))
        element("menu.dismiss").coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.5)).tap()
        XCTAssertTrue(waitForGone(imaging), "tapping away closes it")

        menu.tap()
        element("menu.pin").tap()
        XCTAssertTrue(imaging.waitForExistence(timeout: 3))
        element("area.Focus").tap()
        XCTAssertTrue(imaging.exists, "kept open stays open")
        XCTAssertFalse(menu.exists)
        screenshot("night-menu-pinned")
        element("menu.pin").tap()           // leave it as the other tests expect
        XCTAssertTrue(menu.waitForExistence(timeout: 3))
    }

    private func waitForGone(_ element: XCUIElement) -> Bool {
        let deadline = Date().addingTimeInterval(3)
        while element.exists, Date() < deadline { usleep(100_000) }
        return !element.exists
    }

    /// `MAPP-SOL-010`: a solve runs and its result appears.
    func testSolve() {
        launch()
        waitForShell()
        element("lease.take").tap()
        XCTAssertTrue(element("lease.release").waitForExistence(timeout: 5))
        open("Solve")
        XCTAssertTrue(element("solve.solution").waitForExistence(timeout: 5), "the demo starts with a solution")
        element("solve.capture").tap()
        XCTAssertTrue(app.staticTexts["Done."].waitForExistence(timeout: 10), "Done is only the Master's word")
        XCTAssertTrue(element("solve.solution").exists)
    }

    /// `MAPP-FLP-010`, `MAPP-FLP-020`: the flip's state, and a flip only after the Master's confirmation.
    func testFlipAsksFirst() {
        launch()
        waitForShell()
        element("lease.take").tap()
        XCTAssertTrue(element("lease.release").waitForExistence(timeout: 5))
        open("Flip")
        XCTAssertTrue(app.staticTexts["Scheduled"].waitForExistence(timeout: 5))
        let flip = element("flip.now")
        XCTAssertTrue(flip.waitForExistence(timeout: 5))
        flip.tap()
        XCTAssertTrue(element("confirm.accept").waitForExistence(timeout: 5))
        screenshot("night-flip-confirmation")
        element("confirm.accept").tap()
        XCTAssertTrue(app.staticTexts["Flipping"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Done."].waitForExistence(timeout: 15), "the flip's end is the Master's word")
    }

    /// `MAPP-SES-010`, `MAPP-SES-020`, `MAPP-SES-030`, `MAPP-SCH-010`: pause and resume, a stop that asks, and the queue.
    func testSessions() {
        launch()
        waitForShell()
        element("lease.take").tap()
        XCTAssertTrue(element("lease.release").waitForExistence(timeout: 5))
        open("Sessions")
        XCTAssertTrue(element("schedule.next").waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["3 of 12"].exists)
        screenshot("night-sessions")
        element("session.pause").tap()
        XCTAssertTrue(app.staticTexts["Paused"].waitForExistence(timeout: 5))
        element("session.resume").tap()
        XCTAssertTrue(app.staticTexts["Running"].waitForExistence(timeout: 5))
        element("session.stop").tap()
        XCTAssertTrue(element("confirm.cancel").waitForExistence(timeout: 5), "stop must ask first")
        element("confirm.accept").tap()
        XCTAssertTrue(app.staticTexts["Stopped"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Stopped from the app"].exists)
    }

    /// `MAPP-EMG-010`, `MAPP-EMG-020`: two interactions, no lease, every part listed. A tap isn't enough.
    func testEmergencyNeedsTheDeliberateGesture() {
        launch()
        waitForShell()
        element("emergency").tap()
        let stop = element("emergency.system.stop_everything")
        XCTAssertTrue(stop.waitForExistence(timeout: 5))
        stop.tap()
        sleep(1)
        XCTAssertFalse(element("emergency.result").exists, "a tap must not fire an emergency action")
        stop.press(forDuration: 1.4)
        let listed = element("emergency.result").waitForExistence(timeout: 5)
        screenshot("night-emergency")
        XCTAssertTrue(listed, "the result must list each part")
    }

    /// `MAPP-CTL-010`: a Viewer is offered no control at all, including no emergency action.
    func testViewerSeesNoControls() {
        launch(scenario: "viewer")
        XCTAssertTrue(element("safety.indicator").waitForExistence(timeout: 15))
        XCTAssertFalse(element("emergency").exists)
        open("Equipment")
        sleep(1)
        XCTAssertFalse(element("mount.park").exists)
        XCTAssertFalse(element("lease.take").exists)
        screenshot("night-viewer")
    }

    /// `MAPP-SAF-020`, `MAPP-SAF-030`, `MAPP-SAF-040`: an unsafe state shows everywhere; only acknowledge is offered.
    func testSafetyEvent() {
        launch(scenario: "safety-event")
        waitForShell()
        open("Safety")
        XCTAssertTrue(app.staticTexts["Safety monitor: rain detected"].waitForExistence(timeout: 15))
        XCTAssertEqual(element("safety.indicator").label, "Safety: unsafe")
        element("safety.acknowledge").tap()
        XCTAssertTrue(app.staticTexts["Done."].waitForExistence(timeout: 5))
        screenshot("night-safety-event")
    }

    /// `MAPP-CON-060`: revocation returns to an explanation, not a dead screen.
    func testRevocation() {
        launch(scenario: "revoked-pairing")
        waitForShell()
        XCTAssertTrue(app.staticTexts["No longer paired"].waitForExistence(timeout: 15))
    }

    /// `MAPP-CON-080`, `MAPP-PLT-090`: the screen says which side to update.
    func testNewerMasterSaysToUpdateTheApp() {
        launch(scenario: "newer-master")
        XCTAssertTrue(app.staticTexts["Update this app"].waitForExistence(timeout: 15))
    }

    /// `MAPP-CON-030`: nothing but the state of the request until it's answered.
    func testRefusedPairing() {
        launch(scenario: "first-pairing-refused")
        XCTAssertTrue(app.staticTexts["The request was refused on the Master."].waitForExistence(timeout: 15))
        XCTAssertFalse(element("emergency").exists)
    }

    /// `MAPP-CON-020`, `MAPP-CON-030`, `MAPP-CON-040`, `MAPP-CON-050`, `MAPP-CON-100`: the real thing, on iOS, against
    /// `tools/mock-master`: typed address, real TLS under App Transport Security, the pin, the Keychain (a relaunch
    /// reconnects with no pairing), and unpairing. `scripts/check_ios.sh` starts the server and sets the address.
    func testRealPairingKeychainAndUnpair() throws {
        let address = try XCTUnwrap(
            ProcessInfo.processInfo.environment["GALILEO_UITEST_ADDRESS"],
            "no mock server: run this through scripts/check_ios.sh")
        app = XCUIApplication()
        app.launch()

        let field = element("address")
        XCTAssertTrue(field.waitForExistence(timeout: 10), "expected the welcome screen: is a pairing left over?")
        field.tap()
        field.typeText(address + "\n")

        XCTAssertTrue(element("emergency").waitForExistence(timeout: 30), "pairing against the real server failed")
        XCTAssertFalse(element("demo.banner").exists, "this isn't the demo")
        let connected = element("connection")
        XCTAssertTrue(connected.waitForExistence(timeout: 10))
        let deadline = Date().addingTimeInterval(15)
        while !connected.label.contains("Connected"), Date() < deadline { usleep(200_000) }
        XCTAssertTrue(connected.label.contains("Connected"), connected.label)
        screenshot("night-real-master")

        // A new process has only the Keychain to go on.
        app.terminate()
        app.launch()
        XCTAssertTrue(element("emergency").waitForExistence(timeout: 20), "the pairing did not survive a relaunch")
        XCTAssertFalse(field.exists)

        open("Settings")
        let unpair = element("unpair")
        XCTAssertTrue(unpair.waitForExistence(timeout: 5))
        for _ in 0..<3 where unpair.frame.maxY > app.windows.firstMatch.frame.height { app.swipeUp() }
        unpair.tap()
        element("unpair.confirm").tap()
        XCTAssertTrue(field.waitForExistence(timeout: 10), "unpairing must return to the welcome screen")

        app.terminate()
        app.launch()
        XCTAssertTrue(field.waitForExistence(timeout: 10), "the token must be gone from the Keychain")
    }

    /// `MAPP-LAY-020`, on whichever device the suite runs on: the shell survives rotation.
    func testRotation() {
        launch()
        waitForShell()
        XCUIDevice.shared.orientation = .landscapeLeft
        sleep(1)
        XCTAssertTrue(element("emergency").exists)
        screenshot("night-landscape")
        XCUIDevice.shared.orientation = .portrait
    }
}
