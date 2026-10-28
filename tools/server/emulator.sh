#!/usr/bin/env bash
# Sull'emulatore (workflow "Server MA"): installa la build di prova con il server e fa tre prove.
#  1. Il server da solo: mette un album di toni nella cartella dell'app, avvia Music Assistant
#     (ServerCheckActivity) e lo prova da fuori con check.py, attraverso adb forward.
#  2. Xonos stesso, da capo (dati cancellati): "Usa Music Assistant su questo telefono", la cartella
#     come servizio e il primo brano suonato dal lettore di Xonos (ServerCheckActivity --ez xonos true).
#  3. La release, ridotta con R8 come quella che si pubblica, da capo e senza scorciatoie: il tocco sul
#     tasto come lo farebbe una persona, poi un brano suonato dal lettore di Xonos (check.py --player).
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

# Il centro dell'elemento con quel testTag (per uiautomator è il resource-id): "x y". Esce con 1 se non c'è.
ui_center() {
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
    adb exec-out cat /sdcard/ui.xml > "$OUT/ui.xml"
    python3 - "$1" "$OUT/ui.xml" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
tag, path = sys.argv[1], sys.argv[2]
try:
    nodes = list(ET.parse(path).iter("node"))
except ET.ParseError:
    sys.exit(1)
for node in nodes:
    if node.get("resource-id") == tag:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
PY
}

# Aspetta che sullo schermo ci sia l'elemento $1 (al massimo $2 secondi).
wait_ui() {
    for _ in $(seq 1 $(($2 / 5))); do
        ui_center "$1" >/dev/null && return 0
        sleep 5
    done
    return 1
}

tap() {
    local xy
    xy=$(ui_center "$1") || return 1
    # shellcheck disable=SC2086
    adb shell input tap $xy
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

# 3. La release, da capo, come la installa una persona: niente permessi dati in anticipo, la musica nella
#    cartella Music del telefono (quella che propone il servizio dei file sul disco). La release non si
#    lascia guardare dentro con run-as: per leggere token e lettore, sull'emulatore (google_apis) c'è root.
echo "== La release (R8) con Music Assistant sul telefono"
# uiautomator legge lo schermo solo quando è fermo: niente animazioni.
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb shell settings put global "$scale" 0
done
adb uninstall "$PACKAGE" >/dev/null
adb install app/build/outputs/apk/release/app-release.apk || exit 1
PHONE_MUSIC=/storage/emulated/0/Music
adb shell rm -rf "'$PHONE_MUSIC/Check Artist'"
adb push "$OUT/music/Check Artist" "$PHONE_MUSIC/" >/dev/null
adb root >/dev/null
adb wait-for-device
DATA=/data/user/0/$PACKAGE
ALLOW=com.android.permissioncontroller:id/permission_allow_button
adb logcat -c
adb logcat -v time > "$OUT/logcat-release.txt" 2>&1 &
LOGCAT=$!
adb shell am start -n "$PACKAGE/.phone.MainActivity"
release=1
connected=false
if wait_ui use_phone_server 60 && tap use_phone_server; then
    # Android chiede il permesso per la musica del telefono: si tocca "Consenti".
    if wait_ui "$ALLOW" 30 && tap "$ALLOW"; then
        echo "permesso per la musica concesso"
    else
        echo "ERRORE: Xonos non ha chiesto il permesso per la musica"
    fi
    wait_ui onboarding_done 300 && connected=true
fi
if $connected; then
    sleep 2
    prefs=$(adb exec-out cat "$DATA/shared_prefs/xonos.xml")
    token=$(echo "$prefs" | sed -n 's/.*<string name="token">\([^<]*\)<.*/\1/p')
    player=$(echo "$prefs" | sed -n 's/.*<string name="player_id">\([^<]*\)<.*/\1/p')
    echo "Xonos collegato al server sul telefono, lettore ${player:-?}"
    [ -n "$token" ] && [ -n "$player" ] || echo "ERRORE: Xonos non ha salvato token e lettore"
    tap onboarding_done
    adb forward tcp:8095 tcp:8095
    if [ -n "$token" ] && [ -n "$player" ]; then
        python3 tools/server/check.py --url http://127.0.0.1:8095 --token "$token" --music "$PHONE_MUSIC" --player "$player" --seconds 15 2>&1 | tee "$OUT/check-release.txt"
        release=${PIPESTATUS[0]}
    fi
    adb forward --remove tcp:8095
else
    echo "ERRORE: la release non si è collegata al server sul telefono"
fi
adb exec-out screencap -p > "$OUT/release.png"
adb exec-out cat "$DATA/files/music-assistant/musicassistant.log" > "$OUT/musicassistant-release.log" 2>/dev/null
kill "$LOGCAT" 2>/dev/null
grep -E "Xonos|AndroidRuntime|python\.std(err|out)" "$OUT/logcat-release.txt" | tail -n 80
[ "$release" = 0 ] || status=1
exit "$status"
