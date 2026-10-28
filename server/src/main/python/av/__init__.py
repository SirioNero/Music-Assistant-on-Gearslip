"""
Al posto di PyAV (FFmpeg dentro Python), che per Android non c'è.

aiosendspin, il motore di Sendspin in Music Assistant, importa sempre PyAV, ma col server dentro
Xonos non lo usa: l'audio arriva già nel formato del lettore (PCM a 16 bit, vedi
server/ma/prepare.py) e passa com'è. Opus e FLAC risultano non disponibili, come con un PyAV senza
quei codec; se un giorno servisse altro, l'errore lo dice.
"""

from __future__ import annotations

from typing import Any

__version__ = "0+xonos"


class PyAVMissing(AttributeError):
    """Qualcosa di PyAV che sul telefono non c'è."""


class _Codecs:
    """av.codec, senza codec."""

    class Codec:
        """Come av.codec.Codec con un codec che manca: ValueError."""

        def __init__(self, name: str, mode: str = "r") -> None:
            raise ValueError(f"codec {name!r} non disponibile: PyAV non c'è sul telefono")


codec = _Codecs()


def __getattr__(name: str) -> Any:
    raise PyAVMissing(f"PyAV non c'è sul telefono (av.{name})")
