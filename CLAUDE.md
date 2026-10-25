# Xonos: appunti per chi ci lavora

Xonos è un'app Android che fa del telefono un lettore privato di Music Assistant (MA) e porta la
libreria nel lettore di Gearslip (alternativa ad Android Auto), anche in vista doppia con le mappe.
La musica esce dal telefono, quindi dall'auto via Bluetooth: mai dalle casse di casa. Serve anche ad
aggirare Spotify, che a Gearslip non mostra la libreria: Spotify si aggiunge come servizio in MA.

L'utente (SirioNero) parla italiano e prova l'app sul telefono con Obtainium. Leggi tutto questo file
prima di cominciare: le cose scritte qui sono già state verificate, non vanno riscoperte.

## Regole

- Codice, commenti, commit e log in italiano, con frasi semplici come nel resto del codice. README in
  inglese e italiano. L'interfaccia è in 9 lingue (vedi sotto), con l'inglese come predefinita.
- Si lavora su un ramo `claude/...`. In `main` si pubblica solo quando l'utente lo chiede (anche con
  "fai un aggiornamento"): ogni push in `main` che cambia l'app crea una release.
- Niente pull request se non richiesta.
- Niente copie offline o permanenti dei brani di Spotify, Tidal, YouTube Music e degli altri servizi
  in streaming: vorrebbe dire aggirare le loro protezioni (termini d'uso e diritto d'autore). L'offline
  si fa per podcast e librerie proprie (Jellyfin, Plex, Subsonic/Navidrome, Audiobookshelf, file).
  Un buffer temporaneo per la riproduzione in corso va bene; un archivio da ascoltare dopo no.
- Prima di dire che una cosa funziona, provala (vedi "Come si verifica"). Quello che non si è potuto
  provare (sul telefono, con Gearslip, con Spotify vero) va detto all'utente.

## Compilare e pubblicare

- In questa sandbox non c'è l'SDK Android (dl.google.com, cioè il Maven di Google, è bloccato): l'app
  si compila solo su GitHub Actions, workflow "Compila Xonos" (`.github/workflows/build.yml`), a ogni push.
- Lo stato dei workflow: gli strumenti GitHub (`actions_list`, `get_job_logs` a job finito) o
  `curl https://api.github.com/repos/SirioNero/Music-Assistant-on-Gearslip/actions/runs?branch=...`
  (senza token: `gh` qui non è autenticato). Gli errori di compilazione finiscono anche in
  un'annotazione del check run. Non si può leggere l'elenco dei secret.
- Release: solo da `main`, solo se l'app è cambiata, solo con la chiave. Tag `v0.1.<run_number>`,
  file `Xonos.apk`. La chiave è nel secret `XONOS_KEYSTORE`: è quella di Plancia (alias `plancia`,
  password `plancia-android`); il workflow prova da sé le password `xonos-android` e `plancia-android`.
- Strumenti: AGP 8.11.1, Kotlin 2.2.0, Gradle 8.14.3, compileSdk 36, targetSdk 34, minSdk 26,
  Media3 1.11.1, OkHttp 4.12.0, coroutines 1.10.2, Compose 1.9.4, Material 3 1.4.0, activity-compose
  1.10.1, Coil 3.3.0. Pacchetto `it.xonos.ma`. R8 è spento (`isMinifyEnabled = false`): non si può
  provare qui un APK ridotto, e un errore di riflessione lo vedrebbe solo l'utente.
- Compose e Material 3 hanno le versioni di Compose Multiplatform 1.9.3 / material3 1.9.0 (lo dicono i
  file `.module` di CMP su Maven Central): così `tools/screens` disegna le stesse composable. Coil 3.3.0
  è l'ultimo compilato con Kotlin 2.2 (il 3.4 vuole la stdlib 2.3).

## Come è fatta

| File | Cosa fa |
|---|---|
| `data/MaClient.kt` | WebSocket `/ws` di MA: accesso con token, comandi con risultati a pezzi, eventi. Ogni errore è un `MaException` con un `Problem` |
| `data/MaRepository.kt` | Prova l'indirizzo di casa poi quello remoto, si ricollega, smista gli eventi |
| `data/Library.kt` | I comandi di libreria e coda. Elenchi interi a pagine di 500; ricerca divisa per tipo |
| `data/Model.kt` | `MaItem`, `QueueItem`, `QueueState`, `Connection`, `Problem` |
| `data/Settings.kt` | Preferenze: indirizzi, token, nome e id del lettore, volume, lingua |
| `sendspin/SendspinClient.kt` | Il telefono come lettore Sendspin (protocollo audio di MA), sincronia dell'orologio |
| `sendspin/AudioEngine.kt` | Suona i pacchetti al momento giusto; piccole correzioni un campione alla volta |
| `sendspin/AndroidAudio.kt` | AudioTrack e decoder Opus/FLAC di MediaCodec (OpusHead ricostruito, pre-skip 0) |
| `playback/PlaybackService.kt` | MediaLibraryService di Media3 che Gearslip apre: sessione, focus audio, notifica, rete |
| `playback/MaPlayer.kt` | SimpleBasePlayer che rispecchia la coda di MA |
| `playback/Menu.kt` | Il menu e la ricerca, senza Media3 (si prova sulla JVM). Gli id dicono cosa fare: vedi il commento in testa |
| `playback/BrowseTree.kt`, `ResourceLabels.kt` | Il menu come MediaItem; le sue scritte dalle risorse |
| `playback/Artwork.kt` | Copertine servite a Gearslip come `content://` firmati |
| `data/Lyrics.kt` | Il testo di un brano: lettore LRC (più tempi per riga, offset, tag, parole a tempo) e testo semplice |
| `phone/MainActivity.kt` | L'app sul telefono: Compose, lingua, MediaController verso il servizio finché è in vista |
| `phone/ControllerLink.kt` | `PlayerLink` dal MediaController: lo stesso `MaPlayer` che vede Gearslip |
| `phone/model/` | `PhoneModel` (schede con pile di pagine, caricamenti, ricerca, suona/dopo/in coda, preferiti, testi, impostazioni), `Pages.kt`, `Player.kt` (`NowPlaying`, `PlayerLink`), `ConnectionCheck.kt`. Senza Android: si prova sulla JVM |
| `phone/ui/` | Le schermate in Compose/Material 3. Tutto comune tranne `Platform.kt` (indietro, pixel della copertina) |
| `Language.kt`, `Texts.kt` | Le lingue (senza Android); `wrap()` e `describe()`, che racconta un `Problem` nella lingua scelta |

## Music Assistant: cose verificate (2.10.8 e 2.11.0b7)

- API: `/ws`, primo messaggio `server_info`, poi `auth` con il token. Codici d'errore: 12 comando
  sconosciuto (MA più vecchio della 2.7), 20/21/23 token, 22 permessi.
- `music/<tipo>/library_items`: `limit` (500 di base), `offset`, `order_by`; risultati "summary" che
  includono `provider_mappings`. `music/search`: `limit` per tipo (25 di base), cerca in libreria e in
  tutti i servizi, un servizio lento (oltre 8 s) resta fuori da quella risposta.
- Per un artista della libreria `artist_albums`/`artist_tracks` danno solo ciò che è in libreria:
  per la discografia intera si chiede a ogni `provider_mapping` (lo fa `Library.everywhere`).
  `album_tracks` dà già i brani di tutti i servizi. `top_tracks` c'è dalla 2.10.
- Generi (2.10+): `music/genres/library_items` con `hide_empty=true` e `content_type="music"`.
- Episodi dei podcast: `provider_mappings[].url` è l'indirizzo diretto del file dell'episodio.
- Immagini: `/imageproxy/<proxy_id>?size=256&fmt=jpg` (o 512), senza token.
- `order_by` di `library_items`: `sort_name`, `sort_name_desc`, `timestamp_added_desc`,
  `last_played_desc`, `play_count_desc`, `year`. `search` filtra per nome.
- Testi: `metadata/get_track_lyrics` (c'è dalla 2.7.5) vuole l'oggetto brano intero (quello di
  `music/item_by_uri`) e risponde `[testo semplice, testo LRC]`. Il filesystem legge il tag LYRICS
  (anche in formato LRC: allora finisce nel primo campo) e un file `.lrc` accanto al brano. Se il brano
  non ha testo MA lo cerca nei servizi di metadati (LRCLIB ecc.): senza rete può metterci decine di secondi.
- `play_media` con `start_item` (2.10.8): toglie dalla coda i brani prima di quello scelto (se il
  casuale è spento). `shuffle` in `play_media` c'è solo dalla 2.10, e le versioni vecchie ignorano gli
  argomenti che non conoscono: per mescolare si accende prima `player_queues/shuffle`. Dalla 2.10 un
  album suona in ordine anche con il casuale acceso, se non si passa `shuffle`.
- `music/recommendations` (2.10.8): 17 righe (cartelle) senza contenuto; il contenuto con
  `music/recommendations/items`. La riga `recently_played` ripete gli ascolti recenti.
- Gli indirizzi HTTP dei brani in coda (`/single/<session>/...`, porta 8097) sono protetti da un
  codice di sessione interno che l'API non dà; `music/tracks/preview` è solo un assaggio di 30 s.

### Sendspin (come arriva l'audio)

- Si usa la modalità non cifrata ("legacy") attraverso `/sendspin` sulla porta di MA: primo messaggio
  `{"type":"auth","token":...,"client_id":...}`, poi `client/hello`. L'API va collegata prima di
  Sendspin. `product_name` "Mobile Application" rende il lettore privato (id = client_id);
  `unpaired_access.enabled=false`, altrimenti alla riconnessione il server lo rifiuta.
- Ai client legacy l'intestazione dei pacchetti audio è di 9 byte (tipo + tempo), senza il
  `send_ahead` della specifica nuova: con 13 byte Opus diventa rumore.
- Il server tiene al massimo 30 s di audio in anticipo per lettore (`max_duration_us` in aiosendspin,
  fisso). Più di così si può avere solo cambiando Music Assistant/aiosendspin.

## Gearslip: cosa conta

- Elenca le app con `MediaBrowserService` e il descrittore `automotive_app_desc` (`<uses name="media"/>`).
- Sfoglia con il MediaBrowser del sistema, senza pagine: vuole l'elenco intero. Cerca con
  MediaBrowserCompat, i cui risultati passano in un solo messaggio Binder (circa 1 MB): tenerli corti.
- Non è un'app di sistema: Media3 gli darebbe solo la lettura, serve `onConnectAsync` con tutti i comandi.
- Le copertine `http://` non le carica (targetSdk 37): passano da `content://`.
- La vista doppia compare solo con un brano attivo. I testi li cerca Gearslip da sé.

## L'app sul telefono

- Lo stato è in `PhoneModel` (valori osservabili di Compose, runtime 1.5 sulla JVM): vive in
  `XonosApp.phone`, così sopravvive a rotazione e cambio di lingua (`recreate()`). Tre schede (Home,
  Cerca, Libreria) con la loro pila di pagine; il lettore a schermo intero sta sopra tutto, e la sua
  vista (copertina, testo, coda) è nel modello.
- Si suona con `Library.playMedia` sulla coda del telefono (id del lettore), dopo aver aspettato
  `XonosApp.playerReady`, che il servizio accende quando API e Sendspin sono pronti. Stato e comandi del
  lettore passano dal MediaController del servizio: `MaPlayer` mette negli extra dei metadati uri, tipo,
  album e artista del brano (`MaPlayer.EXTRA_*`).
- Le copertine sul telefono: indirizzi http dell'imageproxy con Coil (OkHttp dell'app, con timeout);
  quelle del lettore sono i `content://` di `Artwork`. Il colore di fondo si calcola dai pixel della
  copertina (`backdropColor`). Le icone sono disegnate in `Icons.kt` (la libreria di icone pesa megabyte).
- La Home si aggiorna trascinando in giù, e da sola riaprendo l'app se ha più di dieci minuti.
- Ciò che sta fuori dallo `Scaffold` (lettore, benvenuto) ha bisogno della `Surface` della radice
  sopra, altrimenti `LocalContentColor` è nero.

## Lingue

- `Language.kt`: en (predefinita), it, es, de, fr, pt, el, ru, zh-CN. La scelta vale per la schermata
  (attachBaseContext) e per il menu in auto (`ResourceLabels`).
- Testi in `res/values*/strings.xml`: stesse chiavi in ogni lingua, plurali con le forme giuste
  (russo one/few/many/other, cinese solo other). Dopo ogni modifica: `python3 tools/check_strings.py`.

## Come si verifica (senza telefono)

Vedi `tools/README.md`: un Music Assistant vero in Docker (album con copertine e testi, 720 brani, un
podcast RSS e un finto servizio di ricerca podcast) e un banco di prova JVM che compila le parti
dell'app senza Android (data, sendspin, Menu, phone/model) e fa le stesse cose del telefono e di
Gearslip (`PhoneCheckKt` per l'app del telefono). La parte Android si verifica solo compilando su GitHub.

L'aspetto si guarda con `tools/screens`: Compose Desktop disegna in PNG le stesse composable con dati
finti. Compose Desktop dalla 1.6 vuole librerie androidx che stanno solo sul Maven di Google: il workflow
"Schermate" le mette, con le PNG e il log, nel riferimento nascosto `refs/xonos/screens` (non è un ramo).
Copiate in `tools/screens/libs`, si disegna anche qui in una ventina di secondi. Le schermate del README
(`docs/*.jpg`) vengono da lì, in inglese. L'utente giudica comunque sul telefono (caratteri, densità,
gesti e animazioni sono diversi).

Nella sandbox: Docker Hub risponde 429, si usa `mirror.gcr.io` (vedi `tools/README.md`); se il lavoro
si riavvia, il demone Docker si ferma e i container `ma` e `pods` vanno riavviati (`docker start`).

## Richieste dell'utente

Fatte: app sul telefono con grafica moderna (riproduzione, mini-lettore, libreria, ricerca) e testi
sincronizzati sul telefono. Ancora da fare:

3. Cali di rete: almeno 3 brani coperti (per poter saltare in galleria) e 10 minuti di podcast.
   Per i podcast si può (episodi scaricati dall'indirizzo diretto). Per la musica in streaming il
   limite è il server (30 s): serve una modifica a Music Assistant/aiosendspin, da proporre a monte;
   per la musica dei server propri (Jellyfin, Plex, Subsonic) si può scaricando direttamente da lì.
4. Offline: podcast e librerie proprie; per le playlist di Spotify, scaricare le copie dei brani
   presenti nella libreria propria dell'utente. Niente copie dai servizi in streaming.
5. Casse di casa, passaggio della musica tra telefono e casa, coda e playlist. Già fatti: la coda nel
   lettore (si salta toccando), "suona dopo" e "in coda" dal menu ⋮. Mancano le casse di casa
   (`players/all`, `player_queues/transfer`) e il modificare le playlist.
