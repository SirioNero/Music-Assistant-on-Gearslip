"""
Il pacchetto di Music Assistant sul telefono, quello che Xonos scarica con "Fai diventare Xonos il tuo server
Music Assistant": dall'AAR del modulo server (Python 3.13 di Chaquopy, Music Assistant, i pacchetti, FFmpeg e
librespot) una cartella per un'ABI, compressa in uno zip.

    python3 make_payload.py --aar server-release.aar --abi arm64-v8a --ndk <NDK> --version 2.10.8-1 --out <zip>

Dentro lo zip:
    bin/       python3 (compilato qui con l'NDK), ffmpeg, ffprobe, librespot: Xonos li avvia attraverso il
               linker di sistema (da Android 10 un'app non può avviare i programmi scaricati)
    lib/       libpython3.13.so e le altre librerie (LD_LIBRARY_PATH), la libreria standard (python313.zip),
               i suoi moduli nativi (python3.13/lib-dynload), i certificati (cacert.pem)
    app/       Music Assistant preparato da ma/prepare.py e xonos_server.py
    site-packages/  i pacchetti di requirements.txt, installati per Android da Chaquopy
    payload.json    versione, ABI e Python

Lo zip è sempre uguale a parità di contenuto: voci in ordine e date fisse.
"""

from __future__ import annotations

import argparse
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

PYTHON = "3.13"
FIXED_TIME = (1980, 1, 1, 0, 0, 0)
# I programmi che Android installa dall'APK come librerie (lib<nome>.so): qui tornano programmi.
PROGRAMS = {"libffmpeg.so": "ffmpeg", "libffprobe.so": "ffprobe", "liblibrespot.so": "librespot"}
# Il ponte Java di Chaquopy: fuori dall'app non serve.
SKIP_LIBS = {"libchaquopy_java.so"}
TRIPLES = {"arm64-v8a": "aarch64-linux-android", "x86_64": "x86_64-linux-android"}
MIN_SDK = 26


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--aar", required=True, help="L'AAR del modulo server (o un APK che lo contiene)")
    parser.add_argument("--abi", required=True, choices=sorted(TRIPLES))
    parser.add_argument("--version", required=True, help="La versione del pacchetto (xonosServerPackage)")
    parser.add_argument("--out", required=True, help="Lo zip da scrivere")
    parser.add_argument("--ndk", help="L'NDK, per compilare python3")
    parser.add_argument("--launcher", help="Un python3 già compilato, invece di compilarlo")
    args = parser.parse_args()
    if not args.ndk and not args.launcher:
        sys.exit("Serve --ndk (per compilare python3) o --launcher")
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp) / "payload"
        build(Path(args.aar), args.abi, args.version, root)
        python3 = root / "bin" / "python3"
        if args.launcher:
            shutil.copyfile(args.launcher, python3)
        else:
            compile_launcher(Path(args.ndk), args.abi, root, python3)
        write_zip(root, Path(args.out))
    size = Path(args.out).stat().st_size
    print(f"pacchetto {args.version} per {args.abi}: {args.out}, {size / 1e6:.1f} MB")


def build(aar: Path, abi: str, version: str, root: Path) -> None:
    lib = root / "lib"
    dynload = lib / f"python{PYTHON}" / "lib-dynload"
    site = root / "site-packages"
    for folder in (root / "bin", lib, dynload, site, root / "app"):
        folder.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(aar) as archive:
        names = archive.namelist()
        native = _native_prefix(names, abi)
        for name in names:
            if name.startswith(native) and name.endswith(".so") and "/" not in name[len(native):]:
                base = name[len(native):]
                if base in SKIP_LIBS:
                    continue
                target = root / "bin" / PROGRAMS[base] if base in PROGRAMS else lib / base
                target.write_bytes(archive.read(name))
        assets = "assets/chaquopy/"
        if f"{assets}stdlib-common.imy" not in names:
            sys.exit(f"{aar}: non ha le risorse di Chaquopy ({assets})")
        # La libreria standard resta compressa: Python la legge da lib/python313.zip.
        (lib / f"python{PYTHON.replace('.', '')}.zip").write_bytes(archive.read(f"{assets}stdlib-common.imy"))
        _extract(archive.read(f"{assets}stdlib-{abi}.imy"), dynload)
        boot_native = f"{assets}bootstrap-native/{abi}/"
        for name in names:
            rest = name[len(boot_native):]
            if name.startswith(boot_native) and name.endswith(".so") and "/" not in rest:
                (dynload / rest).write_bytes(archive.read(name))
        # Dal bootstrap di Chaquopy solo pkg_resources (lo usa qualche pacchetto); java/ è il suo ponte.
        _extract(archive.read(f"{assets}bootstrap.imy"), site, skip=("java/",))
        for part in ("requirements-common", f"requirements-{abi}"):
            if f"{assets}{part}.imy" in names:
                _extract(archive.read(f"{assets}{part}.imy"), site)
        _extract(archive.read(f"{assets}app.imy"), root / "app")
        (lib / "cacert.pem").write_bytes(archive.read(f"{assets}cacert.pem"))
    if not (lib / "libpython3.13.so").exists():
        sys.exit(f"{aar}: manca libpython3.13.so per {abi}")
    missing = [name for name in PROGRAMS.values() if not (root / "bin" / name).exists()]
    if missing:
        sys.exit(f"{aar}: mancano {', '.join(missing)} per {abi}")
    info = {"version": version, "abi": abi, "python": PYTHON}
    (root / "payload.json").write_text(json.dumps(info, indent=2) + "\n")


def compile_launcher(ndk: Path, abi: str, root: Path, out: Path) -> None:
    tools = ndk / "toolchains" / "llvm" / "prebuilt" / "linux-x86_64" / "bin"
    source = Path(__file__).with_name("python3.c")
    subprocess.run(
        [
            str(tools / "clang"),
            f"--target={TRIPLES[abi]}{MIN_SDK}",
            "-O2",
            "-fPIE",
            "-pie",
            "-Wl,--build-id=none",
            "-o",
            str(out),
            str(source),
            f"-L{root / 'lib'}",
            "-lpython3.13",
        ],
        check=True,
    )
    subprocess.run([str(tools / "llvm-strip"), "--strip-all", str(out)], check=True)


def write_zip(root: Path, out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    files = sorted(path for path in root.rglob("*") if path.is_file())
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files:
            name = path.relative_to(root).as_posix()
            entry = zipfile.ZipInfo(name, FIXED_TIME)
            entry.compress_type = zipfile.ZIP_DEFLATED
            # Chi estrae (Xonos) rende eseguibili i file di bin/; il permesso resta scritto anche qui.
            mode = 0o755 if name.startswith("bin/") else 0o644
            entry.external_attr = (0o100000 | mode) << 16
            archive.writestr(entry, path.read_bytes())


def _native_prefix(names: list[str], abi: str) -> str:
    for prefix in (f"jni/{abi}/", f"lib/{abi}/"):
        if any(name.startswith(prefix) for name in names):
            return prefix
    sys.exit(f"Non ci sono librerie per {abi}")


def _extract(data: bytes, target: Path, skip: tuple[str, ...] = ()) -> None:
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for entry in archive.infolist():
            name = entry.filename
            if entry.is_dir() or name.startswith(skip):
                continue
            path = (target / name).resolve()
            if not str(path).startswith(str(target.resolve()) + os.sep):
                sys.exit(f"Voce fuori dalla cartella: {name}")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(archive.read(entry))


if __name__ == "__main__":
    main()
