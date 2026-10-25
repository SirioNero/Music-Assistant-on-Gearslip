#!/bin/sh
# 30 artisti x 4 album x 6 brani: abbastanza per superare i 500 brani di un elenco.
set -e
cd /music2
i=0
for a in Adele Battiato Caparezza "Dalla Lucio" Elisa "Fabrizio Moro" Gazzelle "Hozier" "Irama" "Jovanotti" "Kings of Leon" "Ligabue" "Mahmood" "Negramaro" "Oasis" "Pinguini Tattici Nucleari" "Queen" "Ramazzotti" "Subsonica" "Tiziano Ferro" "Ultimo" "Vasco Rossi" "Wham" "Xavier Rudd" "Yes" "Zucchero" "99 Posse" "Ãlvaro Soler" "The Beatles" "Ennio Morricone"; do
  for b in 1 2 3 4; do
    d="$a/Album $b di $a"; mkdir -p "$d"
    for t in 1 2 3 4 5 6; do
      i=$((i+1)); f=$((200 + (i % 40) * 10))
      ffmpeg -loglevel error -y -f lavfi -i "sine=f=$f:d=2" -ac 2 -ar 44100 -metadata artist="$a" -metadata album_artist="$a" \
        -metadata album="Album $b di $a" -metadata title="Brano $t di $a ($b)" -metadata track="$t" -metadata date="20$((10+b))" "$d/$t.flac"
    done
  done
done
echo "generati $i brani"
