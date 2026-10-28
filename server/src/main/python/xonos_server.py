"""
Il server di Music Assistant dentro Xonos.

Xonos lo avvia come processo a sé, dal pacchetto scaricato con "Fai diventare Xonos il tuo server
Music Assistant": `python3 -m xonos_server --data <cartella> --cache <cartella>`. Gira con un ciclo asyncio
suo e risponde solo sul telefono stesso (127.0.0.1, porta 8095 per l'API e 8097 per i flussi) come un
Music Assistant qualsiasi: Xonos, e myCar attraverso Xonos, ci si collegano come a quello di casa. I dati
(account, servizi, libreria) stanno nella cartella dell'app; non si annuncia in rete.

Su stdout dice a Xonos a che punto è, una riga per volta: "XONOS_STATE starting", "running", "stopped" o
"failed <perché>". SIGTERM lo ferma come si deve.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import signal
import subprocess
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from logging.handlers import RotatingFileHandler
from pathlib import Path

LOG_FORMAT = "%(asctime)s.%(msecs)03d %(levelname)s (%(threadName)s) [%(name)s] %(message)s"
LOG_FILE_BYTES = 2_000_000
STATE_PREFIX = "XONOS_STATE"

_lock = threading.Lock()
_thread: threading.Thread | None = None
_loop: asyncio.AbstractEventLoop | None = None
_stop_event: asyncio.Event | None = None
_state = "stopped"
_error = ""


def main(argv: list[str] | None = None) -> int:
    """Avvia il server, dice a Xonos come va e si ferma con SIGTERM (o SIGINT)."""
    parser = argparse.ArgumentParser(prog="xonos_server")
    parser.add_argument("--data", required=True, help="Dove il server tiene impostazioni e libreria")
    parser.add_argument("--cache", required=True, help="Dove tiene quello che può riscaricare")
    parser.add_argument("--log-level", default="INFO")
    args = parser.parse_args(argv)
    _tools_through_linker()
    stopping = threading.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: stopping.set())
    _say("starting")
    start(args.data, args.cache, log_level=args.log_level)
    said = "starting"
    while True:
        if stopping.wait(0.2):
            stop()
            break
        now = state()
        if now != said:
            _say(now if now != "failed" else f"failed {error()}")
            said = now
        if now in ("failed", "stopped"):
            break
    final = state()
    if final != said:
        _say(final if final != "failed" else f"failed {error()}")
    return 1 if final == "failed" else 0


def start(
    data_dir: str, cache_dir: str, tools_dir: str = "", library_dir: str = "", log_level: str = "INFO"
) -> None:
    """
    Avvia il server in un thread suo e torna subito; se gira già non fa niente.

    :param data_dir: Dove il server tiene impostazioni e libreria.
    :param cache_dir: Dove tiene quello che può riscaricare.
    :param tools_dir: Una cartella con i programmi che chiama (ffmpeg, ffprobe), messa prima nel PATH.
    :param library_dir: Dove stanno le librerie di quei programmi (LD_LIBRARY_PATH).
    :param log_level: Il livello del registro del server.
    """
    global _thread, _state, _error
    with _lock:
        if _thread is not None and _thread.is_alive():
            return
        _state = "starting"
        _error = ""
        if tools_dir and tools_dir not in os.environ.get("PATH", "").split(os.pathsep):
            os.environ["PATH"] = tools_dir + os.pathsep + os.environ.get("PATH", "")
        if library_dir:
            os.environ["LD_LIBRARY_PATH"] = library_dir
        _thread = threading.Thread(
            target=_run, args=(data_dir, cache_dir, log_level), name="music-assistant", daemon=True
        )
        _thread.start()


def state() -> str:
    """Dice se il server sta partendo (starting), gira (running), si è fermato per un errore (failed) o no (stopped)."""
    return _state


def error() -> str:
    """Dice perché il server si è fermato o non è partito, se è successo."""
    return _error


def stop(timeout: float = 15.0) -> None:
    """Ferma il server e lo aspetta, al massimo [timeout] secondi."""
    loop, event, thread = _loop, _stop_event, _thread
    if loop is not None and event is not None:
        loop.call_soon_threadsafe(event.set)
    if thread is not None:
        thread.join(timeout)


def _say(what: str) -> None:
    print(f"{STATE_PREFIX} {what}", flush=True)


def _tools_through_linker() -> None:
    """
    Da Android 10 un'app non può avviare i programmi che ha scaricato (W^X): li fa partire il linker di
    sistema, `/system/bin/linker64 <programma> <argomenti>`. Xonos dice quale (XONOS_LINKER) e dove stanno i
    programmi del pacchetto (XONOS_BIN); qui ogni processo avviato con uno di quei programmi passa da lui.
    Music Assistant avvia ffmpeg, ffprobe e librespot con subprocess (anche asyncio passa da lì).
    """
    linker = os.environ.get("XONOS_LINKER", "")
    bin_dir = os.environ.get("XONOS_BIN", "")
    if not bin_dir:
        return
    bin_dir = os.path.realpath(bin_dir)
    # Python stesso è uno di quei programmi: chi lo riavvia (sys.executable) passa dal linker come gli altri.
    python = os.path.join(bin_dir, "python3")
    if os.path.exists(python):
        sys.executable = python
    if not linker:
        return

    def through(args: object) -> object:
        if isinstance(args, (str, bytes, os.PathLike)):
            return args
        try:
            args = list(args)  # type: ignore[call-overload]
        except TypeError:
            return args
        if not args:
            return args
        program = os.fsdecode(args[0])
        path = os.path.abspath(program if os.sep in program else os.path.join(bin_dir, program))
        if os.path.realpath(os.path.dirname(path)) != bin_dir or not os.path.isfile(path):
            return args
        return [linker, path, *args[1:]]

    class Popen(subprocess.Popen):  # type: ignore[type-arg]
        def __init__(self, args, *rest, **kwargs):  # type: ignore[no-untyped-def]
            if not kwargs.get("shell"):
                args = through(args)
            super().__init__(args, *rest, **kwargs)

    subprocess.Popen = Popen  # type: ignore[misc]


def _run(data_dir: str, cache_dir: str, log_level: str) -> None:
    global _loop, _stop_event, _state, _error
    try:
        Path(data_dir).mkdir(parents=True, exist_ok=True)
        Path(cache_dir).mkdir(parents=True, exist_ok=True)
        _setup_logging(data_dir, log_level)
        # Importato solo ora: caricare Music Assistant richiede qualche secondo.
        from music_assistant.helpers.logging import activate_log_queue_handler  # noqa: PLC0415
        from music_assistant.mass import MusicAssistant  # noqa: PLC0415

        mass = MusicAssistant(data_dir, cache_dir, False)

        async def run_mass() -> None:
            global _loop, _stop_event, _state
            _loop = asyncio.get_running_loop()
            _loop.set_default_executor(ThreadPoolExecutor(max_workers=16))
            activate_log_queue_handler()
            _stop_event = asyncio.Event()
            try:
                await mass.start()
                _state = "running"
                logging.getLogger("xonos").info("Music Assistant pronto sul telefono")
                await _stop_event.wait()
            finally:
                await mass.stop()

        asyncio.run(run_mass())
        _state = "stopped"
    except BaseException as err:  # noqa: BLE001 - qualunque cosa lo fermi, Xonos deve sapere perché
        _state = "failed"
        _error = f"{type(err).__name__}: {err}"
        logging.getLogger("xonos").exception("Music Assistant si è fermato")
    finally:
        _loop = None
        _stop_event = None


def _setup_logging(data_dir: str, log_level: str) -> None:
    root = logging.getLogger()
    if getattr(root, "_xonos", False):
        return
    # stderr arriva a Xonos, che lo mette nel logcat; il file resta nella cartella dei dati.
    logging.basicConfig(level=logging.INFO, format=LOG_FORMAT)
    logging.captureWarnings(True)
    file_handler = RotatingFileHandler(
        os.path.join(data_dir, "musicassistant.log"), maxBytes=LOG_FILE_BYTES, backupCount=1
    )
    file_handler.setFormatter(logging.Formatter(LOG_FORMAT))
    root.addHandler(file_handler)
    logging.getLogger("music_assistant").setLevel(log_level.upper())
    root._xonos = True  # type: ignore[attr-defined]


if __name__ == "__main__":
    sys.exit(main())
