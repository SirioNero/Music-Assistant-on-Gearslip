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
adb shell rm -rf /data/local/tmp/music
adb push "$OUT/music" /data/local/tmp/ >/dev/null
# La cartella dell'app la vede solo l'app: si copia con run-as (la build di prova è debuggable).
adb shell run-as "$PACKAGE" cp -r /data/local/tmp/music files/
adb shell run-as "$PACKAGE" ls -lR files/music
# Una copia a metà farebbe passare la prova con un brano più corto.
expected=$(find "$OUT/music" -type f -name '*.wav' -printf '%s\n' | sort | tr '\n' ' ')
copied=$(adb shell run-as "$PACKAGE" find files/music -type f -name "'*.wav'" -exec stat -c %s {} + | tr -d '\r' | sort | tr '\n' ' ')
echo "attesi: $expected copiati: $copied"
[ "$expected" = "$copied" ] || { echo "La musica di prova non è arrivata intera"; exit 1; }
rm -rf "$OUT/music"

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
