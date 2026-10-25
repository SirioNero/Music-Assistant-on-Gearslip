"""Il servizio di prova di Xonos: non fa niente, ma ha un flusso di configurazione e delle opzioni."""

from __future__ import annotations

from typing import TYPE_CHECKING

from music_assistant_models.config_entries import ConfigActionResult, ConfigEntry, ConfigValueOption
from music_assistant_models.enums import ConfigEntryType

from music_assistant.models.plugin import PluginProvider

if TYPE_CHECKING:
    from music_assistant_models.config_entries import ProviderConfig
    from music_assistant_models.provider import ProviderManifest

    from music_assistant.mass import MusicAssistant
    from music_assistant.models import ProviderInstanceType


async def setup(mass: MusicAssistant, manifest: ProviderManifest, config: ProviderConfig) -> ProviderInstanceType:
    """Crea l'istanza."""
    return XonosTestProvider(mass, manifest, config, set())


class XonosTestProvider(PluginProvider):
    """Un servizio finto, con opzioni di ogni tipo e un'azione."""

    async def get_config_entries(self) -> tuple[ConfigEntry, ...]:
        """Le opzioni modificabili dopo la configurazione."""
        return (
            ConfigEntry(key="divider", type=ConfigEntryType.DIVIDER),
            ConfigEntry(key="warning", type=ConfigEntryType.ALERT, label="This is only a test service."),
            ConfigEntry(key="nickname", type=ConfigEntryType.STRING, label="Nickname", default_value="Xonos", required=False),
            ConfigEntry(key="shuffle_default", type=ConfigEntryType.BOOLEAN, label="Shuffle by default", default_value=False),
            ConfigEntry(key="bitrate", type=ConfigEntryType.INTEGER, label="Bitrate", default_value=320, range=(96, 320)),
            ConfigEntry(key="genres", type=ConfigEntryType.STRING, label="Genres", multi_value=True, default_value=["pop"], required=False,
                        options=[ConfigValueOption(title="Pop", value="pop"), ConfigValueOption(title="Rock", value="rock"), ConfigValueOption(title="Jazz", value="jazz")]),
            ConfigEntry(key="test_action", type=ConfigEntryType.ACTION, label="Test the connection", action="test", action_label="Test"),
        )

    async def handle_config_action(self, action: str) -> ConfigActionResult | None:
        """L'azione di prova risponde con un messaggio."""
        if action == "test":
            return ConfigActionResult(message=f"Connection OK for {self.get_setup_value('account')}")
        return await super().handle_config_action(action)
