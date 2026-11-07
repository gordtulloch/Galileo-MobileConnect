"""Run Galileo's real Connect Link server and the real MobileConnect plugin, with fake devices, approving every pairing.

    PYTHONPATH=../Galileo-Plugins/mobileconnect:../Galileo python tools/mock-master/real_link_server.py

For testing the client against real server code, from the pairing to the operations: only the devices and the person
approving are simulated (`mobileconnect/tests/world.py` in Galileo-Plugins). It touches no real installation: the
database is in a temporary folder, the certificate's key is in memory, and it listens on the loopback address.
Prints `READY <port>` once listening. Needs Galileo's dependencies, and a Python of 3.11 or later.
"""
from __future__ import annotations

import asyncio
import os
import tempfile
from pathlib import Path


async def main() -> None:
    folder = tempfile.mkdtemp(prefix="galileo-real-link-")
    os.environ.setdefault("GALILEO_CONFIG_DIR", folder)

    from galileo.library.database import init_db
    from mobileconnect.clients import OPERATOR
    from tests.world import World

    init_db(Path(folder) / "link.db")
    world = World()
    await world.start()
    print(f"READY {world.server.port}", flush=True)
    while True:
        for request in world.clients.pending():
            world.clients.approve(request["request_id"], OPERATOR)
        await asyncio.sleep(0.05)


if __name__ == "__main__":
    asyncio.run(main())
