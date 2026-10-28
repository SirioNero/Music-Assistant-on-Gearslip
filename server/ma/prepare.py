"""
Prepara il server di Music Assistant per Xonos: prende il sorgente di una versione (dal repository
di Music Assistant, o da una cartella), tiene il nucleo e i servizi che Xonos porta sul telefono e
applica le poche modifiche che servono su Android. Il risultato va in una cartella di sorgenti Python
che Chaquopy mette nell'APK.

    python3 prepare.py --version 2.10.8 --out <cartella> [--source <checkout di music-assistant/server>]

Le modifiche sono sostituzioni di testo controllate: se una versione nuova di Music Assistant cambia
quel codice, lo script si ferma e lo dice, invece di produrre un server rotto.
"""

from __future__ import annotations

import argparse
import io
import shutil
import sys
import tarfile
import urllib.request
from pathlib import Path

# I servizi (provider) che il server di Xonos porta con sé. Quelli "builtin" che Music Assistant
# carica sempre ci devono essere tutti, tranne sendspin_source (vuole soxr, nativo, e serve a
# ricevere audio da altri apparecchi). universal_group lo importa il nucleo.
PROVIDERS = [
    # sempre caricati
    "builtin",
    "coverartarchive",
    "fanarttv",
    "itunes_artwork",
    "loudness_analysis",
    "lrclib",
    "musicbrainz",
    "playlist_metadata",
    "radio_playlist",
    "recommendations",
    "sendspin",
    "sync_group",
    "theaudiodb",
    "universal_group",
    "universal_player",
    "wikipedia",
    # musica
    "filesystem_local",
    "itunes_podcasts",
    "podcastfeed",
    "radiobrowser",
]

# Cartelle e file del sorgente che sul telefono non servono.
SKIP_NAMES = {"__pycache__", "bin"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--source", help="Un checkout di music-assistant/server, invece di scaricarlo")
    args = parser.parse_args()

    out = Path(args.out)
    marker = out / "music_assistant" / ".xonos-version"
    if marker.is_file() and marker.read_text() == _fingerprint(args.version):
        return
    source = Path(args.source) if args.source else _download(args.version, out.parent / "ma-download")
    package = source / "music_assistant"
    if not package.is_dir():
        sys.exit(f"Non trovo music_assistant in {source}")

    target = out / "music_assistant"
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(package, target, ignore=_ignore(package))
    _patch(target)
    marker.write_text(_fingerprint(args.version))
    print(f"Music Assistant {args.version} per Xonos in {target} ({len(PROVIDERS)} servizi)")


def _fingerprint(version: str) -> str:
    return f"{version} {' '.join(PROVIDERS)} {Path(__file__).stat().st_mtime_ns}"


def _download(version: str, folder: Path) -> Path:
    unpacked = folder / f"server-{version}"
    if (unpacked / "music_assistant").is_dir():
        return unpacked
    url = f"https://github.com/music-assistant/server/archive/refs/tags/{version}.tar.gz"
    print(f"Scarico {url}")
    with urllib.request.urlopen(url, timeout=120) as response:
        data = response.read()
    folder.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        members = [m for m in archive.getmembers() if "/music_assistant/" in f"/{m.name}"]
        archive.extractall(folder, members=members, filter="data")
    return unpacked


def _ignore(package: Path):
    providers = package / "providers"

    def ignore(directory: str, names: list[str]) -> set[str]:
        skipped = {name for name in names if name in SKIP_NAMES}
        if Path(directory) == providers:
            skipped |= {name for name in names if (providers / name).is_dir() and name not in PROVIDERS}
        return skipped

    return ignore


def _replace(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        sys.exit(f"{path}: il testo da cambiare c'è {count} volte invece di una:\n{old}")
    path.write_text(text.replace(old, new))


def _patch(root: Path) -> None:
    util = root / "helpers" / "util.py"
    # Sul telefono non si installano pacchetti: quelli dei servizi sono già nell'APK, e un servizio
    # che vuole altro non si carica (lo dice l'errore dell'import).
    _replace(
        util,
        "        if installed_version != version:\n            await install_package(requirement)\n",
        "        # Xonos: i pacchetti sono quelli dentro l'app, la versione non si controlla.\n",
    )
    _replace(
        util,
        "    except ImportError:\n        # (re)install ALL requirements\n        for requirement in requirements:\n"
        "            await install_package(requirement)\n",
        "    except ImportError:\n        # Xonos: niente installazioni sul telefono.\n        raise\n",
    )
    # Su Android c-ares (aiodns) non trova i server DNS del telefono: si usa quello di sistema.
    client = root / "helpers" / "aiohttp_client.py"
    _replace(
        client,
        "from aiohttp_asyncmdnsresolver.api import AsyncDualMDNSResolver\n",
        "from aiohttp import ThreadedResolver as AsyncDualMDNSResolver  # Xonos: il DNS di Android\n",
    )
    _replace(
        client,
        "MassAsyncDNSResolver(async_zeroconf=mass.discovery.aiozc)",
        "MassAsyncDNSResolver()",
    )
    # Sul telefono il server risponde solo al telefono stesso (Xonos e myCar), mai alla rete in cui
    # il telefono si trova: API e flussi su 127.0.0.1, e i flussi pubblicati lì.
    _replace(
        root / "constants.py",
        'DEFAULT_HOST: Final[str] = "0.0.0.0"',
        'DEFAULT_HOST: Final[str] = "127.0.0.1"  # Xonos: solo il telefono stesso',
    )
    # E non si annuncia: niente mDNS (_mass._tcp) né ricerca UPnP nelle reti in cui il telefono entra.
    discovery = root / "controllers" / "discovery" / "controller.py"
    _replace(
        discovery,
        "        await self._setup_mdns_browser()\n        await self._register_mass_service()\n",
        "        # Xonos: il server sul telefono non si annuncia e non cerca casse in rete.\n",
    )
    _replace(
        discovery,
        "        self.mass.subscribe(self._on_core_state_updated, EventType.CORE_STATE_UPDATED)\n",
        "",
    )
    _replace(
        discovery,
        "            self._schedule_periodic_ha_announce()\n        self._schedule_periodic_upnp_discovery()\n",
        "            self._schedule_periodic_ha_announce()\n",
    )


if __name__ == "__main__":
    main()
