# Strumenti per provare Xonos senza telefono

Servono a Claude (e a chiunque) per verificare le parti dell'app contro un Music Assistant vero.

## Il banco di prova JVM (`harness/`)

Compila sulla JVM le parti dell'app che non usano Android (`data/`, `sendspin/`, `playback/Menu.kt`,
`phone/model/`), con piccoli sostituti di `android.util.Log`, `LruCache` e `Settings`, e fa ciò che fanno
telefono e Gearslip: collegarsi, registrarsi come lettore Sendspin, sfogliare, cercare, suonare. Per gli
stati osservabili del modello del telefono usa il runtime di Compose 1.5.12, l'ultimo tutto su Maven Central.

```sh
cd tools/harness
sh sync.sh                                   # copia i sorgenti dell'app (da rifare dopo ogni modifica)
gradle -q installDist -Pmain=harness.MenuCheckKt
MA_TOKEN=... LANG_XONOS=it QUERIES="vasco,podcast morning" build/install/harness/bin/harness
```

| Prova | Cosa fa |
|---|---|
| `MainKt` | Lettore Sendspin con un'uscita finta: play, seek, next, volume, pausa; sincronia (`PLAY_MS`) |
| `MenuCheckKt` | Percorre il menu come Gearslip e fa le ricerche di `QUERIES`, nella lingua `LANG_XONOS` |
| `PlayCheckKt` | Suona dal menu (episodi, cartelle, "da qui in poi", ricerca a voce) e controlla coda e audio |
| `PhoneCheckKt` | L'app del telefono senza schermo: il `PhoneModel` contro MA (Home, libreria a pagine, album, artista, podcast, ricerca, suona/dopo/in coda, casuale, preferiti, testi, indietro, impostazioni). Finisce con "Tutto bene" |
| `ProblemCheckKt` | Token sbagliato, indirizzo sbagliato, porta chiusa...: il `Problem` di ciascuno |
| `CaptureCheckKt` | Salva i pacchetti audio (`CODEC=opus|pcm`, `OUT=file`) per `ma-test/analyze_audio.py` |
| `StartCheckKt` | L'inizio di un brano senza server: un'uscita come un AudioTrack (parte con il buffer pieno o, da Android 12, con 40 ms; dice i suoi tempi dopo un po'; ritardo di altoparlante o Bluetooth). Controlla che l'inizio non si salti mai e che la posizione mostrata sia quella che si sente |
| `AdjustCheckKt` | Controlla le correzioni di sincronia un campione alla volta |
| `PerfCheckKt` | Tempi e blocchi del thread "principale" del `PhoneModel`: collegamento, Home, libreria, una playlist lunga (`PLAYLIST`) |
| `AccountCheckKt` | Accesso con nome utente e password (`MA_USER`, `MA_PASSWORD`), il token che Xonos si fa dare, un ospite, il primo account di un MA vuoto (`NEW_URL`) |
| `PlayersCheckKt` | "Suona su": il telefono e una cassa finta (Sendspin con un altro `product_name`, che MA mostra come Universal Player dopo ~15 s). La coda passa alla cassa e torna dallo stesso punto, comandi e volume della cassa, "suona" va al lettore scelto, lo schermo torna al telefono se questo suona da sé |
| `LatencyCheckKt` | Quanto passa da play e da avanti al primo suono, e quanto ne pesa Music Assistant e quanto il telefono (`LEAD_MS`, `BUFFER_MS`; `SOURCE=slow` per una sorgente alla velocità della musica, come Spotify Soloist) |
| `ArtCheckKt` | Le copertine per auto, notifica e lettore (`ArtCache`): la stessa copertina chiesta da 4 parti insieme, come al cambio di brano, arriva a tutte; per confronto il modo di prima, dove una richiesta su tre restava senza |
| `PlaylistCheckKt` | Le playlist modificabili: crea, aggiunge (un album diventa i suoi brani, i doppioni si saltano), toglie per posizione, `PlaylistRemover` con tocchi veloci, poi lo stesso dal `PhoneModel` ("Aggiungi a una playlist", nuova playlist, "Togli", "Elimina"). Cancella le playlist di prova |
| `SearchCheckKt` | La ricerca del telefono: dopo quanto compaiono i risultati della libreria e dopo quanto quelli dei servizi (`QUERY`, separate da virgole). MA ricorda le ricerche: serve una parola nuova, e un servizio lento (`SEARCH_DELAY` del finto iTunes) |
| `ServicesCheckKt` | I servizi di MA con il servizio finto `xonos_test`: elenco, icone, flusso (modulo con errore, accesso "nel browser", fine), opzioni, pulsante, riconfigura, rimozione; poi lo stesso dal `PhoneModel` (anche la fine ricavata al ritorno nell'app) e la lingua dei moduli |
| `PhoneServerCheckKt` | "Fai diventare Xonos il tuo server Music Assistant" dal `PhoneModel`, contro un Music Assistant vuoto (di solito il server di Xonos avviato sul computer con `python -m xonos_server`): la domanda con quanto pesa il pacchetto, il download finto con l'avanzamento, poi primo account creato da Xonos, token e connessione; con il token perso si rientra con la password tenuta; "Usa un altro Music Assistant"; la rimozione; un download che non riesce e un server che non parte |

`MA_URL` (predefinito `http://127.0.0.1:8095`) e `MA_TOKEN` indicano il server.

## Le schermate del telefono (`screens/`)

Un progetto Compose Desktop 1.9.3 (Compose 1.9.4 e Material 3 1.4.0, come l'app) che compila
`phone/ui/` così com'è, con sostituti di `stringResource`/`pluralStringResource` (leggono i veri
strings.xml), una classe `R` generata da `gen_r.py` e il suo `Platform.kt` (indietro, pixel della
copertina). Disegna le scene in PNG con dati finti (`Catalog.kt`) e un Music Assistant finto
(`FakeServer.kt`: accesso, testo del brano, copertine come sfumature).

Compose Desktop dalla 1.6 usa librerie androidx che stanno solo sul Maven di Google, bloccato nella
sandbox. Per questo il workflow **Schermate** (`.github/workflows/screens.yml`, a ogni push su un ramo
`claude/**` che tocca l'interfaccia) disegna le scene su GitHub e mette in `refs/xonos/screens` (un
riferimento nascosto, non un ramo) le PNG e le librerie androidx in forma di repository Maven. Così:

```sh
git fetch origin refs/xonos/screens && mkdir -p /tmp/shots && git archive FETCH_HEAD | tar -x -C /tmp/shots
cp -r /tmp/shots/libs tools/screens/libs        # una volta: poi si disegna anche nella sandbox
cd tools/screens && sh sync.sh && gradle -q installDist
JAVA_OPTS="-Dxonos.out=/tmp/shots -Dxonos.lang=it -Dxonos.only=home,player" build/install/screens/bin/screens
```

Le scene: `home`, `library`, `album`, `artist`, `artist-more`, `search`, `player`, `lyrics`, `queue`,
`podcast`, `settings`, `welcome`, `services`, `add-service`, `service-flow`, `service-login`, `service`,
`play-on`, `remote`, `remote-mini`, `add-to-playlist`
(1080×2340, densità 2,625). Le scritte sono nella lingua di `-Dxonos.lang`.

## Il Music Assistant di prova (`ma-test/`)

L'immagine ufficiale (ghcr.io) dalla sandbox non si scarica: la si costruisce dai sorgenti.

```sh
git clone --depth 1 -b 2.10.8 https://github.com/music-assistant/server ma-test/server
cp /root/.ccr/ca-bundle.crt ma-test/ca.crt     # solo nella sandbox: il certificato del proxy
docker build --network host -t ma:2.10.8 ma-test
docker run --rm -v $PWD/music2:/music2 -v $PWD/ma-test/gen_music.sh:/gen.sh:ro --entrypoint sh ma:2.10.8 /gen.sh
docker run --rm -v $PWD/music:/music -v $PWD/ma-test/gen_albums.sh:/gen.sh:ro --entrypoint sh ma:2.10.8 /gen.sh
# music/: 4 album da un minuto a brano, con copertine e testi (.lrc accanto, tag LYRICS in LRC, testo
# semplice, niente); music2/: i 720 brani da 2 secondi
docker run -d --name ma --network host -v $PWD/data:/data -v $PWD/music:/music:ro \
  -v $PWD/music2:/music2:ro ma:2.10.8
```

Nella sandbox il demone Docker va avviato a mano (`dockerd &`). Docker Hub risponde spesso 429: con
`{"registry-mirrors":["https://mirror.gcr.io"]}` in `/etc/docker/daemon.json` le immagini arrivano da lì.
Poi:

- `podcasts.py <cartella musica> <copertina.jpg>` avvia un finto iTunes con feed RSS ed episodi su
  `127.0.0.1:8770` (i servizi veri sono bloccati). Perché MA lo usi, nel container:
  `sed -i 's#https://itunes.apple.com#http://127.0.0.1:8770#g; s#https://rss.marketingtools.apple.com#http://127.0.0.1:8770#g' .../providers/itunes_podcasts/__init__.py`
  e riavvio del container.
- `setup.py` crea il primo utente, un token a lungo termine (lo stampa) e aggiunge i servizi.
- `xonos_test/` è un servizio finto che il Dockerfile copia tra quelli di MA: un flusso con un modulo
  (tutti i tipi di campo, un errore se l'account è "errore", la fine anticipata se è "abort"), un
  accesso "nel browser" che passa da `podcasts.py` (`/oauth` rimanda al callback del flusso), e
  opzioni con un pulsante. Serve a `ServicesCheckKt`. `podcasts.py` ha anche `/slow/<n>.flac`, un
  file mandato alla velocità della musica (per `LatencyCheckKt`); con `SEARCH_DELAY=3` nell'ambiente
  la ricerca risponde dopo 3 secondi, come un servizio lento (per `SearchCheckKt`).
- `analyze_audio.py opus pcm` (dentro il container, che ha PyAV) decodifica i pacchetti salvati da
  `CaptureCheckKt` e misura quanto è pulito il suono: le tracce di prova sono sinusoidi.

## Le traduzioni

`python3 tools/check_strings.py` controlla che ogni lingua abbia le stesse chiavi dell'inglese, gli
stessi segnaposto e le forme plurali giuste.
