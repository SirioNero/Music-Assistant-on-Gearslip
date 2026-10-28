"""
Il server di Music Assistant dentro Xonos.

LocalServer.kt lo avvia con start(): gira in un thread suo, con un ciclo asyncio suo, e risponde solo
sul telefono stesso (127.0.0.1, porta 8095 per l'API e 8097 per i flussi) come un Music Assistant
qualsiasi: Xonos, e myCar attraverso Xonos, ci si collegano come a quello di casa. I dati (account,
servizi, libreria) stanno nella cartella dell'app; non si annuncia in rete.
"""

from __future__ import annotations

import asyncio
import logging
import os
import threading
from concurrent.futures import ThreadPoolExecutor
from logging.handlers import RotatingFileHandler
from pathlib import Path

LOG_FORMAT = "%(asctime)s.%(msecs)03d %(levelname)s (%(threadName)s) [%(name)s] %(message)s"
LOG_FILE_BYTES = 2_000_000

_lock = threading.Lock()
_thread: threading.Thread | None = None
_loop: asyncio.AbstractEventLoop | None = None
_stop_event: asyncio.Event | None = None
_state = "stopped"
_error = ""


def start(data_dir: str, cache_dir: str, tools_dir: str = "", log_level: str = "INFO") -> None:
    """
    Start the server in a thread of its own, and return at once; a call while it runs does nothing.

    :param data_dir: Where the server keeps its settings and library.
    :param cache_dir: Where it keeps what it can download again.
    :param tools_dir: A folder with the programs it runs (ffmpeg), put first on the PATH.
    :param log_level: The level of the server's own log.
    """
    global _thread, _state, _error
    with _lock:
        if _thread is not None and _thread.is_alive():
            return
        _state = "starting"
        _error = ""
        if tools_dir:
            os.environ["PATH"] = tools_dir + os.pathsep + os.environ.get("PATH", "")
        _thread = threading.Thread(
            target=_run, args=(data_dir, cache_dir, log_level), name="music-assistant", daemon=True
        )
        _thread.start()


def state() -> str:
    """Return starting, running, failed or stopped."""
    return _state


def error() -> str:
    """Return why the server stopped or couldn't start, if it did."""
    return _error


def stop(timeout: float = 15.0) -> None:
    """Stop the server and wait for it, at most [timeout] seconds."""
    loop, event, thread = _loop, _stop_event, _thread
    if loop is not None and event is not None:
        loop.call_soon_threadsafe(event.set)
    if thread is not None:
        thread.join(timeout)


def _run(data_dir: str, cache_dir: str, log_level: str) -> None:
    global _loop, _stop_event, _state, _error
    try:
        Path(data_dir).mkdir(parents=True, exist_ok=True)
        Path(cache_dir).mkdir(parents=True, exist_ok=True)
        _setup_logging(data_dir, log_level)
        # Imported only now: loading Music Assistant takes a few seconds.
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
    except BaseException as err:  # noqa: BLE001 - whatever stops it, Xonos has to know why
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
    # stderr finisce nel logcat (python.stderr); il file resta nella cartella dei dati.
    logging.basicConfig(level=logging.INFO, format=LOG_FORMAT)
    logging.captureWarnings(True)
    file_handler = RotatingFileHandler(
        os.path.join(data_dir, "musicassistant.log"), maxBytes=LOG_FILE_BYTES, backupCount=1
    )
    file_handler.setFormatter(logging.Formatter(LOG_FORMAT))
    root.addHandler(file_handler)
    logging.getLogger("music_assistant").setLevel(log_level.upper())
    root._xonos = True  # type: ignore[attr-defined]
