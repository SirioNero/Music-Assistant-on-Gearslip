"""Prepara da zero il Music Assistant di prova: primo utente, token, servizi. Stampa il token.

Uso: python3 setup.py [http://127.0.0.1:8095]
Servizi: cartelle /music e /music2, il podcast RSS e la ricerca podcast di iTunes (che nella prova
punta al finto servizio podcasts.py, vedi README).
"""
import asyncio, json, os, sys, urllib.request
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ma_api import MA

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8095"


def first_token():
    """Il primo utente si crea con POST /setup, che risponde con un token (solo la prima volta)."""
    body = json.dumps({"username": "prova", "password": "prova-xonos", "device_name": "prova"}).encode()
    request = urllib.request.Request(f"{BASE}/setup", body, {"Content-Type": "application/json"})
    with urllib.request.urlopen(request) as response:
        return json.load(response)["token"]


async def add(ma, domain, values):
    confs = await ma.cmd("config/providers")
    if any(c["domain"] == domain and all(c["values"].get(k) == v for k, v in values.items()) for c in confs):
        return
    step = await ma.cmd("config/providers/setup", provider_domain=domain)
    if step.get("type") == "form":
        await ma.cmd("config/flows/submit", flow_id=step["flow_id"], values=values)
    print("aggiunto", domain, values, file=sys.stderr)


async def main():
    token = os.environ.get("MA_TOKEN") or first_token()
    ma = MA(BASE.replace("http", "ws", 1) + "/ws")
    await ma.connect(token)
    if not os.environ.get("MA_TOKEN"):
        token = await ma.cmd("auth/token/create", name="Prova Xonos")
        try:
            await ma.cmd("config/onboard_complete")
        except RuntimeError:
            pass
    await add(ma, "filesystem_local", {"content_type": "music", "path": "/music"})
    await add(ma, "filesystem_local", {"content_type": "music", "path": "/music2"})
    await add(ma, "itunes_podcasts", {"locale": "it"})
    await add(ma, "podcastfeed", {"feed_url": "http://127.0.0.1:8770/feeds/elisir.xml"})
    await ma.cmd("music/sync")
    print(token)


asyncio.run(main())
