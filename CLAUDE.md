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

- In questa sandbox non c'è l'SDK Android (dl.google.com è bloccato): l'app si compila solo su GitHub
  Actions, workflow "Compila Xonos" (`.github/workflows/build.yml`), a ogni push.
- Gli errori di compilazione finiscono in un'annotazione del check run:
  `gh api repos/SirioNero/Music-Assistant-on-Gearslip/commits/<sha>/check-runs` e poi
  `.../check-runs/<id>/annotations`. Non si può leggere l'elenco dei secret né i log interi.
- Release: solo da `main`, solo se l'app è cambiata, solo con la chiave. Tag `v0.1.<run_number>`,
  file `Xonos.apk`. La chiave è nel secret `XONOS_KEYSTORE`: è quella di Plancia (alias `plancia`,
  password `plancia-android`); il workflow prova da sé le password `xonos-android` e `plancia-android`.
- Strumenti: AGP 8.11.1, Kotlin 2.2.0, Gradle 8.14.3, compileSdk 36, targetSdk 34, minSdk 26,
  Media3 1.11.1, OkHttp 4.12.0, coroutines 1.10.2. Pacchetto `it.xonos.ma`.

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
| `phone/SettingsActivity.kt` | La schermata del telefono: lingua, indirizzi, token, prova della connessione |
| `Language.kt` | Le lingue e `describe()`, che racconta un `Problem` nella lingua scelta |

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

## Lingue

- `Language.kt`: en (predefinita), it, es, de, fr, pt, el, ru, zh-CN. La scelta vale per la schermata
  (attachBaseContext) e per il menu in auto (`ResourceLabels`).
- Testi in `res/values*/strings.xml`: stesse chiavi in ogni lingua, plurali con le forme giuste
  (russo one/few/many/other, cinese solo other). Dopo ogni modifica: `python3 tools/check_strings.py`.

## Come si verifica (senza telefono)

Vedi `tools/README.md`: un Music Assistant vero in Docker (con due cartelle di musica, un podcast RSS e
un finto servizio di ricerca podcast) e un banco di prova JVM che compila le parti dell'app senza
Android (data, sendspin, Menu) e fa le stesse cose del telefono e di Gearslip. La parte Android si
verifica solo compilando su GitHub; l'aspetto lo giudica l'utente sul telefono.

## Richieste dell'utente ancora da fare

1. App sul telefono con grafica moderna: riproduzione, mini-lettore, libreria, ricerca.
2. Testi sincronizzati sul telefono.
3. Cali di rete: almeno 3 brani coperti (per poter saltare in galleria) e 10 minuti di podcast.
   Per i podcast si può (episodi scaricati dall'indirizzo diretto). Per la musica in streaming il
   limite è il server (30 s): serve una modifica a Music Assistant/aiosendspin, da proporre a monte;
   per la musica dei server propri (Jellyfin, Plex, Subsonic) si può scaricando direttamente da lì.
4. Offline: podcast e librerie proprie; per le playlist di Spotify, scaricare le copie dei brani
   presenti nella libreria propria dell'utente. Niente copie dai servizi in streaming.
5. Casse di casa, passaggio della musica tra telefono e casa, coda e playlist.
