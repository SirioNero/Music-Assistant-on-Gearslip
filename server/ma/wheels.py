"""
Costruisce le wheel che pip deve trovare per il server di Xonos al posto di quelle che per Android
non ci sono: orjson e aiodns (in Rust e in C) diventano i moduli in Python di server/wheels, che
fanno quello che Music Assistant e le sue librerie chiedono loro, e zeroconf, che su PyPI per
Android ha solo il sorgente, diventa la sua versione in Python puro (la stessa, senza le parti
compilate con Cython). Le librerie che li vogliono (music-assistant-models, aiosendspin, radios)
li trovano così per nome e versione.

    python3 wheels.py --out <cartella>

Le wheel sono sempre uguali byte per byte: stessi file, stesso ordine, date fisse.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import io
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
SOURCES = HERE.parent / "wheels"

# Il nome, la versione con cui si presentano e la cartella con i loro moduli.
SHIMS = [
    ("orjson", "3.11.6+xonos", "orjson"),
    ("aiodns", "4.0.4+xonos", "aiodns"),
]
ZEROCONF = ("zeroconf", "0.149.16", ["ifaddr>=0.1.7"])
FIXED_TIME = (1980, 1, 1, 0, 0, 0)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    for name, version, folder in SHIMS:
        files = _tree(SOURCES / folder)
        _write(out, name, version, files, [], None)
    name, version, requires = ZEROCONF
    if not any(out.glob(f"{name}-{version}-*.whl")):
        files, license_text = _pure_from_sdist(name, version)
        _write(out, name, version, files, requires, license_text)
    print("Wheel per il server di Xonos:", ", ".join(sorted(p.name for p in out.glob("*.whl"))))


def _tree(folder: Path) -> dict[str, bytes]:
    files = {}
    for path in sorted(folder.rglob("*")):
        if path.is_file() and "__pycache__" not in path.parts:
            files[path.relative_to(folder).as_posix()] = path.read_bytes()
    if not files:
        sys.exit(f"Nessun file in {folder}")
    return files


def _pure_from_sdist(name: str, version: str) -> tuple[dict[str, bytes], str]:
    url = f"https://files.pythonhosted.org/packages/source/{name[0]}/{name}/{name}-{version}.tar.gz"
    print(f"Scarico {url}")
    with urllib.request.urlopen(url, timeout=120) as response:
        data = response.read()
    files: dict[str, bytes] = {}
    license_text = ""
    prefix = f"{name}-{version}/"
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        for member in archive.getmembers():
            if not member.isfile() or not member.name.startswith(prefix):
                continue
            path = member.name[len(prefix):]
            content = archive.extractfile(member).read()  # type: ignore[union-attr]
            if path == "COPYING":
                license_text = content.decode()
            elif path.startswith(f"src/{name}/") and path.endswith((".py", "py.typed")):
                files[path.removeprefix("src/")] = content
    if f"{name}/__init__.py" not in files:
        sys.exit(f"Nel sorgente di {name} {version} non trovo src/{name}")
    return dict(sorted(files.items())), license_text


def _write(out: Path, name: str, version: str, files: dict[str, bytes], requires: list[str], license_text: str | None) -> None:
    info = f"{name}-{version}.dist-info"
    metadata = f"Metadata-Version: 2.1\nName: {name}\nVersion: {version}\n"
    metadata += "".join(f"Requires-Dist: {requirement}\n" for requirement in requires)
    entries = dict(files)
    entries[f"{info}/METADATA"] = metadata.encode()
    entries[f"{info}/WHEEL"] = b"Wheel-Version: 1.0\nGenerator: xonos\nRoot-Is-Purelib: true\nTag: py3-none-any\n"
    if license_text:
        entries[f"{info}/COPYING"] = license_text.encode()
    record = "".join(f"{path},sha256={_digest(content)},{len(content)}\n" for path, content in entries.items())
    entries[f"{info}/RECORD"] = (record + f"{info}/RECORD,,\n").encode()

    target = out / f"{name}-{version}-py3-none-any.whl"
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as wheel:
        for path, content in entries.items():
            entry = zipfile.ZipInfo(path, FIXED_TIME)
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o644 << 16
            wheel.writestr(entry, content)
    if not target.exists() or target.read_bytes() != buffer.getvalue():
        target.write_bytes(buffer.getvalue())


def _digest(content: bytes) -> str:
    return base64.urlsafe_b64encode(hashlib.sha256(content).digest()).rstrip(b"=").decode()


if __name__ == "__main__":
    main()
