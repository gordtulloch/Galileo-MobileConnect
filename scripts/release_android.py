#!/usr/bin/env python3
"""Build, check and publish a signed Android release of MobileConnect, entirely on this computer.

    python scripts/release_android.py            # build + verify only (nothing is published)
    python scripts/release_android.py --publish  # ...then tag, create the GitHub release, list it on the web site

The signing key never leaves this machine: it is read from android/keystore.properties (see docs/android/TESTING.md).
Before --publish: raise versionName/versionCode in android/app/build.gradle.kts, update docs/android/release-notes.md,
and commit and push the repository (the release tag is made on the pushed commit).

Publishing needs the GitHub CLI (`gh`, logged in) and a clone of Galileo.web next to this repository (or --web PATH).
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ANDROID = ROOT / "android"
GRADLE_FILE = ANDROID / "app" / "build.gradle.kts"
NOTES = ROOT / "docs" / "android" / "release-notes.md"
REPO = "gordtulloch/Galileo-MobileConnect"
LABEL = "MobileConnect for Android (testing)"
WINDOWS = os.name == "nt"


def run(cmd: list[str], cwd: Path = ROOT, check: bool = True, capture: bool = False) -> subprocess.CompletedProcess:
    print("  $", " ".join(str(c) for c in cmd))
    return subprocess.run(cmd, cwd=cwd, check=check, text=True, capture_output=capture)


def fail(message: str) -> None:
    sys.exit(f"error: {message}")


def read_versions() -> tuple[str, int]:
    text = GRADLE_FILE.read_text(encoding="utf-8")
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    if not name or not code:
        fail(f"could not read versionName/versionCode from {GRADLE_FILE}")
    return name.group(1), int(code.group(1))


def sdk_dir() -> Path:
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(var):
            return Path(os.environ[var])
    props = ANDROID / "local.properties"
    if props.exists():
        match = re.search(r"^sdk\.dir\s*=\s*(.+)$", props.read_text(encoding="utf-8"), re.M)
        if match:
            return Path(match.group(1).strip().replace("\\:", ":").replace("\\\\", "\\"))
    default = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk"
    if default.exists():
        return default
    fail("Android SDK not found (set ANDROID_HOME)")
    raise SystemExit(1)


def apksigner() -> Path:
    builds = sorted((sdk_dir() / "build-tools").glob("*"), key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"\D+", p.name)])
    for build in reversed(builds):
        tool = build / ("apksigner.bat" if WINDOWS else "apksigner")
        if tool.exists():
            return tool
    fail("apksigner not found in the SDK's build-tools")
    raise SystemExit(1)


def check_key() -> None:
    props = ANDROID / "keystore.properties"
    if not props.exists():
        fail("android/keystore.properties is missing: a release for testers must be signed with the release key "
             "(docs/android/TESTING.md)")
    text = props.read_text(encoding="utf-8")
    if "REPLACE_WITH" in text:
        fail("android/keystore.properties still has placeholder passwords")


def build(version: str) -> Path:
    gradle = str(ANDROID / ("gradlew.bat" if WINDOWS else "gradlew"))
    print("Tests")
    run([gradle, ":core:test", ":quest-logic:test"], cwd=ANDROID)
    print("Build")
    run([gradle, ":app:assembleRelease"], cwd=ANDROID)
    built = ANDROID / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
    if not built.exists():
        fail(f"{built} was not produced")
    out = ROOT / "dist" / f"Galileo-MobileConnect-{version}.apk"
    out.parent.mkdir(exist_ok=True)
    shutil.copyfile(built, out)
    print("Signature")
    result = run([str(apksigner()), "verify", "--print-certs", str(out)], capture=True)
    print(result.stdout.strip())
    if "Android Debug" in result.stdout:
        fail("the APK is signed with the debug key, not the release key")
    return out


def publish(apk: Path, version: str, web: Path) -> None:
    tag = f"v{version}"
    if shutil.which("gh") is None:
        fail("the GitHub CLI (gh) is not installed")
    dirty = run(["git", "status", "--porcelain"], capture=True).stdout.strip()
    if dirty:
        fail("the repository has uncommitted changes; commit and push them first so the tag is on the built code")
    run(["git", "fetch", "origin", "main", "--quiet"])
    ahead = run(["git", "rev-list", "--count", "origin/main..HEAD"], capture=True).stdout.strip()
    if ahead != "0":
        fail("there are commits not pushed to origin/main; push first")
    if run(["gh", "release", "view", tag, "-R", REPO], check=False, capture=True).returncode == 0:
        fail(f"release {tag} already exists; raise the version")
    if not NOTES.exists() or version not in NOTES.read_text(encoding="utf-8"):
        fail(f"{NOTES.relative_to(ROOT)} does not mention {version}; write this release's notes first")
    if not (web / "scripts" / "update_apps.py").exists():
        fail(f"no Galileo.web clone at {web} (use --web PATH)")
    if run(["git", "status", "--porcelain"], cwd=web, capture=True).stdout.strip():
        fail(f"{web} has uncommitted changes; commit or stash them first")
    run(["git", "pull", "--ff-only", "--quiet"], cwd=web)

    print("GitHub release")
    run(["gh", "release", "create", tag, str(apk), "-R", REPO, "--title", f"MobileConnect for Android {version} (testing)",
         "--notes-file", str(NOTES), "--prerelease", "--target", "main"])
    print("Web site")
    run([sys.executable, str(web / "scripts" / "update_apps.py"), "android", LABEL, version, tag, str(apk),
         str(web / "downloads.json")])
    run(["git", "add", "downloads.json"], cwd=web)
    run(["git", "commit", "-m", f"Downloads: MobileConnect for Android {version}"], cwd=web)
    run(["git", "push", "origin", "HEAD"], cwd=web)
    print(f"\nPublished {tag}: https://github.com/{REPO}/releases/tag/{tag}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--publish", action="store_true", help="tag, release on GitHub and update the web site")
    parser.add_argument("--web", type=Path, default=ROOT.parent / "Galileo.web", help="clone of Galileo.web")
    args = parser.parse_args()

    version, code = read_versions()
    print(f"MobileConnect for Android {version} (versionCode {code})")
    check_key()
    apk = build(version)
    print(f"\nBuilt and verified: {apk} ({apk.stat().st_size / 1048576:.1f} MB)")
    if not args.publish:
        print("Nothing published. Run again with --publish to release it.")
        return
    publish(apk, version, args.web.resolve())


if __name__ == "__main__":
    main()
