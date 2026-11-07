"""Validate every `stream` frame in api/vectors/ against api/events.schema.json, and each vector's shape.

    python scripts/check_vectors.py        (needs jsonschema)
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import jsonschema

ROOT = Path(__file__).resolve().parent.parent
STEPS = {"act", "send", "receive", "stream", "drop", "expect_connect", "clock", "expect"}


def main() -> int:
    schema = json.loads((ROOT / "api" / "events.schema.json").read_text(encoding="utf-8"))
    jsonschema.Draft202012Validator.check_schema(schema)
    # `something_new` proves unknown types are ignored, so the frame envelope is what is checked for it.
    validator = jsonschema.Draft202012Validator(schema)
    envelope = jsonschema.Draft202012Validator({"$ref": "#/$defs/frame", "$defs": {**schema["$defs"], "event": {"type": "object", "required": ["type"]}}})
    faults = 0
    files = sorted((ROOT / "api" / "vectors").glob("*.json"))
    for path in files:
        vector = json.loads(path.read_text(encoding="utf-8"))
        for key in ("name", "description", "status", "requirements", "steps"):
            if key not in vector:
                print(f"{path.name}: missing {key}")
                faults += 1
        for step in vector.get("steps", []):
            if step.get("do") not in STEPS:
                print(f"{path.name}: unknown step {step.get('do')}")
                faults += 1
            if step.get("do") == "stream":
                frame = step["frame"]
                known = frame.get("data", {}).get("type") in (None, "snapshot", "delta", "alert", "lease", "access", "closing") or str(frame.get("data", {}).get("type", "")).startswith("op.")
                for error in (validator if known else envelope).iter_errors(frame):
                    print(f"{path.name}: {error.message}")
                    faults += 1
    print(f"{len(files)} vectors, {faults} faults")
    return 1 if faults else 0


if __name__ == "__main__":
    sys.exit(main())
