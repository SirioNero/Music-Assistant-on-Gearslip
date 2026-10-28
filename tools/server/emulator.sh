#!/usr/bin/env bash
# Sull'emulatore (workflow "Server MA"): installa la build di prova, mette un album di toni nella
# cartella dell'app, avvia il server di Music Assistant dentro Xonos (ServerCheckActivity) e lo prova
# da fuori con check.py, attraverso adb forward. Registro, logcat e risultato vanno in $OUT.
set -uo pipefail
OUT=${OUT:?}
PACKAGE=it.xonos.ma
MUSIC=/data/user/0/$PACKAGE/files/music
mkdir -p "$OUT"

adb install -r -g app/build/outputs/apk/debug/app-debug.apk || exit 1

python3 tools/server/tones.py "$OUT/music"
adb shell run-as "$PACKAGE" mkdir -p "files/music/Check\ Artist/Check\ Album"
for file in "$OUT/music/Check Artist/Check Album"/*.wav; do
    name=$(basename "$file")
    # adb unisce gli argomenti con spazi: il comando va scritto tutto per la shell del telefono.
    adb exec-in "run-as $PACKAGE sh -c 'cat > \"files/music/Check Artist/Check Album/$name\"'" < "$file"
done
rm -rf "$OUT/music"
adb shell run-as "$PACKAGE" ls -lR files/music

adb logcat -c
adb logcat -v time > "$OUT/logcat.txt" 2>&1 &
LOGCAT=$!
adb shell am start -n "$PACKAGE/.ServerCheckActivity"

state=""
for _ in $(seq 1 600); do
    state=$(grep -o "Server: \(RUNNING\|FAILED\|STOPPED\)" "$OUT/logcat.txt" | tail -n 1)
    [ -n "$state" ] && break
    sleep 1
done
echo "== stato: ${state:-ancora in avvio dopo 10 minuti}"
grep "XonosServer" "$OUT/logcat.txt"

status=1
if [ "$state" = "Server: RUNNING" ]; then
    adb forward tcp:8095 tcp:8095
    python3 tools/server/check.py --url http://127.0.0.1:8095 --music "$MUSIC" --seconds 12 2>&1 | tee "$OUT/check.txt"
    status=${PIPESTATUS[0]}
fi

adb exec-out run-as "$PACKAGE" cat files/music-assistant/musicassistant.log > "$OUT/musicassistant.log" 2>/dev/null
kill "$LOGCAT" 2>/dev/null
grep -E " (E|W)/python\.std(err|out)|XonosServer|AndroidRuntime" "$OUT/logcat.txt" | tail -n 120
exit "$status"
