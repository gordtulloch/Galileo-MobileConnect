"""Run Galileo's real Connect Link server with a throwaway database, approving every pairing request.

    PYTHONPATH=../Galileo python tools/mock-master/real_link_server.py     (needs Galileo's dependencies)

For testing the client against the routes that exist today (`x-galileo-status: implemented`). It touches no real
installation: the database, the certificate and its key live in a temporary folder or in memory, and the real
listener is bound to the loopback address. Prints `READY <port>` once listening.
"""
from __future__ import annotations

import asyncio
import os
import tempfile
from pathlib import Path


async def main() -> None:
    folder = tempfile.mkdtemp(prefix="galileo-real-link-")
    os.environ.setdefault("GALILEO_CONFIG_DIR", folder)

    from galileo.connect_link import AuditLog, PeerStore
    from galileo.connect_link.certs import CertificateStore
    from galileo.connect_link.handshake import Handshake, Versions
    from galileo.connect_link.pairing import PairingService
    from galileo.connect_link.secrets_store import MemorySecretStore
    from galileo.connect_link.server import PROTOCOL_VERSION, LinkServer
    from galileo.library.database import init_db

    init_db(Path(folder) / "link.db")
    peers = PeerStore()
    peers.declare_plugin("mobileconnect", ["control", "view"])
    pairing = PairingService(peers)
    certificates = CertificateStore(MemorySecretStore(), "real-link-test")
    server = LinkServer(
        peers, pairing, certificates, AuditLog(),
        handshake=Handshake(lambda: Versions("test", "1.0", 0, PROTOCOL_VERSION)),
    )

    class NoDiscovery:
        async def advertise(self, *args):
            pass

        async def withdraw(self):
            pass

    server.discovery = NoDiscovery()
    await server.start("127.0.0.1", 0)
    print(f"READY {server.port}", flush=True)
    while True:
        for request in pairing.pending():
            pairing.approve(request.request_id, ["control", "view"])
        await asyncio.sleep(0.05)


if __name__ == "__main__":
    asyncio.run(main())
