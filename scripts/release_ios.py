#!/usr/bin/env python3
"""Archive MobileConnect for iOS and, optionally, upload it to App Store Connect for TestFlight.

    python scripts/release_ios.py            # tests + archive + export an .ipa into dist/ (nothing uploaded)
    python scripts/release_ios.py --upload   # ...then upload that build to App Store Connect (TestFlight)

Run it on a Mac with Xcode, signed in to Xcode > Settings > Accounts with the Apple ID that holds the developer
membership. Signing is automatic (-allowProvisioningUpdates), so Xcode creates the distribution certificate and the
App Store profile itself; no key or password is kept in this repository.

Before an upload: raise MARKETING_VERSION and/or CURRENT_PROJECT_VERSION in ios/project.yml (the build number must go
up on every upload or App Store Connect rejects it), run `xcodegen` in ios/ and commit both. The script refuses to
continue if project.yml and the Xcode project disagree. Then finish in App Store Connect: see docs/ios/TESTING.md.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
IOS = ROOT / "ios"
PROJECT = IOS / "GalileoMobileConnect.xcodeproj"
PROJECT_YML = IOS / "project.yml"
SCHEME = "GalileoMobileConnect"


def run(cmd: list[str], cwd: Path = ROOT, log: Path | None = None) -> None:
    """Run *cmd*. With *log*, keep the full output there and, on failure, print just the error lines."""
    print("  $", " ".join(str(c) for c in cmd))
    if log is None:
        subprocess.run(cmd, cwd=cwd, check=True)
        return
    with log.open("w", encoding="utf-8") as handle:
        result = subprocess.run(cmd, cwd=cwd, stdout=handle, stderr=subprocess.STDOUT, text=True)
    if result.returncode:
        lines = log.read_text(encoding="utf-8", errors="replace").splitlines()
        errors = [line for line in lines if "error:" in line or "** ARCHIVE FAILED **" in line
                  or "** EXPORT FAILED **" in line]
        print("\n".join(dict.fromkeys(errors)) or "\n".join(lines[-30:]))
        fail(f"{cmd[1]} failed (exit {result.returncode}); full output in {log}")


def fail(message: str) -> None:
    sys.exit(f"error: {message}")


def read_yml(key: str) -> str:
    match = re.search(rf'^\s*{key}:\s*"?([^"\s#]+)"?', PROJECT_YML.read_text(encoding="utf-8"), re.M)
    if not match:
        fail(f"could not read {key} from {PROJECT_YML}")
    return match.group(1)


def check_project_matches(version: str, build: str) -> None:
    text = (PROJECT / "project.pbxproj").read_text(encoding="utf-8")
    versions = set(re.findall(r"MARKETING_VERSION = ([^;]+);", text))
    builds = set(re.findall(r"CURRENT_PROJECT_VERSION = ([^;]+);", text))
    if versions != {version} or builds != {build}:
        fail(f"project.yml says {version} ({build}) but the Xcode project has {sorted(versions)} / {sorted(builds)}; "
             "run `xcodegen` in ios/ and commit the result")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--upload", action="store_true", help="upload the build to App Store Connect (TestFlight)")
    parser.add_argument("--skip-tests", action="store_true", help="do not run the GalileoCore tests first")
    args = parser.parse_args()

    if sys.platform != "darwin":
        fail("iOS builds need a Mac with Xcode")
    version, build, team = read_yml("MARKETING_VERSION"), read_yml("CURRENT_PROJECT_VERSION"), read_yml("DEVELOPMENT_TEAM")
    print(f"MobileConnect for iOS {version} (build {build})")
    check_project_matches(version, build)

    if not args.skip_tests:
        print("Tests")
        run(["swift", "test"], cwd=IOS / "Packages" / "GalileoCore")

    dist = ROOT / "dist" / f"ios-{version}-{build}"
    archive = dist / f"{SCHEME}.xcarchive"
    dist.mkdir(parents=True, exist_ok=True)
    print("Archive")
    run(["xcodebuild", "archive", "-project", str(PROJECT), "-scheme", SCHEME, "-configuration", "Release",
         "-destination", "generic/platform=iOS", "-archivePath", str(archive), "-allowProvisioningUpdates"],
        log=dist / "archive.log")

    export_options = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n'
        '<plist version="1.0"><dict>\n'
        "<key>method</key><string>app-store-connect</string>\n"
        f"<key>teamID</key><string>{team}</string>\n"
        "<key>signingStyle</key><string>automatic</string>\n"
        f"<key>destination</key><string>{'upload' if args.upload else 'export'}</string>\n"
        "<key>uploadSymbols</key><true/>\n"
        "</dict></plist>\n")
    with tempfile.TemporaryDirectory() as tmp:
        options = Path(tmp) / "ExportOptions.plist"
        options.write_text(export_options, encoding="utf-8")
        print("Upload to App Store Connect" if args.upload else "Export")
        run(["xcodebuild", "-exportArchive", "-archivePath", str(archive), "-exportOptionsPlist", str(options),
             "-exportPath", str(dist), "-allowProvisioningUpdates"],
            log=dist / "export.log")

    if args.upload:
        print(f"\nUploaded {version} ({build}). It appears in App Store Connect > TestFlight after processing "
              "(usually 5 to 30 minutes); see docs/ios/TESTING.md for what to do next.")
    else:
        print(f"\nBuilt and signed: {dist}. Nothing uploaded. Run again with --upload to send it to TestFlight.")


if __name__ == "__main__":
    main()
