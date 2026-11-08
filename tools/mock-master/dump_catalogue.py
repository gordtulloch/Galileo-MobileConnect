"""Write api/scenarios/catalogue.json from the operations a Galileo checkout really registers.

    PYTHONPATH=../Galileo python tools/mock-master/dump_catalogue.py     (needs Galileo's dependencies)

The mock Master's catalogue is therefore the real one, in the wire shape of api/openapi.yaml, not a copy
someone typed. Operations the app needs that core does not have yet are in catalogue.planned.json instead.
"""
from __future__ import annotations

import json
from pathlib import Path

from galileo.control_surface import catalogue, flips, guiding, history, holds, imaging, piers, registry
from galileo.control_surface import polar, safety, schedule, sessions, sky, solve

OUT = Path(__file__).resolve().parents[2] / "api" / "scenarios" / "catalogue.json"
REGISTERS = [
    flips.register_flip_operations, guiding.register_guiding_operations, history.register_history_operations,
    imaging.register_imaging_operations, piers.register_pier_operations, safety.register_safety_operations,
    schedule.register_schedule_operations, sessions.register_session_operations,
    sessions.register_start_operations, sky.register_sky_operations, solve.register_solve_operations,
    polar.register_polar_operations,
]


class _Registry(registry.Registry):
    """The engine and the local channel each register `session.templates`; the first one is kept here."""

    def register(self, spec, handler, *, namespace=""):
        if spec.name not in self.names():
            super().register(spec, handler, namespace=namespace)


class _Access:
    """Stands in for the running engine: registration only reads attributes, and nothing here is invoked."""

    def __getattr__(self, name: str):
        return lambda *args, **kwargs: None


def main() -> None:
    reg = _Registry()
    catalogue.register_device_operations(reg, _Access(), holds.HoldManager())
    for register in REGISTERS:
        register(reg, _Access())
    operations = []
    for entry in reg.catalogue()["operations"]:
        wire = {"name": entry["name"], "area": entry["name"].split(".")[0], "class": entry["class"],
                "lease": entry["needs_lease"], "args": entry["args"]}
        for key in ("description", "long_running"):
            if entry.get(key):
                wire[key] = entry[key]
        operations.append(wire)
    OUT.write_text(json.dumps({"operations": operations}, indent=1) + "\n", encoding="utf-8")
    print(f"{len(operations)} operations -> {OUT}")


if __name__ == "__main__":
    main()
