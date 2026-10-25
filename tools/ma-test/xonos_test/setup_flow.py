"""Il flusso del servizio di prova di Xonos: un modulo, poi un accesso "nel browser", poi la fine.

L'accesso è finto: l'indirizzo da aprire è quello del server finto di podcasts.py (/oauth), che
rimanda subito al callback del flusso con un codice, come farebbe un vero accesso OAuth.
"""

from __future__ import annotations

from typing import TYPE_CHECKING
from urllib.parse import quote

from music_assistant_models.config_entries import ConfigEntry, ConfigValueOption
from music_assistant_models.enums import ConfigEntryType

from music_assistant.models.setup_flow import AbortFlow

if TYPE_CHECKING:
    from music_assistant.models.setup_flow import SetupSession

FAKE_OAUTH = "http://127.0.0.1:8770/oauth"


async def run_setup(session: SetupSession) -> None:
    """Modulo con campi di ogni tipo, errore se il nome è "errore", poi l'accesso esterno."""
    entries = [
        ConfigEntry(key="intro", type=ConfigEntryType.LABEL, label="Fill in the **test** form. See [the docs](https://example.org)."),
        ConfigEntry(key="account", type=ConfigEntryType.STRING, label="Account name", required=True),
        ConfigEntry(key="secret", type=ConfigEntryType.SECURE_STRING, label="Secret", required=True),
        ConfigEntry(key="quality", type=ConfigEntryType.STRING, label="Quality", default_value="high",
                    options=[ConfigValueOption(title="Low", value="low"), ConfigValueOption(title="High", value="high"), ConfigValueOption(title="Lossless", value="lossless")]),
        ConfigEntry(key="volume", type=ConfigEntryType.INTEGER, label="Volume", default_value=50, range=(0, 100)),
        ConfigEntry(key="explicit", type=ConfigEntryType.BOOLEAN, label="Explicit content", default_value=True),
        ConfigEntry(key="region", type=ConfigEntryType.STRING, label="Region", required=False, depends_on="explicit"),
        ConfigEntry(key="advanced_thing", type=ConfigEntryType.STRING, label="Advanced thing", required=False, advanced=True),
    ]
    errors: dict[str, str] = {}
    while True:
        values = await session.form(entries, step_id="user", errors=errors)
        if values.get("account") == "abort":
            raise AbortFlow("not_supported")
        if values.get("account") != "errore":
            break
        errors = {"account": "That account does not exist"}
    params = await session.external(f"{FAKE_OAUTH}?state={quote(session.callback_url, safe='')}", step_id="auth", expires_in=600)
    if params.get("code") != "xonos":
        raise AbortFlow("auth_failed")
    await session.finish({**values, "code": params["code"]})
