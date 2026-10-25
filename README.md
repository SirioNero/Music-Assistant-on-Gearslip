# Xonos

[English](#english) | [Italiano](#italiano)

## English

Music Assistant in your car, through [Gearslip](https://github.com/twinksoftware/Gearslip).

Xonos turns your phone into a [Music Assistant](https://music-assistant.io) player and brings your library to Gearslip’s music player, including split screen next to the map. The music plays on the phone itself, so it reaches the car’s speakers over Bluetooth (or through Gearslip’s USB audio), never your speakers at home.

It’s also a way around Spotify, which refuses to show its library to Gearslip: add Spotify to Music Assistant, and your playlists, liked songs, albums and artists are in Xonos.

The app’s interface is in Italian for now.

### How it works

- Xonos signs in to Music Assistant with a token and registers the phone as a player through Sendspin, Music Assistant’s own audio protocol. The player is private, like the local player of Music Assistant’s official app: only Xonos controls it, and Music Assistant keeps its queue between trips.
- Gearslip lists Xonos among its music apps. You browse and pick in the car; Gearslip shows what’s playing, the controls, Up next, the lyrics, and the player beside the map.
- The audio arrives as Opus, about 100 kbit/s (some 45 MB an hour of mobile data).

### Getting around

- **The menu** lists Ascoltati di recente (recently played), Per te (recommendations from your library and services), Playlist, Album, Artisti (artists), Brani (every track in the library), Brani preferiti (favorite tracks), Generi (genres), Radio, Podcast, Audiolibri (audiobooks) and Sfoglia i servizi (browse each service’s own folders). Empty ones are hidden. Lists are complete, however long: past 150 items they split by initial, A to Z.
- **Albums and playlists** open with a tap; the play button next to them plays them whole. A track inside an album or playlist plays the album or playlist from that track on, so next and previous stay inside it.
- **An artist** shows the most played tracks, every album (those in the library and the whole discography on the services where the artist is), and all the tracks.
- **Search** with Gearslip’s search key: Xonos searches the library and every service connected to Music Assistant, for every kind of item. The best matches come first, one per kind, then a folder per kind with all its results: Brani, Artisti, Album, Playlist, Podcast, Audiolibri, Radio, Generi. Starting or ending with a kind (“podcast morning”, “morning podcast”, “radio deejay”) puts that kind first.
- **The heart** marks the track as a favorite in Music Assistant: you find it in Brani preferiti. Shuffle and repeat work on the Music Assistant queue.
- **Calls and the voice assistant** pause the music, which comes back afterwards. Navigation prompts lower it. If the car’s Bluetooth disconnects, the music pauses instead of moving to the phone’s speaker.
- **No signal** (a tunnel): the phone keeps playing what it has already received, up to about half a minute, then picks up where the music would have been.

### Installation

1. From the [Releases](../../releases) page, download `Xonos.apk` to your phone and install it. Android asks you to allow installing unknown apps; Play Protect may warn that the app is unknown.
2. Open Xonos on your phone and enter:
   - your Music Assistant address at home, for example `http://homeassistant.local:8095` (the Home Assistant add-on) or `http://192.168.1.10:8095`;
   - the address to use away from home (see below);
   - a long-lived access token (in Music Assistant: Settings, Profile, Long-lived access tokens);
   - optionally, the name the phone gets among Music Assistant’s players.
   Tap “Salva e prova la connessione” (Save and test connection): it checks both the API and the audio.
3. Tap “Consenti in background” (Allow in background), so Android doesn’t stop the music with the screen off.
4. In the car, open Gearslip: Xonos appears among the music apps.

To get updates automatically, add `https://github.com/SirioNero/Music-Assistant-on-Gearslip` to [Obtainium](https://github.com/ImranR98/Obtainium). Each release is named after the app version (`v0.1.N`), so Obtainium can tell when an update is out.

### Away from home

In the car your phone is on mobile data, so the home address doesn’t respond. Xonos tries the home address first, then the remote one, which must reach Music Assistant’s web port (8095): the API and the audio both go through it. You need one of these:

- a VPN on the phone (WireGuard, Tailscale) and a Music Assistant address reachable through it;
- Music Assistant behind an HTTPS reverse proxy, for example `https://music.example.com`, with WebSockets allowed (`/ws` and `/sendspin`).

Home Assistant Cloud (Nabu Casa) only reaches Home Assistant, not Music Assistant’s port, and Music Assistant’s own Remote Access (the `MA-XXXX-XXXX` code) isn’t supported. Avoid a plain `http://` remote address without a VPN: your token would travel unencrypted.

### Requirements

- Music Assistant 2.7.5 or later (checked against 2.10.8 and 2.11.0 beta 7).
- For Spotify, Music Assistant’s Spotify provider, which needs Spotify Premium.

### Troubleshooting

- **The test says “audio: …”.** The API answers but the audio doesn’t: with a reverse proxy, allow WebSockets on `/sendspin` too; Music Assistant before 2.7.5 has no audio for phones.
- **The menu works but there’s no sound.** Check that the phone is connected to the car over Bluetooth, or that Gearslip’s USB audio is on.
- **The music stops with the screen off.** Tap “Consenti in background” on the phone, and on some brands (Xiaomi, Huawei, Samsung) also allow Xonos to run in the background in the battery settings.
- **Useful logs**: `adb logcat -s Xonos`.

### Limitations

- The phone’s player is private: Music Assistant’s other apps and Home Assistant don’t offer it as a target. You start the music from Xonos, in the car.
- The audio uses Sendspin without its own encryption, a mode Music Assistant accepts by default; the connection is signed in with your token, and encrypted when the address is `https://`.
- One phone, one queue: if you change the player name, Music Assistant keeps the same player and its queue.

### Building

Every push builds the app on GitHub Actions. On the `main` branch, if the `XONOS_KEYSTORE` secret (the signing key, base64-encoded) is set, it also publishes a release with the APK; other branches and pull requests are built only as a check. Plancia’s key works too: set the same value as `PLANCIA_KEYSTORE` and the workflow reads its alias. If the key has its own password, put it in `XONOS_KEYSTORE_PASSWORD`.

Locally: open the folder in Android Studio and press Run. Without `app/xonos.keystore`, the app is signed with your computer’s debug key.

### Contributing

Bug reports and pull requests are welcome, especially from people trying Xonos in their car with different Music Assistant setups. When you report a problem, include your phone and car, your Gearslip and Music Assistant versions, and what you see and hear.

| File | What it contains |
|---|---|
| `data/MaClient.kt` | WebSocket connection to Music Assistant’s API |
| `data/MaRepository.kt` | Which address answers (home or remote), events, reconnection |
| `data/Library.kt` | The library and queue commands the car uses |
| `sendspin/SendspinClient.kt` | The phone as a Music Assistant player: the Sendspin protocol and clock sync |
| `sendspin/AudioEngine.kt` | Plays the audio at the time the server asks for |
| `sendspin/AndroidAudio.kt` | The phone’s audio output and Opus decoder |
| `playback/PlaybackService.kt` | The music app Gearslip opens: session, menu, focus, notification |
| `playback/MaPlayer.kt` | The player Gearslip sees, mirroring the Music Assistant queue |
| `playback/Menu.kt` | The menu in the car, the search, and what each item plays |
| `playback/BrowseTree.kt` | The menu as Media3 items for Gearslip |
| `playback/Artwork.kt` | Cover art served to Gearslip |
| `phone/SettingsActivity.kt` | The settings screen on the phone |

## Italiano

Music Assistant in auto, tramite [Gearslip](https://github.com/twinksoftware/Gearslip).

Xonos trasforma il telefono in un lettore di [Music Assistant](https://music-assistant.io) e porta la tua libreria nel lettore musicale di Gearslip, anche nella vista doppia accanto alla mappa. La musica suona sul telefono stesso, quindi arriva alle casse dell’auto via Bluetooth (o con l’audio USB di Gearslip), mai alle casse di casa.

È anche il modo per aggirare Spotify, che a Gearslip non mostra la sua libreria: aggiungi Spotify a Music Assistant, e playlist, brani che ti piacciono, album e artisti sono in Xonos.

### Come funziona

- Xonos accede a Music Assistant con un token e registra il telefono come lettore con Sendspin, il protocollo audio di Music Assistant. Il lettore è privato, come quello dell’app ufficiale di Music Assistant: lo comanda solo Xonos, e Music Assistant gli ricorda la coda da un viaggio all’altro.
- Gearslip trova Xonos tra le app musicali. In auto sfogli e scegli; Gearslip mostra cosa sta suonando, i comandi, i prossimi brani, il testo e il lettore accanto alla mappa.
- L’audio arriva in Opus, circa 100 kbit/s (sui 45 MB di dati all’ora).

### Come si naviga

- **Il menu** elenca Ascoltati di recente, Per te (i consigli della libreria e dei servizi), Playlist, Album, Artisti, Brani (tutti quelli della libreria), Brani preferiti, Generi, Radio, Podcast, Audiolibri e Sfoglia i servizi (le cartelle di ciascun servizio). Le voci vuote non compaiono. Gli elenchi sono completi, anche lunghissimi: oltre i 150 elementi si dividono per iniziale, dalla A alla Z.
- **Album e playlist** si aprono con un tocco; il tasto play accanto li suona interi. Un brano dentro un album o una playlist li fa partire da quel brano, così avanti e indietro restano lì dentro.
- **Un artista** mostra i brani più ascoltati, tutti gli album (quelli in libreria e la discografia intera dei servizi dove l’artista c’è) e tutti i brani.
- **La ricerca** è il tasto cerca di Gearslip: Xonos cerca nella libreria e in tutti i servizi collegati a Music Assistant, ogni tipo di elemento. In cima ci sono i risultati migliori, uno per tipo, poi una cartella per tipo con tutti i suoi risultati: Brani, Artisti, Album, Playlist, Podcast, Audiolibri, Radio, Generi. Se cominci o finisci con un tipo (“podcast morning”, “morning podcast”, “radio deejay”) quel tipo va per primo.
- **Il cuore** segna il brano tra i preferiti di Music Assistant: lo ritrovi in Brani preferiti. Casuale e ripeti agiscono sulla coda di Music Assistant.
- **Telefonate e assistente vocale** mettono in pausa la musica, che poi riprende. Le indicazioni del navigatore la abbassano. Se il Bluetooth dell’auto si stacca, la musica si ferma invece di passare all’altoparlante del telefono.
- **Senza campo** (una galleria) il telefono continua a suonare quello che ha già ricevuto, fino a una mezzo minuto, poi riprende da dove la musica sarebbe arrivata.

### Installazione

1. Dalla pagina [Releases](../../releases) scarica `Xonos.apk` sul telefono e installalo. Android chiede di consentire l’installazione di app sconosciute; Play Protect può avvisare che l’app non è nota.
2. Apri Xonos sul telefono e inserisci:
   - l’indirizzo di casa di Music Assistant, per esempio `http://homeassistant.local:8095` (il componente aggiuntivo di Home Assistant) oppure `http://192.168.1.10:8095`;
   - l’indirizzo da fuori casa (vedi sotto);
   - un token di accesso a lungo termine (in Music Assistant: Impostazioni, Profilo, Token di accesso a lungo termine);
   - se vuoi, il nome del telefono tra i lettori di Music Assistant.
   Premi “Salva e prova la connessione”: controlla sia l’API sia l’audio.
3. Premi “Consenti in background”, così Android non ferma la musica a schermo spento.
4. In auto apri Gearslip: Xonos compare tra le app musicali.

Per ricevere gli aggiornamenti in automatico, aggiungi `https://github.com/SirioNero/Music-Assistant-on-Gearslip` a [Obtainium](https://github.com/ImranR98/Obtainium). Ogni release ha il nome della versione dell’app (`v0.1.N`), così Obtainium capisce quando c’è un aggiornamento.

### Fuori casa

In auto il telefono usa la rete mobile, quindi l’indirizzo di casa non risponde. Xonos prova prima quello di casa e poi quello remoto, che deve arrivare alla porta web di Music Assistant (8095): da lì passano sia l’API sia l’audio. Serve uno di questi:

- una VPN sul telefono (WireGuard, Tailscale) e l’indirizzo di Music Assistant raggiungibile attraverso di essa;
- Music Assistant dietro un reverse proxy HTTPS, per esempio `https://musica.example.com`, con i WebSocket consentiti (`/ws` e `/sendspin`).

Home Assistant Cloud (Nabu Casa) arriva solo a Home Assistant, non alla porta di Music Assistant, e l’accesso remoto di Music Assistant (il codice `MA-XXXX-XXXX`) non è supportato. Evita un indirizzo remoto in `http://` senza VPN: il token viaggerebbe in chiaro.

### Requisiti

- Music Assistant 2.7.5 o successivo (verificato con la 2.10.8 e la 2.11.0 beta 7).
- Per Spotify, il provider Spotify di Music Assistant, che vuole Spotify Premium.

### Se qualcosa non va

- **La prova dice “audio: …”.** L’API risponde ma l’audio no: con un reverse proxy consenti i WebSocket anche su `/sendspin`; Music Assistant prima della 2.7.5 non ha l’audio per i telefoni.
- **Il menu funziona ma non si sente niente.** Controlla che il telefono sia collegato all’auto via Bluetooth, o che l’audio USB di Gearslip sia attivo.
- **La musica si ferma a schermo spento.** Premi “Consenti in background” sul telefono e, su alcune marche (Xiaomi, Huawei, Samsung), consenti a Xonos di funzionare in background anche nelle impostazioni della batteria.
- **Log utili**: `adb logcat -s Xonos`.

### Limiti

- Il lettore del telefono è privato: le altre app di Music Assistant e Home Assistant non lo offrono come destinazione. La musica la fai partire da Xonos, in auto.
- L’audio usa Sendspin senza la sua cifratura, una modalità che Music Assistant accetta di serie; la connessione è comunque autenticata dal token, e cifrata quando l’indirizzo è `https://`.
- Un telefono, una coda: se cambi il nome del lettore, Music Assistant tiene lo stesso lettore e la sua coda.

### Compilare

Ogni push compila l’app su GitHub Actions. Sul ramo `main`, se è configurato il secret `XONOS_KEYSTORE` (la chiave di firma in base64), pubblica anche una release con l’APK; gli altri rami e le pull request vengono compilati solo per controllo. Va bene anche la chiave di Plancia: metti lo stesso valore come `PLANCIA_KEYSTORE` e il workflow ne legge l’alias. Se la chiave ha una password sua, mettila in `XONOS_KEYSTORE_PASSWORD`.

In locale: apri la cartella in Android Studio e premi Run. Senza `app/xonos.keystore` l’app viene firmata con la chiave di debug del proprio computer.

### Contribuire

Segnalazioni e pull request sono benvenute, soprattutto da chi prova Xonos in auto con impianti Music Assistant diversi. Quando segnali un problema indica telefono e auto, la versione di Gearslip e di Music Assistant, e cosa vedi e senti.

| File | Cosa contiene |
|---|---|
| `data/MaClient.kt` | Connessione WebSocket all’API di Music Assistant |
| `data/MaRepository.kt` | Quale indirizzo risponde (casa o remoto), eventi, riconnessione |
| `data/Library.kt` | I comandi di libreria e coda che usa l’auto |
| `sendspin/SendspinClient.kt` | Il telefono come lettore di Music Assistant: il protocollo Sendspin e l’orologio |
| `sendspin/AudioEngine.kt` | Suona l’audio al momento che chiede il server |
| `sendspin/AndroidAudio.kt` | L’uscita audio del telefono e il decoder Opus |
| `playback/PlaybackService.kt` | L’app musicale che apre Gearslip: sessione, menu, audio condiviso, notifica |
| `playback/MaPlayer.kt` | Il lettore che vede Gearslip, specchio della coda di Music Assistant |
| `playback/Menu.kt` | Il menu in auto, la ricerca e cosa suona ogni voce |
| `playback/BrowseTree.kt` | Il menu come voci di Media3 per Gearslip |
| `playback/Artwork.kt` | Le copertine passate a Gearslip |
| `phone/SettingsActivity.kt` | La schermata di configurazione sul telefono |
