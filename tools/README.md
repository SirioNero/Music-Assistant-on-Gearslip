# Strumenti per provare Xonos senza telefono

Servono a Claude (e a chiunque) per verificare le parti dell'app contro un Music Assistant vero.

## Il banco di prova JVM (`harness/`)

Compila sulla JVM le parti dell'app che non usano Android (`data/`, `sendspin/`, `playback/Menu.kt`),
con piccoli sostituti di `android.util.Log`, `LruCache` e `Settings`, e fa ciò che fanno telefono e
Gearslip: collegarsi, registrarsi come lettore Sendspin, sfogliare, cercare, suonare.

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
| `ProblemCheckKt` | Token sbagliato, indirizzo sbagliato, porta chiusa...: il `Problem` di ciascuno |
| `CaptureCheckKt` | Salva i pacchetti audio (`CODEC=opus|pcm`, `OUT=file`) per `ma-test/analyze_audio.py` |
| `AdjustCheckKt` | Controlla le correzioni di sincronia un campione alla volta |

`MA_URL` (predefinito `http://127.0.0.1:8095`) e `MA_TOKEN` indicano il server.

## Il Music Assistant di prova (`ma-test/`)

L'immagine ufficiale (ghcr.io) dalla sandbox non si scarica: la si costruisce dai sorgenti.

```sh
git clone --depth 1 -b 2.10.8 https://github.com/music-assistant/server ma-test/server
cp /root/.ccr/ca-bundle.crt ma-test/ca.crt     # solo nella sandbox: il certificato del proxy
docker build --network host -t ma:2.10.8 ma-test
docker run --rm -v $PWD/music2:/music2 -v $PWD/ma-test/gen_music.sh:/gen.sh:ro --entrypoint sh ma:2.10.8 /gen.sh
# music/: una cartella con qualche album (file FLAC con i tag); music2/: i 720 brani generati qui sopra
docker run -d --name ma --network host -v $PWD/data:/data -v $PWD/music:/music:ro \
  -v $PWD/music2:/music2:ro ma:2.10.8
```

Nella sandbox il demone Docker va avviato a mano (`dockerd &`). Poi:

- `podcasts.py <cartella musica> <copertina.jpg>` avvia un finto iTunes con feed RSS ed episodi su
  `127.0.0.1:8770` (i servizi veri sono bloccati). Perché MA lo usi, nel container:
  `sed -i 's#https://itunes.apple.com#http://127.0.0.1:8770#g; s#https://rss.marketingtools.apple.com#http://127.0.0.1:8770#g' .../providers/itunes_podcasts/__init__.py`
  e riavvio del container.
- `setup.py` crea il primo utente, un token a lungo termine (lo stampa) e aggiunge i servizi.
- `analyze_audio.py opus pcm` (dentro il container, che ha PyAV) decodifica i pacchetti salvati da
  `CaptureCheckKt` e misura quanto è pulito il suono: le tracce di prova sono sinusoidi.

## Le traduzioni

`python3 tools/check_strings.py` controlla che ogni lingua abbia le stesse chiavi dell'inglese, gli
stessi segnaposto e le forme plurali giuste.
