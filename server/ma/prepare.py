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
import ast
import io
import shutil
import sys
import tarfile
import tokenize
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
    # musica: i file del telefono e i servizi le cui librerie ci sono per Android (vedi requirements.txt).
    # Fuori: Apple Music (DRM), Deezer (per ora), NFS e SMB (vogliono mount), YouTube Music (deno),
    # nicovideo (pydantic 2, in Rust), QQ Music (cryptography 47), BBC Sounds e iBroadcast (solo 3.14).
    "abc_radio_network",
    "ambient_sounds",
    "ard_audiothek",
    "audiobookshelf",
    "bandcamp",
    "digitally_incorporated",
    "emby",
    "filesystem_cloud",  # senza manifest: la base comune di Google Drive e OneDrive
    "filesystem_google_drive",
    "filesystem_local",
    "filesystem_onedrive",
    "gpodder",
    "internet_archive",
    "itunes_podcasts",
    "jellyfin",
    "musicme",
    "neteasecloudmusic",
    "nts",
    "nugs",
    "opensubsonic",
    "orf_radiothek",
    "overcast",
    "pandora",
    "phishin",
    "plex",
    "pocketcasts",
    "podcast_index",
    "podcastfeed",
    "qobuz",
    "radiobrowser",
    "radioparadise",
    "siriusxm",
    "somafm",
    "soundcloud",
    "spotify",  # con librespot compilato per Android (server/librespot); Soloist è solo per Linux
    "storytel",
    "sverigesradio",
    "tidal",
    "tunein",
    "webdav",
    "zvuk_music",
    # metadati e altro
    "genius_lyrics",
    "lastfm_recommendations",
    "lastfm_scrobble",
    "listenbrainz_scrobble",
    "smart_playlist",
    "subsonic_scrobble",
]

# Cartelle che altri servizi importano, ma che sul telefono non sono servizi da aggiungere: si copiano
# senza manifest, così Music Assistant non le propone. Spotify Connect vuole go-librespot e l'mDNS.
LIBRARIES = ["spotify_connect"]

# Cartelle e file del sorgente che sul telefono non servono (bin: programmi per Linux e macOS).
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
    for library in LIBRARIES:
        (target / "providers" / library / "manifest.json").unlink()
    _patch(target)
    rewritten = _python313(target)
    marker.write_text(_fingerprint(args.version))
    print(
        f"Music Assistant {args.version} per Xonos in {target} ({len(PROVIDERS)} servizi,"
        f" {rewritten} file riscritti per Python 3.13)"
    )


def _fingerprint(version: str) -> str:
    return f"{version} {' '.join(PROVIDERS + LIBRARIES)} {Path(__file__).stat().st_mtime_ns}"


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
            kept = set(PROVIDERS) | set(LIBRARIES)
            skipped |= {name for name in names if (providers / name).is_dir() and name not in kept}
        return skipped

    return ignore


def _python313(root: Path) -> int:
    """
    Riscrive quello che Python 3.13 non legge e controlla che legga ogni file.

    Music Assistant è scritto per Python 3.14, ma i pacchetti con parti native (Pillow,
    cryptography) per Android ci sono compilati solo fino al 3.13. Del 3.14 il suo codice usa due
    cose: `except A, B:` senza parentesi (PEP 758), che qui diventa `except (A, B):`, e le
    annotazioni dei tipi lette solo quando servono (PEP 649), che nel 3.13 si hanno con
    `from __future__ import annotations` in testa al file.
    """
    rewritten = 0
    for path in sorted(root.rglob("*.py")):
        text = original = path.read_text()
        if "except" in text:
            text = _bracket_excepts(text)
        try:
            tree = ast.parse(text, str(path))
        except SyntaxError as err:
            sys.exit(f"{path}:{err.lineno}: Python {sys.version_info[0]}.{sys.version_info[1]} non lo legge: {err.msg}")
        if FUTURE_ANNOTATIONS not in text and _annotated(tree):
            text = _with_future_annotations(text, tree)
        if text != original:
            compile(text, str(path), "exec", dont_inherit=True)
            path.write_text(text)
            rewritten += 1
    return rewritten


FUTURE_ANNOTATIONS = "from __future__ import annotations"


def _annotated(tree: ast.Module) -> bool:
    for node in ast.walk(tree):
        if isinstance(node, ast.AnnAssign):
            return True
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            arguments = node.args
            every = [*arguments.posonlyargs, *arguments.args, *arguments.kwonlyargs, arguments.vararg, arguments.kwarg]
            if node.returns is not None or any(arg is not None and arg.annotation is not None for arg in every):
                return True
    return False


def _with_future_annotations(text: str, tree: ast.Module) -> str:
    # Dopo la docstring del modulo e prima di qualsiasi altra istruzione, come vuole Python.
    lines = text.splitlines(keepends=True)
    after = 0
    first = tree.body[0] if tree.body else None
    if isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant) and isinstance(first.value.value, str):
        after = first.end_lineno or 0
    lines.insert(after, FUTURE_ANNOTATIONS + "\n")
    return "".join(lines)


def _bracket_excepts(text: str) -> str:
    tokens = list(tokenize.generate_tokens(io.StringIO(text).readline))
    spans: list[tuple[tuple[int, int], tuple[int, int]]] = []
    index = 0
    while index < len(tokens):
        token = tokens[index]
        index += 1
        if token.type != tokenize.NAME or token.string != "except":
            continue
        if tokens[index].string == "*":
            index += 1
        first = index
        depth = 0
        comma = named = False
        while index < len(tokens):
            part = tokens[index]
            if part.type == tokenize.OP and part.string in "([{":
                depth += 1
            elif part.type == tokenize.OP and part.string in ")]}":
                depth -= 1
            elif depth == 0 and part.type == tokenize.OP and part.string == ",":
                comma = True
            elif depth == 0 and part.type == tokenize.NAME and part.string == "as":
                named = True
            elif depth == 0 and part.type == tokenize.OP and part.string == ":":
                break
            index += 1
        if comma and not named:
            spans.append((tokens[first].start, tokens[index - 1].end))
    lines = text.splitlines(keepends=True)
    for (start_row, start_col), (end_row, end_col) in reversed(spans):
        end = lines[end_row - 1]
        lines[end_row - 1] = end[:end_col] + ")" + end[end_col:]
        start = lines[start_row - 1]
        lines[start_row - 1] = start[:start_col] + "(" + start[start_col:]
    return "".join(lines)


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
    # Lo Zeroconf che i servizi condividono resta, ma sul telefono stesso: nessun socket nella rete.
    _replace(
        discovery,
        '        return AsyncZeroconf(\n            ip_version=zc_args["ip_version"],\n'
        '            interfaces=zc_args["interfaces"],\n        )\n',
        "        # Xonos: solo il telefono stesso, nessun socket nella rete.\n"
        '        return AsyncZeroconf(ip_version=IPVersion.V4Only, interfaces=["127.0.0.1"])\n',
    )
    _replace(discovery, "from zeroconf import (\n", "from zeroconf import (\n    IPVersion,\n")
    # Sendspin: Music Assistant prepara l'audio in float a 32 bit e aiosendspin lo converte per ogni
    # lettore con PyAV, che per Android non c'è. Sul telefono il lettore è Xonos, che prende PCM a
    # 16 bit: l'audio si prepara già così (ffmpeg converte) e aiosendspin lo passa com'è.
    playback = root / "providers" / "sendspin" / "playback.py"
    for indent, rate in (("", "48000"), ("        ", "sample_rate")):
        _replace(
            playback,
            f"{indent}    content_type=ContentType.PCM_F32LE,\n{indent}    sample_rate={rate},\n"
            f"{indent}    bit_depth=32,\n",
            f"{indent}    content_type=ContentType.PCM_S16LE,  # Xonos: PCM a 16 bit\n"
            f"{indent}    sample_rate={rate},\n{indent}    bit_depth=16,\n",
        )
        _replace(
            playback,
            f"{indent}    sample_rate={rate},\n{indent}    bit_depth=32,\n{indent}    channels=2,\n"
            f'{indent}    sample_type="float",\n',
            f"{indent}    sample_rate={rate},\n{indent}    bit_depth=16,\n{indent}    channels=2,\n"
            f'{indent}    sample_type="int",  # Xonos: PCM a 16 bit\n',
        )
    # Spotify: librespot per Android non sta nella cartella bin del servizio ma tra i programmi
    # dell'app, che Xonos mette nel PATH (vedi server/librespot e LocalServer).
    spotify = root / "providers" / "spotify" / "helpers.py"
    _replace(
        spotify,
        '    msg = f"Unable to locate Librespot for {system}/{architecture}"\n',
        "    # Xonos: su Android librespot è tra i programmi dell'app, nel PATH.\n"
        '    if (on_path := shutil.which("librespot")) and (librespot_binary := await check_librespot(on_path)):\n'
        "        return librespot_binary\n\n"
        '    msg = f"Unable to locate Librespot for {system}/{architecture}"\n',
    )
    _replace(spotify, "import re\nimport tempfile\n", "import re\nimport shutil\nimport tempfile\n")


if __name__ == "__main__":
    main()
