"""
aiodns per il server di Music Assistant dentro Xonos.

L'originale usa c-ares (pycares), che su Android non trova i server DNS del telefono: qui le
domande vanno al DNS di Android. Chi lo usa è la libreria delle radio (radios), per trovare i
server di Radio Browser con un record SRV: Android non risponde agli SRV, quindi per quel nome
si chiede all.api.radio-browser.info, come Radio Browser suggerisce a chi non può farlo.

Niente getaddrinfo qui: senza, aiohttp non lo prende per il suo risolutore e resta su quello di sistema.
"""

from __future__ import annotations

import asyncio
import socket
from dataclasses import dataclass
from typing import Any

from . import error

__version__ = "3.2.0+xonos"

RADIO_BROWSER_SRV = "_api._tcp.radio-browser.info"
RADIO_BROWSER_ALL = "all.api.radio-browser.info"


@dataclass
class SrvRecord:
    """Like pycares' SRV answer."""

    host: str
    port: int
    priority: int = 0
    weight: int = 0
    ttl: int = 0
    type: str = "SRV"


@dataclass
class AddressRecord:
    """Like pycares' A and AAAA answers."""

    host: str
    ttl: int = 0
    type: str = "A"


class DNSResolver:
    """A resolver that asks the phone's own DNS."""

    def __init__(self, nameservers: Any = None, loop: asyncio.AbstractEventLoop | None = None, **_: Any) -> None:
        self.nameservers = nameservers or []

    async def query(self, host: str, qtype: str, qclass: Any = None) -> Any:
        """Answer A, AAAA, and the one SRV name that is needed."""
        kind = qtype.upper()
        if kind == "SRV" and host.rstrip(".").lower() == RADIO_BROWSER_SRV:
            return await asyncio.to_thread(_radio_browser_servers)
        if kind in ("A", "AAAA"):
            family = socket.AF_INET if kind == "A" else socket.AF_INET6
            infos = await _addresses(host, family)
            return [AddressRecord(info[4][0], type=kind) for info in infos]
        raise error.DNSError(4, f"{kind} queries aren't available on the phone")

    async def close(self) -> None:
        """Nothing to close."""

    def cancel(self) -> None:
        """Nothing to cancel."""


async def _addresses(host: str, family: int) -> list[Any]:
    try:
        return await asyncio.get_running_loop().getaddrinfo(host, None, family=family, type=socket.SOCK_STREAM)
    except socket.gaierror as err:
        raise error.DNSError(4, str(err)) from err


def _radio_browser_servers() -> list[SrvRecord]:
    try:
        infos = socket.getaddrinfo(RADIO_BROWSER_ALL, 443, type=socket.SOCK_STREAM)
    except socket.gaierror as err:
        raise error.DNSError(4, str(err)) from err
    records: list[SrvRecord] = []
    for address in dict.fromkeys(info[4][0] for info in infos):
        try:
            name = socket.gethostbyaddr(address)[0]
        except OSError:
            continue
        if name.endswith("radio-browser.info"):
            records.append(SrvRecord(name, 443))
    if not records:
        raise error.DNSError(4, "no Radio Browser server found")
    return records
