#!/bin/sh
# Copia nel banco di prova i file dell'app che girano anche sulla JVM.
SRC="$(cd "$(dirname "$0")/../../app/src/main/java/it/xonos/ma" && pwd)"
DST=$(dirname "$0")/src/main/kotlin/app
rm -rf "$DST" && mkdir -p "$DST"
for f in data/Model.kt data/MaClient.kt data/MaRepository.kt data/Library.kt playback/Menu.kt sendspin/TimeFilter.kt sendspin/AudioEngine.kt sendspin/SendspinClient.kt "$@"; do
  mkdir -p "$DST/$(dirname $f)"; cp "$SRC/$f" "$DST/$f"
done
