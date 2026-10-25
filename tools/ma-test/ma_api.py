"""Small helper to talk to the MA websocket API for probing."""
import asyncio, json, sys, itertools
import websockets

import os

class MA:
    def __init__(self, url="ws://127.0.0.1:8095/ws"):
        self.url = url
        self.ids = itertools.count(1)
        self.pending = {}
        self.partials = {}
        self.events = []
        self.event_cb = None

    async def connect(self, token):
        self.ws = await websockets.connect(self.url, max_size=None)
        self.server_info = json.loads(await self.ws.recv())
        self.reader = asyncio.create_task(self._read())
        return await self.cmd("auth", token=token)

    async def _read(self):
        async for raw in self.ws:
            msg = json.loads(raw)
            if "event" in msg:
                self.events.append(msg)
                if self.event_cb: self.event_cb(msg)
                continue
            mid = msg.get("message_id")
            if msg.get("partial"):
                self.partials.setdefault(mid, []).extend(msg.get("result") or [])
                continue
            fut = self.pending.pop(mid, None)
            if fut is None:
                print("unsolicited:", raw[:300]); continue
            if "error_code" in msg:
                fut.set_exception(RuntimeError(f"{msg['error_code']}: {msg.get('details')}"))
            else:
                res = msg.get("result")
                if mid in self.partials:
                    res = self.partials.pop(mid) + (res or [])
                fut.set_result(res)

    async def cmd(self, command, **args):
        mid = str(next(self.ids))
        fut = asyncio.get_running_loop().create_future()
        self.pending[mid] = fut
        await self.ws.send(json.dumps({"message_id": mid, "command": command, "args": args}))
        return await asyncio.wait_for(fut, 30)

def token():
    """Il token di accesso a lungo termine, dalla variabile MA_TOKEN."""
    return os.environ["MA_TOKEN"]
