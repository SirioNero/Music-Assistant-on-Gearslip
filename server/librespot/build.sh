#!/usr/bin/env bash
# librespot per il servizio Spotify del server dentro Xonos, compilato per Android dal sorgente: il fork
# di Music Assistant (music-assistant/librespot, ramo mass), lo stesso che Music Assistant porta per
# Linux e macOS.
#
#   ANDROID_NDK=<cartella dell'NDK> server/librespot/build.sh <abi> <cartella di uscita>
#
# Music Assistant lo avvia per ogni brano (--single-track --passthrough): l'Ogg Vorbis di Spotify passa
# così com'è a ffmpeg. Il fork non ha uscite audio; qui TLS con rustls (niente OpenSSL). Il programma si
# chiama liblibrespot.so per lo stesso motivo di ffmpeg (vedi ffmpeg/build.sh). Va solo con gli account
# Spotify Premium creati prima di dicembre 2024 (lo dice Music Assistant). Se l'uscita c'è già, compilata
# da questa stessa versione dello script, non rifà niente.
set -euo pipefail

LIBRESPOT_COMMIT=59a21d48d296a17544d0231e2ffeeced78fda186
CARGO_NDK_VERSION=4.1.2
API=26

ABI=${1:?abi}
OUT=$(mkdir -p "${2:?cartella di uscita}" && cd "$2" && pwd)/$ABI
NDK=${ANDROID_NDK:?serve ANDROID_NDK con la cartella dell’NDK}
# Fuori dalla cartella delle librerie, che finisce nell'APK così com'è.
STAMP="$(dirname "$OUT")/.build-$ABI"
ME=$(sha256sum "$0" | cut -d' ' -f1)
if [ -f "$STAMP" ] && [ "$(cat "$STAMP")" = "$ME" ] && [ -f "$OUT/liblibrespot.so" ]; then
    echo "librespot per $ABI già pronto in $OUT"
    exit 0
fi

case "$ABI" in
    arm64-v8a) TARGET=aarch64-linux-android ;;
    x86_64) TARGET=x86_64-linux-android ;;
    *) echo "ABI non prevista: $ABI"; exit 1 ;;
esac
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

rustup target add "$TARGET" >/dev/null
command -v cargo-ndk >/dev/null || cargo install --locked cargo-ndk --version "$CARGO_NDK_VERSION" >/dev/null

echo "librespot ${LIBRESPOT_COMMIT:0:7} per $ABI"
git -C "$WORK" init -q
git -C "$WORK" fetch -q --depth 1 https://github.com/music-assistant/librespot.git "$LIBRESPOT_COMMIT"
git -C "$WORK" checkout -q FETCH_HEAD
(
    cd "$WORK"
    ANDROID_NDK_HOME="$NDK" ANDROID_NDK_ROOT="$NDK" cargo ndk -t "$ABI" -P "$API" build --release --locked \
        --no-default-features --features "rustls-tls-webpki-roots,with-libmdns,passthrough-decoder"
)

rm -rf "$OUT"
mkdir -p "$OUT"
cp "$WORK/target/$TARGET/release/librespot" "$OUT/liblibrespot.so"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$OUT/liblibrespot.so"
echo "$ME" > "$STAMP"
ls -la "$OUT"
