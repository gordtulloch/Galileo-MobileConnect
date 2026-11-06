"""Copy the single API description to where each generator reads it.

    python scripts/sync_api.py           copy api/openapi.yaml to every target
    python scripts/sync_api.py --check   exit 1 if any copy differs (use in CI)

`api/openapi.yaml` is the only file to edit. The copies exist because the Swift package plugin
(swift-openapi-generator) requires the description inside the target's source directory, and symbolic
links do not survive a Windows checkout. Run from the repository root.
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
# The Kotlin generator reads api/openapi.yaml directly (android/api-client/build.gradle.kts), so it needs no copy.


def main(argv: list[str]) -> int:
    check = "--check" in argv
    drift = 0
    for target in TARGETS:
        if check:
            if not target.exists() or not filecmp.cmp(SOURCE, target, shallow=False):
                print(f"OUT OF DATE: {target.relative_to(ROOT)}")
                drift += 1
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(SOURCE, target)
            print(f"wrote {target.relative_to(ROOT)}")
    if check and drift:
        print("run: python scripts/sync_api.py")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
