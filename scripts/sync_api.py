"""Copy the shared contract to where each platform's build reads it.

    python scripts/sync_api.py           copy to every target
    python scripts/sync_api.py --check   exit 1 if any copy differs (use in CI)

`api/` holds the only files to edit. The copies exist because SwiftPM requires the description (for the
generator) and the mock scenarios (as resources) inside a target's source directory, and symbolic links
do not survive a Windows checkout. Run from the repository root.
"""
from __future__ import annotations

import filecmp
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "api" / "openapi.yaml"
TARGETS = [
    ROOT / "ios" / "Packages" / "GalileoAPI" / "Sources" / "GalileoAPI" / "openapi.yaml",
]
SCENARIOS = ROOT / "api" / "scenarios"
SCENARIO_TARGET = ROOT / "ios" / "Packages" / "GalileoCore" / "Sources" / "GalileoCore" / "Resources" / "scenarios"
# The Kotlin generator reads api/openapi.yaml directly (android/api-client/build.gradle.kts), so it needs no copy.


def main(argv: list[str]) -> int:
    check = "--check" in argv
    drift = 0
    pairs = [(SOURCE, target) for target in TARGETS]
    pairs += [(path, SCENARIO_TARGET / path.name) for path in sorted(SCENARIOS.iterdir())
              if path.suffix in (".json", ".jpg")]
    for source, target in pairs:
        if check:
            if not target.exists() or not filecmp.cmp(source, target, shallow=False):
                print(f"OUT OF DATE: {target.relative_to(ROOT)}")
                drift += 1
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            print(f"wrote {target.relative_to(ROOT)}")
    expected = {target.name for _, target in pairs if target.parent == SCENARIO_TARGET}
    for stray in sorted(SCENARIO_TARGET.iterdir()):
        if stray.name not in expected:
            if check:
                print(f"STALE: {stray.relative_to(ROOT)}")
                drift += 1
            else:
                stray.unlink()
                print(f"removed {stray.relative_to(ROOT)}")
    if check and drift:
        print("run: python scripts/sync_api.py")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
