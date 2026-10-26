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
- Release: solo da `main`, solo se l'app è cambiata, solo con la chiave. File `Xonos.apk`, tag
  `v<versionName>`. La versione è scritta in `app/build.gradle.kts` e **va alzata a mano a ogni
  pubblicazione** che cambia l'app: `versionCode` +1 e `versionName` = `0.1.<versionCode>` (fino alla
  v0.1.36 il numero era quello del workflow; la prima scritta nel sorgente è la 37). Insieme si scrivono
  le note `fastlane/metadata/android/{en-US,it-IT}/changelogs/<versionCode>.txt` (massimo 500 caratteri),
  che diventano anche il testo della release. Se il tag c'è già il workflow si ferma con un errore.
- La chiave è nel secret `XONOS_KEYSTORE`: è quella di Plancia (alias `plancia`, password
  `plancia-android`); il workflow prova da sé le password `xonos-android` e `plancia-android`. Senza
  chiave la release resta non firmata (come la vuole F-Droid); con `XONOS_DEBUG_SIGNING=true` (le
  misure) si firma con la chiave di debug.
- Licenza AGPL-3.0-or-later (`LICENSE`). Tutte le librerie sono Apache 2.0, compatibili. F-Droid: testi,
  icona e schermate in `fastlane/metadata/android/` (inglese e italiano), ricetta da proporre a
  fdroiddata in `tools/fdroid/it.xonos.ma.yml` (toglie `tools/` prima di compilare: le prove usano un
  mirror di Maven Central che lo scanner di F-Droid non accetta), workflow "Come F-Droid" che compila
  senza chiave e passa ricetta e APK a `fdroid lint` e `fdroid scanner`. `dependenciesInfo` è spento
  (F-Droid rifiuta quel blocco). F-Droid firma con la sua chiave: senza build riproducibili chi passa
  da GitHub/Obtainium a F-Droid (o viceversa) deve disinstallare.
- Strumenti: AGP 8.11.1, Kotlin 2.2.0, Gradle 8.14.3, compileSdk 36, targetSdk 34, minSdk 26,
  Media3 1.11.1, OkHttp 4.12.0, coroutines 1.10.2, Compose 1.9.4, Material 3 1.4.0, activity-compose
  1.10.1, Coil 3.3.0 (con coil-svg per le icone dei servizi), profileinstaller 1.4.1. Pacchetto `it.xonos.ma`.
- R8 è acceso nella release (`isMinifyEnabled` e `isShrinkResources`, regole in `app/proguard-rules.pro`,
  `-dontobfuscate` così gli errori restano leggibili). L'APK ridotto si prova sull'emulatore con le misure
  (sotto): parte, si collega, suona Opus, nessun arresto.
- Baseline Profile: il modulo `baselineprofile/` (Macrobenchmark 1.4.1, UiAutomator) lo genera
  sull'emulatore; il profilo sta in `app/src/main/generated/baselineProfiles/baseline-prof.txt` e va
  rigenerato (commit con `[profilo]`) quando cambiano molto avvio, Home, Libreria o lettore.
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
| `playback/Artwork.kt` | Copertine servite a Gearslip, alla notifica e al lettore come `content://` firmati; i file li tiene `data/ArtCache.kt` (una copertina chiesta da più parti insieme si scarica una volta sola) |
| `data/SkipSender.kt` | Avanti, indietro e "salta a" mandati a MA uno alla volta, sempre verso l'ultimo brano chiesto; raccolti per 400 ms solo con i servizi a un flusso (`QueueItem.singleStream`) |
| `data/PlaylistRemover.kt` | Toglie brani da una playlist: i tocchi vicini in un giro, il giro dopo solo quando il primo è applicato |
| `data/Lyrics.kt` | Il testo di un brano: lettore LRC (più tempi per riga, offset, tag, parole a tempo) e testo semplice |
| `data/Cache.kt` | `ScreenCache`: le ultime schermate (Home, prime pagine della Libreria) su disco, mostrate subito all'apertura |
| `data/Account.kt` | Accesso con nome utente e password (auth/login, poi un token suo con auth/token/create), primo account (POST /setup) |
| `data/Services.kt` | I servizi (provider) di MA: elenco, tipi, icone, opzioni, pulsanti, flussi a passi; i campi come `ConfigField` |
| `data/Devices.kt` | I lettori di MA su cui suonare ("Suona su"), il passaggio della coda, i comandi a una coda |
| `phone/MainActivity.kt` | L'app sul telefono: Compose, lingua, MediaController verso il servizio finché è in vista |
| `phone/ControllerLink.kt` | `PlayerLink` dal MediaController: lo stesso `MaPlayer` che vede Gearslip |
| `phone/model/` | `PhoneModel` (schede con pile di pagine, caricamenti, ricerca, suona/dopo/in coda, preferiti, testi, impostazioni, "Suona su"), `Pages.kt`, `Player.kt` (`NowPlaying`, `PlayerLink`), `ConnectionCheck.kt`, `Discovery.kt` (server trovati con mDNS), `ServicesModel.kt` e `ServicePages.kt` (i servizi), `RemoteLink.kt` (un lettore di MA comandato dallo schermo). Senza Android: si prova sulla JVM |
| `phone/NsdFinder.kt` | Cerca Music Assistant nella rete con NsdManager (`_mass._tcp`) |
| `phone/ui/` | Le schermate in Compose/Material 3. Tutto comune tranne `Platform.kt` (indietro, pixel della copertina, "schermata piena" per le misure, id per UiAutomator). `ServicesScreens.kt` disegna i moduli di MA dai campi; `Markdown.kt` i loro testi; `DevicesSheet.kt` "Suona su"; `PlaylistSheet.kt` "Aggiungi a una playlist" |
| `baselineprofile/` | Le prove sull'emulatore: avvio, scorrimento di Home e Libreria, lettore, playlist lunga; genera il Baseline Profile |
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
- Immagini: `/imageproxy/<proxy_id>?size=256&fmt=jpg` (o 512), senza token. Le misure accettate sono
  solo 0, 80, 160, 256, 512 e 1024 (0 = originale): con le altre MA risponde 400.
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
- Accesso: `auth/login` (username, password, device_name) apre una sessione; con quella
  `auth/token/create` dà un token a lunga durata, poi `auth/logout`. Agli ospiti niente token: resta
  la sessione. Senza utenti MA risponde 200 a `GET /setup` e all'API `setup_required` (codice 503); il
  primo account si crea con `POST /setup`. Password di almeno 8 caratteri. Ruoli: admin, user, guest.
- `auth` accetta `locale` (e c'è `translations/set_locale`): MA traduce da sé etichette dei moduli,
  errori e titoli (pt_PT è molto più completa di pt_BR; l'italiano ha poche voci, il resto resta inglese).
- MA si annuncia con mDNS `_mass._tcp`; nei TXT le informazioni di `server_info` (internal_url, base_url,
  external_url, server_version...).
- Servizi (solo admin): `config/providers` (dalla 2.10 `last_error` è un oggetto con `message`),
  `providers/manifests`, `providers/icon` (data URI, di solito SVG), `config/providers/get` + `get_entries`
  (l'ordine dei campi), `save` (solo i valori cambiati; una password salvata torna come
  `this_value_is_encrypted`), `remove`, `invoke_action`. Dalla 2.10 (schema 63) si aggiunge con
  `config/providers/setup` e i flussi (`config/flows/submit|get|abort`, `config/providers/reconfigure`):
  passi form/external/progress/finish/abort, anche con l'evento `setup_flow_updated`. L'accesso nel
  browser torna a `{base_url}/setup_flow/callback/<flow>`. I flussi restano vivi 15 minuti anche se la
  connessione cade. Nei moduli dei flussi non ci sono pulsanti; i segreti non tornano mai.
- Bug di MA: dopo `save` il servizio si ricarica un secondo più tardi (`call_later(1, ...)`); un
  `remove` in quel momento lascia in settings.json una voce senza `domain` e da lì `config/providers`
  risponde `'domain'` per sempre. `Services.remove` aspetta 3 s dal salvataggio e la fine del
  caricamento. Nel Docker di prova la voce rotta si toglie a mano da `data/settings.json` (MA fermo).
- Playlist: `music/playlists/create_playlist` (`name`; senza servizio la crea "builtin", cioè di MA, che
  accetta brani di tutti i servizi; di base solo brani). `add_playlist_tracks` (`db_playlist_id`, `uris`)
  apre album e playlist nei loro brani, salta i doppioni e per la playlist di un servizio cerca lì lo
  stesso brano; `remove_playlist_tracks` (`db_playlist_id`, `positions_to_remove`) vuole le posizioni
  da 1, il campo `position` dei brani di `playlist_tracks`. Dalla 2.10 aggiunta e rimozione girano in
  disparte (MA risponde prima di farle) e lo stato dei lavori (`tasks/get`) è solo per gli admin.
  `playlist_tracks` passa da una cache che dopo una modifica può restare vecchia per più di 15 s:
  `force_refresh: true` la salta. Nelle playlist di MA i brani hanno l'uri del loro servizio, non quello
  della libreria. `is_editable` è nei risultati di `library_items`. Per cancellare una playlist:
  `music/library/remove_item` (`media_type`, `library_item_id`); `music/playlists/remove` vuole `item_id`.
- Coda: `player_queues/delete_item` (`item_id_or_index`), `move_item` (`queue_item_id`, `pos_shift`;
  0 = in testa ai prossimi), `move_item_end`. Il brano in corso e quelli fino a `index_in_buffer` (già
  mandati al lettore) non si toccano: `delete_item` lo ignora in silenzio, `move_item` dà errore.
- Lettori: `players/all` dà anche `private` (i lettori delle app, come il telefono: non si offrono) e
  `hide_in_ui`; i lettori di tipo `protocol` non compaiono. Una cassa Sendspin che non è un'app diventa
  un Universal Player (`up...`) circa 15 s dopo. `player_queues/transfer` sposta coda, punto e casuale,
  svuota e ferma la coda di prima, e riparte se quella suonava.

### Sendspin (come arriva l'audio)

- Si usa la modalità non cifrata ("legacy") attraverso `/sendspin` sulla porta di MA: primo messaggio
  `{"type":"auth","token":...,"client_id":...}`, poi `client/hello`. L'API va collegata prima di
  Sendspin. `product_name` "Mobile Application" rende il lettore privato (id = client_id);
  `unpaired_access.enabled=false`, altrimenti alla riconnessione il server lo rifiuta.
- Ai client legacy l'intestazione dei pacchetti audio è di 9 byte (tipo + tempo), senza il
  `send_ahead` della specifica nuova: con 13 byte Opus diventa rumore.
- Il server tiene al massimo 30 s di audio in anticipo per lettore (`max_duration_us` in aiosendspin,
  fisso). Più di così si può avere solo cambiando Music Assistant/aiosendspin.
- Il primo suono arriva circa `required_lead_time_ms` − 150 ms dopo l'inizio del flusso (misurato:
  800 → 0,65 s, 1500 → 1,3 s, 300 → subito). Xonos chiede 300 ms in Wi-Fi e 600 in rete mobile: il
  margine si paga a ogni play e a ogni salto, le gallerie le copre l'anticipo che il server manda dopo.
- L'uscita del telefono ci mette di più ad aprirsi (sull'emulatore l'avvio era in ritardo di 1042, 652,
  169 ms; con il Bluetooth si aggiunge il suo ritardo). Fino alla 0.1.37 l'audio in ritardo all'avvio si
  saltava (l'inizio del brano tagliato): ora si suona tutto un po' dopo e si recupera piano (0,4%), e la
  posizione mostrata è quella del server meno `AudioEngine.lateMs`. L'AudioTrack di suo parte solo con il
  buffer pieno: da Android 12 Xonos gli fa bastare 40 ms (`setStartThresholdInFrames`) e mentre aspetta
  i suoi tempi tiene in coda solo 50 ms di silenzio (`StartCheckKt` lo simula).
- Il decoder Opus di MediaCodec con gli ingressi pieni aspetta che gli si prendano le uscite: prima si
  prendono quelle, poi si riprova (fino alla 0.1.37 il pacchetto si buttava: un buco di 20 ms).

### Avvio e limiti dei servizi (misurati con `LatencyCheckKt`)

- Prima di far partire un brano MA aspetta audio nel suo buffer: 2 s di solito, 5 con la
  normalizzazione dinamica, 8 con la dissolvenza incrociata; per le sorgenti "realtime" (Spotify
  Soloist) 1 s, 2 con la normalizzazione dinamica, 5 all'inizio di un brano sotto il minuto. Con una
  sorgente che arriva alla velocità della musica (`/slow/<n>.flac` del finto server) il play aspetta
  6–7 s e ogni avanti 3–4 s; con un file locale il play 0,1 s, ma ogni avanti costa sempre ~1 s.
- `max_concurrent_streams` per servizio: Spotify con librespot 3, con Soloist 1, Tidal 1, YouTube Music
  3, gli altri servizi in streaming 5, file e server propri senza limite. Con 1 non c'è il precarico del
  brano dopo, e una raffica di salti finiva in "has reached its limit of 1 simultaneous streams":
  con quei servizi Xonos raccoglie avanti/indietro premuti in fretta in un salto solo (`SkipSender`).
  Due comandi insieme MA li esegue in parallelo e vince l'ultimo a finire: i salti vanno uno alla volta.
- Spotify in MA ha due motori: librespot (parte prima, 3 flussi, ma va solo con gli account creati
  prima di dicembre 2024) e Soloist (quello ufficiale: tutti gli account Premium, un flusso, audio alla
  velocità della musica). Si sceglie riconfigurando il servizio.

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
  quelle del lettore sono i `content://` di `Artwork`. Al cambio di brano la stessa copertina la chiedono
  insieme notifica, mini-lettore (copertina e colore) e auto: fino alla 0.1.37 i download si pestavano
  i piedi e una richiesta su tre restava senza (`ArtCheckKt`); ora c'è `ArtCache`. Il colore di fondo si calcola dai pixel della
  copertina (`backdropColor`). Le icone sono disegnate in `Icons.kt` (la libreria di icone pesa megabyte).
- La Home si aggiorna trascinando in giù, e da sola riaprendo l'app se ha più di dieci minuti. Home e
  prime pagine della Libreria si mostrano subito da `ScreenCache`, poi si aggiornano.
- Gli elenchi lunghi si convertono a pezzi mentre arrivano, fuori dal thread principale
  (`MaClient.items`): la "Playlist grande" di prova (2880 brani) è ~14 MB di JSON, come MaItem ~1 MB.
  I pezzi (500) si mostrano subito (`onPart`): playlist e podcast con la rotella in fondo, l'artista prima
  con la libreria e poi con ogni servizio. La ricerca mostra subito i risultati della sola libreria
  (`library_only`), poi quelli di tutti i servizi. I testi: dopo 8 s "nessun testo", poi se arriva si mostra.
- Playlist: "Aggiungi a una playlist" (brani, album, playlist; anche una nuova), "Togli" nelle playlist
  modificabili della libreria, "Elimina" per quelle di MA. Dopo una modifica i brani si chiedono senza
  cache per 10 minuti. Coda: "Suona dopo" e "Togli dalla coda" dai tre puntini dei brani in arrivo;
  `MaPlayer` le riceve come modifiche di Media3 (`COMMAND_CHANGE_MEDIA_ITEMS`) e mette negli extra del
  brano in corso da dove la coda si può cambiare (`EXTRA_EDITABLE_FROM`).
- Impostazioni: i server trovati in rete, accesso con nome utente e password (o token), primo account.
  I servizi di MA solo per gli admin (Impostazioni, e la Home vuota).
- "Suona su": `PhoneModel.remote` è il lettore di MA comandato dallo schermo (null: il telefono); "suona"
  va lì. In auto non cambia niente: il menu suona sempre sulla coda del telefono. Se il telefono comincia
  a suonare da sé, lo schermo torna al telefono (`phonePlaying`). La scelta non si salva.
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
Altre prove del banco: `ServicesCheckKt` (con il servizio finto `tools/ma-test/xonos_test`),
`PlayersCheckKt` (una cassa finta, anche la sua coda), `AccountCheckKt`, `PerfCheckKt`, `PlaylistCheckKt`,
`SearchCheckKt`, `ArtCheckKt`, `StartCheckKt` (l'avvio dell'audio senza server).

Le misure sull'emulatore: il workflow "Misure" parte con `[misure]` nel messaggio del commit (con
`[profilo]` genera anche il Baseline Profile) o a mano; costruisce MA in Docker sul runner, l'app
release (R8) e le prove di `baselineprofile/`, e mette tabella, log e tracce in
`refs/xonos/measures/<ramo con - al posto di />`. Sull'emulatore (senza GPU) contano i confronti, non
i valori assoluti; le prove usano gli id dei testTag (`Modifier.automationIds()`).
- Si confrontano solo numeri della stessa esecuzione: tra esecuzioni diverse cambiano la macchina di
  GitHub (l'avvio è variato del 30% con lo stesso codice) e, se si toccano le prove, la lunghezza dei
  gesti. Per un prima/dopo vero servirebbe misurare le due versioni nello stesso job (non c'è ancora).
- Risultati che reggono (ottobre 2026, playlist di 2880 brani): conversione sul thread principale da
  130 ms a 0, heap massimo da 163 a 83 MB, memoria anonima da 256 a 174 MB, lettura del JSON da 2,1 a
  1,5 s. L'APK con R8 parte, si collega e suona Opus, nessun arresto. Avvio, scorrimento e lettore non
  hanno un miglioramento dimostrato sull'emulatore; con e senza Baseline Profile la differenza è nel rumore.
- Mentre Xonos suona, il servizio in primo piano tiene vivo il processo: la prova d'avvio a freddo a
  volte si ferma con "must not be running prior to cold start" (andrebbe fermata la musica prima).

## Richieste dell'utente

Fatte: app sul telefono con grafica moderna (riproduzione, mini-lettore, libreria, ricerca), testi
sincronizzati sul telefono, fluidità (R8, Baseline Profile, elenchi fuori dal thread principale, meno
ricomposizioni, schermate salvate), server trovati in rete e accesso con nome utente e password,
gestione dei servizi di MA per gli admin, "Suona su" con passaggio della coda, playlist modificabili
(aggiungi, togli, nuova, elimina) e coda modificabile (suona dopo, togli). Studiato, in attesa
della scelta dell'utente: un server MA sul telefono stesso (Termux, proot dentro l'app, Chaquopy).
Proposto, non fatto: cercare dal telefono con mDNS/SSDP le casse che MA non vede e suggerire il
servizio da aggiungere. Ancora da fare:

3. Cali di rete: almeno 3 brani coperti (per poter saltare in galleria) e 10 minuti di podcast.
   Per i podcast si può (episodi scaricati dall'indirizzo diretto). Per la musica in streaming il
   limite è il server (30 s): serve una modifica a Music Assistant/aiosendspin, da proporre a monte;
   per la musica dei server propri (Jellyfin, Plex, Subsonic) si può scaricando direttamente da lì.
4. Offline: podcast e librerie proprie; per le playlist di Spotify, scaricare le copie dei brani
   presenti nella libreria propria dell'utente. Niente copie dai servizi in streaming.
5. Coda e playlist: fatto (vedi sopra). Si potrebbe aggiungere il riordino trascinando.
