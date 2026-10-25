#!/bin/sh
# Prepara su un runner di GitHub il Music Assistant di prova, come in tools/README.md: immagine dai
# sorgenti, musica finta, podcast finti, primo utente e servizi. In più la "Playlist grande" (i 720
# brani quattro volte). Il token finisce in $WORK/token. Uso: sh tools/measure/prepare_ma.sh
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
T="$ROOT/tools/ma-test"
W=${WORK:-$HOME/matest}
mkdir -p "$W/music" "$W/music2" "$W/data"

[ -d "$T/server" ] || git clone -q --depth 1 -b 2.10.8 https://github.com/music-assistant/server "$T/server"
cp /etc/ssl/certs/ca-certificates.crt "$T/ca.crt"
docker build -q --network host -t ma:2.10.8 "$T"

docker run --rm -v "$W/music2:/music2" -v "$T/gen_music.sh:/gen.sh:ro" --entrypoint sh ma:2.10.8 /gen.sh
docker run --rm -v "$W/music:/music" -v "$T/gen_albums.sh:/gen.sh:ro" --entrypoint sh ma:2.10.8 /gen.sh
(cd "$W/music2" && for i in 1 2 3 4; do find . -name '*.flac' | sort; done > "Playlist grande.m3u")

docker run -d --name pods --network host -v "$W/music:/music:ro" -v "$T/podcasts.py:/podcasts.py:ro" \
  --entrypoint python ma:2.10.8 /podcasts.py /music "/music/Lucio Battisti/Emozioni/cover.jpg"
docker run -d --name ma --network host -v "$W/data:/data" -v "$W/music:/music:ro" -v "$W/music2:/music2:ro" ma:2.10.8

up() { for i in $(seq 90); do curl -sf localhost:8095/info > /dev/null && return 0; sleep 2; done; return 1; }
up
# La ricerca dei podcast punta al finto iTunes (i servizi veri non servono alla prova).
docker exec ma sh -c 'f=$(python -c "import music_assistant.providers.itunes_podcasts as m;print(m.__file__)"); sed -i "s#https://itunes.apple.com#http://127.0.0.1:8770#g; s#https://rss.marketingtools.apple.com#http://127.0.0.1:8770#g" $f'
docker restart ma > /dev/null
up

python3 -m pip install -q websockets
python3 "$T/setup.py" > "$W/token"
MA_TOKEN=$(cat "$W/token") python3 "$ROOT/tools/measure/wait_library.py"
