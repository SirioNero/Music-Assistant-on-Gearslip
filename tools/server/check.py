"""
Prova un server di Music Assistant come lo usa Xonos: di solito quello dentro Xonos (server/).

Crea il primo account (se il server è nuovo), mette una cartella di musica come servizio, aspetta i
brani, si collega a Sendspin come il lettore di Xonos (solo PCM a 16 bit, 48 kHz) e suona il primo
brano. Alla fine dice in che formato è arrivato l'audio, quanti secondi ne sono arrivati e se è suono
o silenzio; esce con 1 se l'audio non arriva.

    python3 check.py --url http://127.0.0.1:8095 --music <cartella vista dal server> [--seconds 10]

Con --player (e il --token dell'app) suona invece sul lettore vero di un'app, Xonos, e guarda dalla sua
coda in Music Assistant se suona davvero: stato e secondi suonati.

Con --spotify prova anche il primo tratto del flusso di Spotify del server di Xonos: chiede il Client ID
dell'app Spotify della persona e lo usa per entrare (più avanti serve un account Spotify vero). Col lettore
di prova controlla anche che, quando non c'è niente da suonare, Music Assistant dica perché.

Sull'emulatore il server dentro Xonos si raggiunge con `adb forward tcp:8095 tcp:8095`. Serve aiohttp.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import math
import secrets
import struct
import sys
import time
import uuid
from typing import Any
from urllib.parse import parse_qs, urlparse

import aiohttp

RATE = 48000
AUDIO_CHUNK = 4
AUDIO_HEADER = 9


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8095")
    parser.add_argument("--music", help="La cartella con la musica, come la vede il server")
    parser.add_argument("--token", help="Un token del server, se l'account c'è già")
    parser.add_argument("--player", help="Suona su questo lettore (quello di Xonos) invece che su uno di prova")
    parser.add_argument("--seconds", type=float, default=10)
    parser.add_argument("--spotify", action="store_true", help="Prova anche l'inizio del flusso di Spotify")
    args = parser.parse_args()
    sys.exit(asyncio.run(check(args)))


async def check(args: argparse.Namespace) -> int:
    url = args.url.rstrip("/")
    ws_url = "ws" + url.removeprefix("http")
    async with aiohttp.ClientSession() as http:
        info = await _wait_info(http, url)
        print(f"server {info.get('server_version')}, schema {info.get('schema_version')}, base {info.get('base_url')}")
        session = args.token or await _first_account(http, url)
        async with http.ws_connect(f"{ws_url}/ws", max_msg_size=0) as socket:
            api = Api(socket)
            await api.start(session)
            token = args.token or await api.call("auth/token/create", name="Xonos check")
            if not info.get("onboard_done"):
                await api.call("config/onboard_complete", ignore_errors=True)
            spotify = 0 if not args.spotify or await _spotify_flow(api) else 1
            if args.music:
                await _add_folder(api, args.music)
            tracks = await _wait_tracks(api)
            if not tracks:
                print("ERRORE: nessun brano nella libreria")
                return 1
            track = tracks[0]
            print(f"brani: {len(tracks)}; suono {track['name']} ({track['uri']})")
            if args.player:
                return max(await _play_on(api, args.player, track, args.seconds), spotify)

            player = Sendspin(http, ws_url, token)
            await player.start()
            started = time.monotonic()
            await api.call("player_queues/play_media", queue_id=player.client_id, media=track["uri"])
            await player.listen(args.seconds)
            print(f"play_media → primo audio: {player.first_audio_s(started)}")
            await api.call("player_queues/stop", queue_id=player.client_id, ignore_errors=True)
            reason = 0 if await _reason(api, player.client_id) else 1
            await player.close()
            return max(player.report(), spotify, reason)


class Api:
    """Il WebSocket /ws di Music Assistant: comandi con i loro risultati, anche a pezzi."""

    def __init__(self, socket: aiohttp.ClientWebSocketResponse) -> None:
        self._socket = socket
        self._count = 0
        self._waiting: dict[str, asyncio.Future[Any]] = {}
        self._parts: dict[str, list[Any]] = {}
        self._reader: asyncio.Task[None] | None = None

    async def start(self, token: str) -> None:
        await self._socket.receive_json()  # server_info
        self._reader = asyncio.create_task(self._read())
        await self.call("auth", token=token)

    async def call(self, command: str, ignore_errors: bool = False, **args: Any) -> Any:
        self._count += 1
        message_id = str(self._count)
        future: asyncio.Future[Any] = asyncio.get_running_loop().create_future()
        self._waiting[message_id] = future
        await self._socket.send_json({"message_id": message_id, "command": command, "args": args})
        try:
            return await asyncio.wait_for(future, 120)
        except RuntimeError as err:
            if ignore_errors:
                print(f"({command}: {err})")
                return None
            raise

    async def _read(self) -> None:
        async for message in self._socket:
            if message.type != aiohttp.WSMsgType.TEXT:
                continue
            data = json.loads(message.data)
            message_id = data.get("message_id")
            future = self._waiting.get(message_id)
            if future is None:
                continue
            if data.get("partial"):
                self._parts.setdefault(message_id, []).extend(data.get("result") or [])
                continue
            del self._waiting[message_id]
            parts = self._parts.pop(message_id, None)
            if "error_code" in data:
                future.set_exception(RuntimeError(f"{data['error_code']} {data.get('details')}"))
            else:
                result = data.get("result")
                future.set_result(parts + (result or []) if parts is not None else result)


class Sendspin:
    """Un lettore Sendspin come quello di Xonos, che prende solo PCM a 16 bit."""

    def __init__(self, http: aiohttp.ClientSession, ws_url: str, token: str) -> None:
        self._http = http
        self._url = f"{ws_url}/sendspin"
        self._token = token
        self.client_id = f"xonos-check-{uuid.uuid4().hex[:8]}"
        self._socket: aiohttp.ClientWebSocketResponse | None = None
        self._reader: asyncio.Task[None] | None = None
        self._hello = asyncio.Event()
        self._times: asyncio.Queue[int] = asyncio.Queue()
        self.formats: list[dict[str, Any]] = []
        self.bytes = 0
        self.squares = 0.0
        self.samples = 0
        self.first_audio: float | None = None

    async def start(self) -> None:
        self._socket = await self._http.ws_connect(self._url, max_msg_size=0)
        await self._socket.send_json({"type": "auth", "token": self._token, "client_id": self.client_id})
        self._reader = asyncio.create_task(self._read())
        await asyncio.wait_for(self._hello.wait(), 15)
        for _ in range(8):
            await self._send("client/time", client_transmitted=_now_us())
            await asyncio.wait_for(self._times.get(), 5)
        await self._send(
            "client/state",
            available=True,
            state="synchronized",
            player={
                "volume": 100,
                "muted": False,
                "output_delay_ms": 0,
                "required_lead_time_ms": 300,
                "min_buffer_ms": 300,
                "supported_commands": ["volume", "mute"],
            },
        )
        print(f"lettore Sendspin {self.client_id} pronto")
        await asyncio.sleep(1)

    async def listen(self, seconds: float) -> None:
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            await asyncio.sleep(1)
            await self._send("client/time", client_transmitted=_now_us())

    async def close(self) -> None:
        if self._socket is not None:
            await self._send("client/goodbye", reason="shutdown")
            await self._socket.close()
        if self._reader is not None:
            await self._reader

    def first_audio_s(self, started: float) -> str:
        return "mai" if self.first_audio is None else f"{self.first_audio - started:.2f} s"

    def report(self) -> int:
        seconds = self.bytes / (RATE * 2 * 2)
        rms = math.sqrt(self.squares / self.samples) if self.samples else 0.0
        print(f"formati annunciati dal server: {self.formats}")
        print(f"audio arrivato: {seconds:.1f} s ({self.bytes} byte), livello RMS {rms:.0f} su 32767")
        if not self.formats or seconds < 1:
            print("ERRORE: l'audio non è arrivato")
            return 1
        if any(f.get("codec") != "pcm" or f.get("bit_depth") != 16 for f in self.formats):
            print("ERRORE: il formato non è PCM a 16 bit")
            return 1
        print("suono" if rms > 100 else "ATTENZIONE: silenzio")
        return 0

    async def _send(self, kind: str, **payload: Any) -> None:
        assert self._socket is not None
        await self._socket.send_json({"type": kind, "payload": payload})

    async def _read(self) -> None:
        assert self._socket is not None
        async for message in self._socket:
            if message.type == aiohttp.WSMsgType.BINARY:
                self._audio(message.data)
                continue
            if message.type != aiohttp.WSMsgType.TEXT:
                continue
            data = json.loads(message.data)
            kind = data.get("type")
            payload = data.get("payload") or {}
            if kind == "auth_ok":
                await self._send(
                    "client/hello",
                    client_id=self.client_id,
                    version=1,
                    name="Xonos check",
                    device_info={"product_name": "Mobile Application", "manufacturer": "Xonos", "software_version": "check"},
                    supported_roles=["player@v1"],
                    **{
                        "player@v1_support": {
                            "supported_formats": [{"codec": "pcm", "channels": 2, "sample_rate": RATE, "bit_depth": 16}],
                            "buffer_capacity": 2_000_000,
                            "supported_commands": ["volume", "mute"],
                        }
                    },
                    unpaired_access={"enabled": False},
                )
            elif kind == "server/hello":
                print(f"Sendspin: {payload.get('name')}, ruoli {payload.get('active_roles')}")
                self._hello.set()
            elif kind == "server/time":
                self._times.put_nowait(payload.get("client_transmitted", 0))
            elif kind == "stream/start" and payload.get("player"):
                self.formats.append(payload["player"])
                print(f"stream/start: {payload['player']}")
            elif kind in ("stream/end", "stream/clear"):
                print(kind)
            elif kind not in ("group/update", "server/state", "server/command"):
                print(f"({kind})")

    def _audio(self, data: bytes) -> None:
        if len(data) <= AUDIO_HEADER or data[0] != AUDIO_CHUNK:
            return
        if self.first_audio is None:
            self.first_audio = time.monotonic()
        pcm = data[AUDIO_HEADER:]
        self.bytes += len(pcm)
        count = len(pcm) // 2
        for (sample,) in struct.iter_unpack("<h", pcm[: count * 2]):
            self.squares += sample * sample
        self.samples += count


async def _wait_info(http: aiohttp.ClientSession, url: str) -> dict[str, Any]:
    for _ in range(120):
        try:
            async with http.get(f"{url}/info") as response:
                if response.status == 200:
                    return await response.json()
        except aiohttp.ClientError:
            pass
        await asyncio.sleep(1)
    raise SystemExit(f"il server {url} non risponde")


async def _first_account(http: aiohttp.ClientSession, url: str) -> str:
    password = secrets.token_urlsafe(16)
    body = {"username": "xonos", "password": password, "device_name": "Xonos check"}
    async with http.post(f"{url}/setup", json=body) as response:
        if response.status != 200:
            raise SystemExit(f"POST /setup: {response.status} {await response.text()}")
        print("primo account creato")
        return (await response.json())["token"]


async def _add_folder(api: Api, folder: str) -> None:
    step = await api.call("config/providers/setup", provider_domain="filesystem_local")
    if isinstance(step, dict) and step.get("type") == "form":
        await api.call("config/flows/submit", flow_id=step["flow_id"], values={"content_type": "music", "path": folder})
    await api.call("music/sync")


async def _wait_tracks(api: Api) -> list[dict[str, Any]]:
    tracks: list[dict[str, Any]] = []
    for _ in range(60):
        tracks = await api.call("music/tracks/library_items", limit=10) or []
        if tracks:
            break
        await asyncio.sleep(2)
    return tracks


async def _reason(api: Api, queue_id: str) -> bool:
    """Un brano che non c'è: Music Assistant deve dire perché non c'è niente da suonare, non solo che non c'è."""
    try:
        await api.call("player_queues/play_media", queue_id=queue_id, media="library://track/999999", option="add")
    except RuntimeError as err:
        print(f"niente da suonare: {err}")
        if "nothing to play here:" in str(err):
            return True
        print("ERRORE: Music Assistant non dice perché")
        return False
    print("ERRORE: un brano che non c'è è finito in coda")
    return False


async def _spotify_flow(api: Api) -> bool:
    """Il flusso di Spotify fino al link di Spotify: il primo passo chiede il Client ID, il link lo usa."""
    client_id = "0123456789abcdef0123456789abcdef"
    step = await api.call("config/providers/setup", provider_domain="spotify")
    flow = step["flow_id"]
    try:
        print(f"spotify: primo passo {step.get('step_id')} ({step.get('title')})")
        if step.get("step_id") != "xonos_client_id":
            print("ERRORE: Spotify non chiede il Client ID")
            return False
        step = await api.call("config/flows/submit", flow_id=flow, values={"xonos_client_id": client_id})
        query = parse_qs(urlparse(step.get("url") or "").query)
        print(f"spotify: poi {step.get('step_id')}, client_id {query.get('client_id')}, ritorno {query.get('redirect_uri')}")
        if step.get("step_id") != "authenticate" or query.get("client_id") != [client_id]:
            print("ERRORE: il link di Spotify non ha il Client ID")
            return False
        return True
    finally:
        await api.call("config/flows/abort", flow_id=flow, ignore_errors=True)


async def _play_on(api: Api, queue_id: str, track: dict[str, Any], seconds: float) -> int:
    """Suona sul lettore di un'app e guarda dalla sua coda se suona: stato e secondi suonati."""
    queue: dict[str, Any] = {}
    for _ in range(60):
        queue = await api.call("player_queues/get", queue_id=queue_id) or {}
        if queue.get("available"):
            break
        await asyncio.sleep(2)
    else:
        print(f"ERRORE: il lettore {queue_id} non c'è in Music Assistant")
        return 1
    print(f"lettore {queue.get('display_name')} ({queue_id}) pronto")
    await api.call("player_queues/play_media", queue_id=queue_id, media=track["uri"])
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        await asyncio.sleep(1)
        queue = await api.call("player_queues/get", queue_id=queue_id) or {}
    state = queue.get("state")
    elapsed = queue.get("elapsed_time") or 0.0
    if state == "playing":
        # Il tempo della coda è quello dell'ultimo aggiornamento: da lì il brano è andato avanti.
        elapsed += max(0.0, time.time() - (queue.get("elapsed_time_last_updated") or time.time()))
    print(f"lettore {queue_id}: {state}, {elapsed:.1f} s suonati")
    await api.call("player_queues/stop", queue_id=queue_id, ignore_errors=True)
    if state != "playing" or elapsed < seconds / 2:
        print("ERRORE: il lettore non suona")
        return 1
    print("suona")
    return 0


def _now_us() -> int:
    return time.monotonic_ns() // 1000


if __name__ == "__main__":
    main()
