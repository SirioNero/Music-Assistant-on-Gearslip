#!/usr/bin/env bash
# Sull'emulatore (workflow "Server MA"): installa la build di prova con il server e fa due prove.
#  1. Il server da solo: mette un album di toni nella cartella dell'app, avvia Music Assistant
#     (ServerCheckActivity) e lo prova da fuori con check.py, attraverso adb forward.
#  2. Xonos stesso, da capo (dati cancellati): "Usa Music Assistant su questo telefono", la cartella
#     come servizio e il primo brano suonato dal lettore di Xonos (ServerCheckActivity --ez xonos true).
# Registro, logcat e risultati vanno in $OUT.
set -uo pipefail
OUT=${OUT:?}
PACKAGE=it.xonos.ma
MUSIC=/data/user/0/$PACKAGE/files/music
mkdir -p "$OUT"

adb install -r -g app/build/outputs/apk/debug/app-debug.apk || exit 1
python3 tools/server/tones.py "$OUT/music"

copy_music() {
    adb shell rm -rf /data/local/tmp/music
    adb push "$OUT/music" /data/local/tmp/ >/dev/null
    # La cartella dell'app la vede solo l'app: si copia con run-as (la build di prova è debuggable).
    adb shell run-as "$PACKAGE" mkdir -p files
    adb shell run-as "$PACKAGE" cp -r /data/local/tmp/music files/
    # Una copia a metà farebbe passare la prova con un brano più corto.
    local expected copied
    expected=$(find "$OUT/music" -type f -name '*.wav' -printf '%s\n' | sort | tr '\n' ' ')
    copied=$(adb shell run-as "$PACKAGE" find files/music -type f -name "'*.wav'" -exec stat -c %s {} + | tr -d '\r' | sort | tr '\n' ' ')
    echo "musica di prova: attesi $expected copiati $copied"
    [ "$expected" = "$copied" ]
}

# Aspetta nel logcat $1 una riga che corrisponde a $2 (al massimo $3 secondi) e la scrive.
wait_for() {
    local line=""
    for _ in $(seq 1 "$3"); do
        line=$(grep -o "$2" "$1" | tail -n 1)
        [ -n "$line" ] && break
        sleep 1
    done
    echo "$line"
}

# 1. Il server da solo.
copy_music || { echo "La musica di prova non è arrivata intera"; exit 1; }
adb logcat -c
adb logcat -v time > "$OUT/logcat.txt" 2>&1 &
LOGCAT=$!
adb shell am start -n "$PACKAGE/.ServerCheckActivity"
state=$(wait_for "$OUT/logcat.txt" "Server: \(RUNNING\|FAILED\|STOPPED\)" 600)
echo "== stato: ${state:-ancora in avvio dopo 10 minuti}"
grep "XonosServer" "$OUT/logcat.txt"
status=1
if [ "$state" = "Server: RUNNING" ]; then
    adb forward tcp:8095 tcp:8095
    python3 tools/server/check.py --url http://127.0.0.1:8095 --music "$MUSIC" --seconds 12 2>&1 | tee "$OUT/check.txt"
    status=${PIPESTATUS[0]}
    adb forward --remove tcp:8095
fi
adb exec-out run-as "$PACKAGE" cat files/music-assistant/musicassistant.log > "$OUT/musicassistant.log" 2>/dev/null
kill "$LOGCAT" 2>/dev/null
grep -E " (E|W)/python\.std(err|out)|AndroidRuntime" "$OUT/logcat.txt" | tail -n 60

# 2. Xonos stesso, da capo.
echo "== Xonos con Music Assistant sul telefono"
adb shell am force-stop "$PACKAGE"
adb shell pm clear "$PACKAGE" >/dev/null
copy_music || { echo "La musica di prova non è arrivata intera"; exit 1; }
adb logcat -c
adb logcat -v time > "$OUT/logcat-xonos.txt" 2>&1 &
LOGCAT=$!
adb shell am start -n "$PACKAGE/.ServerCheckActivity" --ez xonos true
result=$(wait_for "$OUT/logcat-xonos.txt" "Xonos: \(suona=.*\|prova interrotta.*\|collegato al server sul telefono = false.*\)" 600)
kill "$LOGCAT" 2>/dev/null
grep -E "XonosServer|I/Xonos|W/Xonos|AndroidRuntime" "$OUT/logcat-xonos.txt" | tail -n 80
echo "== Xonos: ${result:-nessun risultato dopo 10 minuti}"
adb exec-out run-as "$PACKAGE" cat files/music-assistant/musicassistant.log > "$OUT/musicassistant-xonos.log" 2>/dev/null
position=$(echo "$result" | sed -n 's/.*posizione=\([0-9]*\).*/\1/p')
if ! echo "$result" | grep -q "suona=true" || [ "${position:-0}" -lt 5 ]; then
    echo "ERRORE: Xonos non ha suonato dal server sul telefono"
    status=1
fi
exit "$status"
