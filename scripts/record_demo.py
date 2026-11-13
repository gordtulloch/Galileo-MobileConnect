"""Drives the Android app through every screen, twice: once in Night (red) and once in Standard. For recording a demo.

    python scripts/record_demo.py setup [--address HOST[:PORT]] [--no-wait]    once: install, then pair with a Master
    python scripts/record_demo.py run   [--demo] [--pause SECONDS] [--themes night,standard]

`setup` installs the debug build, clears the app, types the Master's address and asks to pair; the pairing code appears on
the emulator and in this window, and has to be approved once on the Master. After that `run` reconnects by itself.
With `--demo` neither is needed: the app plays its built-in simulated observatory instead.

The tour takes control once, to take a single 5 second exposure on the Imaging screen (`--no-exposure` for none), and gives
control back at the end. It moves no equipment and fires no emergency action: it opens the emergency panel and closes it again. It needs a running emulator or device (the `Tab_A7` AVD is a 2000x1200 landscape tablet) and the
APK from `gradlew :app:assembleDebug`. Nothing here is part of the app.
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
from pathlib import Path

PACKAGE = "com.gordtulloch.galileo.mobileconnect"
ACTIVITY = f"{PACKAGE}/com.galileo.mobileconnect.MainActivity"
ROOT = Path(__file__).resolve().parent.parent
APK = ROOT / "android" / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
DEFAULT_ADDRESS = "10.7.10.47"


def find_adb() -> str:
    for base in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"), os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk")):
        if base:
            for name in ("adb.exe", "adb"):
                candidate = Path(base) / "platform-tools" / name
                if candidate.exists():
                    return str(candidate)
    return "adb"


ADB = find_adb()


def adb(*args: str) -> str:
    result = subprocess.run([ADB, *args], capture_output=True)
    return result.stdout.decode("utf-8", errors="replace")


def log(message: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {message}", flush=True)


class Screen:
    def __init__(self, pause: float) -> None:
        self.pause_seconds = pause

    # -- reading the screen -----------------------------------------------------------------------------------------

    def dump(self) -> str:
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        return adb("exec-out", "cat", "/sdcard/ui.xml")

    def find(self, xml: str, key: str, value: str) -> tuple[int, int] | None:
        pattern = rf'{key}="{re.escape(value)}"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
        match = re.search(pattern, xml)
        if not match:
            return None
        a, b, c, d = map(int, match.groups())
        return (a + c) // 2, (b + d) // 2

    def has(self, tag: str) -> bool:
        return self.find(self.dump(), "resource-id", tag) is not None

    # -- acting -------------------------------------------------------------------------------------------------------

    def tap(self, tag: str, quiet: bool = False) -> bool:
        point = self.find(self.dump(), "resource-id", tag)
        if point is None:
            if not quiet:
                log(f"  (no '{tag}' on screen: skipped)")
            return False
        adb("shell", "input", "tap", str(point[0]), str(point[1]))
        return True

    def tap_text(self, text: str) -> bool:
        point = self.find(self.dump(), "text", text)
        if point is None:
            log(f"  (no '{text}' on screen: skipped)")
            return False
        adb("shell", "input", "tap", str(point[0]), str(point[1]))
        return True

    def keyboard_shown(self) -> bool:
        return "mInputShown=true" in adb("shell", "dumpsys", "input_method")

    def type_into(self, tag: str, value: str) -> bool:
        """Types into a field, then closes the on-screen keyboard if one opened (BACK would leave the app otherwise)."""
        if not self.tap(tag):
            return False
        time.sleep(0.6)
        adb("shell", "input", "keyevent", "123")  # end of the field
        for _ in range(8):
            adb("shell", "input", "keyevent", "67")  # clear what is there
        adb("shell", "input", "text", value)
        time.sleep(0.4)
        if self.keyboard_shown():
            adb("shell", "input", "keyevent", "4")
            time.sleep(0.5)
        return True

    def swipe(self, x1: int, y1: int, x2: int, y2: int, ms: int = 500) -> None:
        adb("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms))

    def size(self) -> tuple[int, int]:
        match = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
        return (int(match.group(1)), int(match.group(2))) if match else (2000, 1200)

    def pause(self, factor: float = 1.0) -> None:
        time.sleep(self.pause_seconds * factor)

    # -- the app --------------------------------------------------------------------------------------------------------

    def go(self, area: str) -> bool:
        tag = f"area.{area}"
        xml = self.dump()
        if self.find(xml, "resource-id", tag) is None and self.find(xml, "resource-id", "menu") is not None:
            self.tap("menu")  # a tablet keeps the areas in a menu
            time.sleep(0.6)
        if not self.tap(tag):
            return False
        time.sleep(0.8)
        return True

    def wait_for(self, tag: str, seconds: float) -> bool:
        deadline = time.time() + seconds
        while time.time() < deadline:
            if self.has(tag):
                return True
            time.sleep(1.5)
        return False


def launch(demo: bool, theme: str) -> None:
    adb("shell", "am", "force-stop", PACKAGE)
    time.sleep(1.5)
    args = ["shell", "am", "start", "-n", ACTIVITY]
    if demo:
        args += ["--es", "galileo.demo", "normal-night"]
    args += ["--es", "galileo.theme", theme]
    adb(*args)


# -- setup ----------------------------------------------------------------------------------------------------------------


def setup(args: argparse.Namespace) -> int:
    if not APK.exists():
        log(f"No APK at {APK}. Run: cd android && gradlew :app:assembleDebug")
        return 1
    screen = Screen(args.pause)
    log("Installing the app and clearing its data")
    adb("install", "-r", str(APK))
    adb("shell", "pm", "clear", PACKAGE)
    launch(False, "night")
    if not screen.wait_for("address", 20):
        log("The welcome screen did not appear.")
        return 1
    log(f"Typing the address {args.address}")
    screen.tap("address")
    time.sleep(0.8)
    adb("shell", "input", "text", args.address)
    adb("shell", "input", "keyevent", "4")  # close the keyboard
    time.sleep(0.6)
    screen.tap("connect")
    if not screen.wait_for("pairing.code", 25):
        log("No pairing code appeared. Is the Master running, with the MobileConnect plugin, and reachable from the emulator?")
        return 1
    xml = screen.dump()
    code = re.search(r'content-desc="Code ([\d ]+)"', xml)
    shown = code.group(1).replace(" ", "") if code else "(see the emulator)"
    log(f"PAIRING CODE {shown[:3]} {shown[3:]} - approve this request on the Galileo desktop (it matches the code there).")
    if args.no_wait:
        log("--no-wait: stopping here. The request stays pending on the Master until it expires.")
        return 0
    if not screen.wait_for("connection", 330):
        log("Not approved in time. Run setup again.")
        return 1
    log("Paired and connected. The Master is remembered on the emulator; run `record_demo.py run` when ready to record.")
    return 0


# -- the tour -------------------------------------------------------------------------------------------------------------


def tour(screen: Screen, label: str, exposure: float | None) -> None:
    w, h = screen.size()
    mid_x, mid_y = w // 2, h // 2
    log(f"--- {label}: Overview")
    screen.go("Overview")
    screen.pause(1.2)
    screen.swipe(mid_x, h - 200, mid_x, 300)
    screen.pause()
    screen.swipe(mid_x, 300, mid_x, h - 200)
    screen.pause(0.5)

    log(f"--- {label}: Equipment")
    screen.go("Equipment")
    screen.pause(1.2)
    screen.swipe(mid_x, h - 200, mid_x, 300)
    screen.pause()
    screen.swipe(mid_x, 300, mid_x, h - 200)

    log(f"--- {label}: Sky")
    screen.go("Sky")
    screen.pause(2)  # the stars arrive page by page
    screen.swipe(mid_x + 300, mid_y, mid_x - 300, mid_y, 900)  # look around
    screen.pause(1.5)
    screen.swipe(mid_x - 200, mid_y + 150, mid_x + 200, mid_y - 150, 900)
    screen.pause(1.5)
    if screen.tap("sky.panel", quiet=True):
        screen.pause()
        screen.tap_text("+1 h")
        screen.pause(0.8)
        screen.tap_text("+1 h")
        screen.pause(1.2)
        screen.tap("sky.now", quiet=True)
        screen.pause(0.8)
        screen.tap("sky.constellations", quiet=True)
        screen.pause(1.2)
        screen.tap("sky.constellations", quiet=True)
        screen.pause(0.8)
        screen.tap("sky.panel.close", quiet=True)
        screen.pause(0.8)

    log(f"--- {label}: Imaging")
    screen.go("Imaging")
    screen.pause(2)
    if screen.tap("imaging.panel", quiet=True):
        screen.pause()
        if exposure:
            # The one thing the tour does: a short exposure, so there is a picture to look at and to stretch.
            if screen.has("lease.take"):
                screen.tap("lease.take")
                screen.wait_for("lease.release", 10)
            screen.type_into("imaging.capture.seconds", str(exposure))
            screen.type_into("imaging.capture.count", "1")
            screen.pause(0.8)
            log(f"  taking a {exposure} s exposure")
            screen.tap("imaging.capture")
            screen.pause(0.8)
            screen.tap("imaging.panel.close", quiet=True)
            time.sleep(exposure + 6)  # the exposure, and the picture coming back
            screen.pause(1.5)
        else:
            screen.tap("imaging.more", quiet=True)
            screen.pause(1.5)
            screen.tap("imaging.more", quiet=True)
            screen.tap("imaging.panel.close", quiet=True)
            screen.pause(0.8)
    screen.swipe(int(w * 0.35), h - 70, int(w * 0.75), h - 70, 900)  # the stretch slider
    screen.pause(1.5)
    screen.tap("imaging.stretch.reset", quiet=True)
    screen.pause(0.8)

    log(f"--- {label}: Focus")
    screen.go("Focus")
    screen.pause(1.2)
    screen.swipe(mid_x, h - 200, mid_x, 300)
    screen.pause()

    log(f"--- {label}: Solve")
    screen.go("Solve")
    screen.pause(1.5)
    screen.tap("solve.menu", quiet=True)  # the picture can have the screen to itself
    screen.pause(1.2)
    screen.tap("solve.menu", quiet=True)
    screen.pause(0.8)

    log(f"--- {label}: Guiding")
    screen.go("Guiding")
    screen.pause(1.2)
    screen.tap("guide.show.Full frame", quiet=True)
    screen.pause(1.5)
    screen.tap("guide.show.Star", quiet=True)
    screen.pause(0.8)
    screen.swipe(mid_x, h - 200, mid_x, 300)
    screen.pause()

    log(f"--- {label}: Flip")
    screen.go("Flip")
    screen.pause(1.2)

    log(f"--- {label}: Sessions")
    screen.go("Sessions")
    screen.pause(1.5)

    log(f"--- {label}: Safety")
    screen.go("Safety")
    screen.pause(1.5)

    log(f"--- {label}: Emergency panel (opened and closed; nothing is fired)")
    if screen.tap("emergency", quiet=True):
        screen.pause(1.5)
        screen.tap("emergency.close", quiet=True)
        screen.pause(0.6)

    log(f"--- {label}: Settings")
    screen.go("Settings")
    screen.pause(1.5)


def choose_theme(screen: Screen, theme: str) -> None:
    screen.go("Settings")
    screen.pause(0.5)
    screen.tap("theme.Night" if theme == "night" else "theme.Standard")
    time.sleep(1.5)


def run(args: argparse.Namespace) -> int:
    screen = Screen(args.pause)
    themes = [t.strip().lower() for t in args.themes.split(",") if t.strip()]
    log("Starting the app" + (" (built-in demo observatory)" if args.demo else ""))
    launch(args.demo, themes[0])
    if not screen.wait_for("connection", 45):
        log("The app did not connect. For a real Master, run `record_demo.py setup` first; or use --demo.")
        return 1
    screen.pause(1.5)
    log("READY - recording can start now. The tour begins in 5 seconds.")
    time.sleep(5)
    for index, theme in enumerate(themes):
        choose_theme(screen, theme)
        tour(screen, "Night (red)" if theme == "night" else "Standard", None if args.no_exposure else args.exposure)
        if index + 1 < len(themes):
            log("Changing theme")
            screen.pause(0.5)
    screen.go("Overview")
    screen.pause(2)
    if screen.tap("lease.release", quiet=True):
        log("Gave control back")
    log("Done.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    s = sub.add_parser("setup", help="install the app and pair with a Master")
    s.add_argument("--address", default=DEFAULT_ADDRESS)
    s.add_argument("--no-wait", action="store_true", help="show the pairing code and stop, without waiting for approval")
    s.add_argument("--pause", type=float, default=1.0)
    r = sub.add_parser("run", help="tour every screen in each theme")
    r.add_argument("--demo", action="store_true", help="use the built-in simulated observatory instead of a Master")
    r.add_argument("--pause", type=float, default=4.0, help="seconds to rest on a screen (the tour scales it)")
    r.add_argument("--themes", default="night,standard")
    r.add_argument("--exposure", type=float, default=5.0, help="seconds for the one exposure taken on the Imaging screen (default 5)")
    r.add_argument("--no-exposure", action="store_true", help="take no exposure: the tour stays strictly read-only")
    args = parser.parse_args()
    return setup(args) if args.command == "setup" else run(args)


if __name__ == "__main__":
    sys.exit(main())
