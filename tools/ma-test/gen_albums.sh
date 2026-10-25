#!/bin/sh
# Qualche album "vero" per /music: brani di un minuto, copertine colorate (cover.jpg) e testi.
# Il primo album ha i testi sincronizzati in file .lrc accanto ai brani, il secondo nel tag LYRICS
# (in formato LRC), il terzo testi semplici senza tempi. Uso, nel container di MA:
#   docker run --rm -v $PWD/music:/music -v $PWD/ma-test/gen_albums.sh:/gen.sh:ro --entrypoint sh ma:2.10.8 /gen.sh
set -e
cd /music

cover() { # cartella, colore, colore2
  ffmpeg -loglevel error -y -f lavfi -i "gradients=s=600x600:c0=$2:c1=$3:x0=0:y0=0:x1=600:y1=600:d=1" -frames:v 1 "$1/cover.jpg"
}

lrc() { # titolo: un verso ogni 6 secondi
  for n in 0 1 2 3 4 5 6 7 8 9; do
    printf '[00:%02d.00]%s, verso %d\n' $((n * 6)) "$1" $((n + 1))
  done
}

album() { # artista, album, anno, colore, colore2, modo dei testi (lrc|tag|plain), titoli...
  artist=$1 name=$2 year=$3 c0=$4 c1=$5 mode=$6
  shift 6
  dir="$artist/$name"; mkdir -p "$dir"
  cover "$dir" "$c0" "$c1"
  t=0
  for title in "$@"; do
    t=$((t + 1)); f=$((220 + t * 40))
    file="$dir/$(printf %02d $t) $title.flac"
    lyrics=""
    case $mode in
      lrc) lrc "$title" > "${file%.flac}.lrc" ;;
      tag) lyrics=$(lrc "$title") ;;
      plain) lyrics=$(printf '%s\nprima strofa\nseconda strofa\nritornello' "$title") ;;
    esac
    ffmpeg -loglevel error -y -f lavfi -i "sine=f=$f:d=60" -ac 2 -ar 44100 \
      -metadata artist="$artist" -metadata album_artist="$artist" -metadata album="$name" -metadata title="$title" \
      -metadata track="$t" -metadata date="$year" -metadata genre="Pop" ${lyrics:+-metadata "LYRICS=$lyrics"} "$file"
  done
}

album "Lucio Battisti" "Emozioni" 1970 0x1d4e89 0x6ec6ff lrc "Emozioni" "Acqua azzurra, acqua chiara" "Mi ritorni in mente" "Fiori rosa, fiori di pesco"
album "Vasco Rossi" "Bollicine" 1983 0xc2410c 0xfbbf24 tag "Bollicine" "Vita spericolata" "Portatemi Dio" "Una canzone per te"
album "Mina" "Studio Uno" 1965 0x9d174d 0xf9a8d4 plain "Se telefonando" "Un anno d'amore" "Brava"
album "Ennio Morricone" "C'era una volta il West" 1968 0x3f3f46 0xd6d3d1 none "C'era una volta il West" "L'uomo dell'armonica" "Jill's America"
echo "album pronti"
