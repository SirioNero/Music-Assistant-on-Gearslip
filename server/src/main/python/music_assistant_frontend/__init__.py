"""
Al posto dell'interfaccia web di Music Assistant (decine di megabyte), che dentro Xonos non serve:
le schermate sono quelle di Xonos. Il server mostra solo una pagina che lo dice.
"""

import os


def where() -> str:
    """Return the folder with the web pages the server shows."""
    return os.path.dirname(__file__)
