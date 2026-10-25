"""Aspetta che il Music Assistant di prova abbia letto tutta la musica e la playlist lunga.

La prima lettura della playlist da 2880 brani è lenta (MA risolve ogni riga): la si fa qui, così le
misure trovano la playlist già pronta come su un server vero.
"""
import asyncio, os, sys, time
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ma-test"))
from ma_api import MA

os.environ.setdefault("MA_TIMEOUT", "300")


async def main():
    ma = MA()
    await ma.connect(os.environ["MA_TOKEN"])
    start = time.time()
    while time.time() - start < 900:
        tracks = await ma.cmd("music/tracks/count")
        playlists = await ma.cmd("music/playlists/library_items", limit=50, search="grande")
        if tracks >= 724 and playlists:
            p = playlists[0]
            items = await ma.cmd("music/playlists/playlist_tracks", item_id=p["item_id"], provider_instance_id_or_domain=p["provider"])
            print(f"libreria pronta: {tracks} brani, {p['name']} con {len(items)} brani ({time.time() - start:.0f} s)")
            return
        await asyncio.sleep(5)
    raise SystemExit("la libreria non è pronta dopo 15 minuti")


asyncio.run(main())
