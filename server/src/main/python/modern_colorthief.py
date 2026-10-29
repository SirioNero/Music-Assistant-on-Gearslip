"""
modern_colorthief in Python, per il server di Music Assistant dentro Xonos.

L'originale è in Rust e per Android non c'è. Music Assistant ne usa solo get_palette, per i colori
delle copertine: qui li trova Pillow, riducendo i colori dell'immagine col taglio mediano (lo stesso
principio dell'MMCQ di colorthief), dal più frequente al meno frequente.
"""

from __future__ import annotations

import io

from PIL import Image

# I lati della copia rimpicciolita su cui si contano i colori: basta per una tavolozza.
_SAMPLE_SIDE = 96


def get_palette(image: bytes | str, color_count: int = 10, quality: int = 10) -> list[tuple[int, int, int]]:
    """
    Dà i colori principali di un'immagine, dal più frequente.

    :param image: I byte dell'immagine, o il percorso del file.
    :param color_count: Quanti colori trovare.
    :param quality: Non usato: l'immagine si guarda sempre alla stessa misura ridotta.
    """
    source = Image.open(io.BytesIO(image) if isinstance(image, (bytes, bytearray)) else image)
    with source:
        picture = source.convert("RGB")
    picture.thumbnail((_SAMPLE_SIDE, _SAMPLE_SIDE))
    reduced = picture.quantize(colors=max(2, color_count), method=Image.Quantize.MEDIANCUT)
    palette = reduced.getpalette() or []
    counts = sorted(reduced.getcolors() or [], reverse=True)
    colors = []
    for _, index in counts[:color_count]:
        red, green, blue = palette[index * 3 : index * 3 + 3]
        colors.append((red, green, blue))
    return colors


def get_color(image: bytes | str, quality: int = 10) -> tuple[int, int, int]:
    """Dà il colore più frequente di un'immagine."""
    palette = get_palette(image, 5, quality)
    return palette[0] if palette else (0, 0, 0)
