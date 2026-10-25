"""Finto iTunes Search + feed RSS per provare Music Assistant con un provider di podcast cercabile."""
import glob, json, os, sys
from email.utils import formatdate
from aiohttp import web

MUSIC = sys.argv[1] if len(sys.argv) > 1 else "music"
ART = sys.argv[2] if len(sys.argv) > 2 else "art.jpg"
BASE = "http://127.0.0.1:8770"
# Gli episodi suonano i primi file FLAC della cartella di musica.
AUDIO = sorted(glob.glob(f"{MUSIC}/**/*.flac", recursive=True))[:2]
assert AUDIO, f"nessun file FLAC in {MUSIC}"
CATALOG = [
    ("muschio-selvaggio", "Muschio Selvaggio", "Fedez e Luis Sal", 40),
    ("tienimi-bordone", "Tienimi Bordone", "Matteo Bordone", 120),
    ("morning", "Morning", "Il Post", 300),
    ("il-mondo", "Il Mondo", "Internazionale", 60),
    ("daily-cogito", "Daily Cogito", "Rick DuFer", 25),
    ("canzone-del-giorno", "La canzone del giorno", "Radio Prova", 15),
    ("storie-artista", "Storie di un Artista", "Podcast Prova", 10),
    ("elisir", "Elisir", "Rai", 8),
]

def result(slug, name, author, count):
    return {
        "wrapperType": "track", "kind": "podcast", "collectionId": abs(hash(slug)) % 10**9, "trackId": abs(hash(slug)) % 10**9,
        "artistName": author, "collectionName": name, "trackName": name, "feedUrl": f"{BASE}/feeds/{slug}.xml",
        "artworkUrl600": f"{BASE}/art/{slug}.jpg", "artworkUrl100": f"{BASE}/art/{slug}.jpg",
        "trackCount": count, "primaryGenreName": "Society & Culture", "genres": ["Podcasts"],
    }

async def search(request):
    term = request.query.get("term", "").lower()
    limit = int(request.query.get("limit", "50"))
    hits = [result(*p) for p in CATALOG if term in p[1].lower()][:limit]
    print("search", term, "->", len(hits), flush=True)
    return web.json_response({"resultCount": len(hits), "results": hits})

async def lookup(request):
    hits = [result(*p) for p in CATALOG if str(abs(hash(p[0])) % 10**9) == request.query.get("id")]
    return web.json_response({"resultCount": len(hits), "results": hits})

async def top(request):
    return web.json_response({"feed": {"results": [
        {"artistName": p[2], "id": str(abs(hash(p[0])) % 10**9), "name": p[1], "genres": []} for p in CATALOG[:5]]}})

async def feed(request):
    slug = request.match_info["slug"]
    match = [p for p in CATALOG if p[0] == slug]
    if not match:
        raise web.HTTPNotFound()
    _, name, author, count = match[0]
    items = []
    for n in range(count, 0, -1):
        items.append(f"""<item><title>{name} – puntata {n}</title><guid>{slug}-{n}</guid>
<pubDate>{formatdate(1_700_000_000 + n * 86400)}</pubDate><itunes:duration>40</itunes:duration>
<enclosure url="{BASE}/audio/{n % len(AUDIO)}.flac" type="audio/flac" length="1000000"/></item>""")
    xml = f"""<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel>
<title>{name}</title><link>{BASE}</link><description>Podcast di prova</description><itunes:author>{author}</itunes:author>
<itunes:image href="{BASE}/art/{slug}.jpg"/>{''.join(items)}</channel></rss>"""
    return web.Response(text=xml, content_type="application/rss+xml")

async def audio(request):
    return web.FileResponse(AUDIO[int(request.match_info["n"]) % len(AUDIO)])

async def art(request):
    return web.FileResponse(ART)

app = web.Application()
app.router.add_get("/search", search)
app.router.add_get("/lookup", lookup)
app.router.add_get("/api/v2/{country}/podcasts/top/15/podcasts.json", top)
app.router.add_get("/feeds/{slug}.xml", feed)
app.router.add_get("/audio/{n}.flac", audio)
app.router.add_get("/art/{slug}.jpg", art)
web.run_app(app, host="127.0.0.1", port=8770, print=None)
