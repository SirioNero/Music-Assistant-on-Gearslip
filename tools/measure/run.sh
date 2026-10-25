#!/bin/bash
# Dentro l'emulatore (workflow "Misure"): se serve genera il Baseline Profile, poi le misure di
# baselineprofile/ contro il Music Assistant di prova (10.0.2.2:8095 visto dall'emulatore).
# Alla fine: l'app è partita? ha suonato? I risultati vanno in $OUT.
OUT=${OUT:-$PWD/measure-out}
mkdir -p "$OUT"
TOKEN=$(cat "${WORK:-$HOME/matest}/token")
ARGS=(
  "-Pandroid.testInstrumentationRunnerArguments.xonosToken=$TOKEN"
  "-Pandroid.testInstrumentationRunnerArguments.xonosUrl=http://10.0.2.2:8095"
)

# Appena acceso l'emulatore è lento e il launcher può andare in "non risponde": il suo dialogo
# coprirebbe l'app. Niente dialoghi di errore (gli arresti restano nel log) e un minuto per assestarsi.
adb shell settings put global hide_error_dialogs 1
sleep 60
adb shell input keyevent KEYCODE_WAKEUP
adb shell input keyevent KEYCODE_HOME

adb logcat -c
adb logcat -v threadtime > "$OUT/logcat.txt" 2>&1 &
LOGCAT=$!
status=0

if [ "$GENERATE" = "true" ]; then
  echo "::group::Baseline Profile"
  if gradle :app:generateBaselineProfile "${ARGS[@]}" --stacktrace 2>&1 | tee "$OUT/profile.log"; then
    mkdir -p "$OUT/profile" && cp app/src/main/generated/baselineProfiles/* "$OUT/profile/"
  else
    echo "::error title=Baseline Profile::la generazione non è riuscita (vedi profile.log)"
    status=1
  fi
  echo "::endgroup::"
fi

# Con il profilo nell'APK si misura anche la compilazione con il profilo.
MODES=${MODES:-none}
if [ -f app/src/main/generated/baselineProfiles/baseline-prof.txt ] && [ "$MODES" = "none" ]; then MODES=none,partial; fi
echo "Compilazioni: $MODES"

echo "::group::Misure"
gradle :baselineprofile:connectedBenchmarkReleaseAndroidTest "${ARGS[@]}" \
  "-Pandroid.testInstrumentationRunnerArguments.xonosModes=$MODES" \
  "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark" \
  ${CLASS:+"-Pandroid.testInstrumentationRunnerArguments.class=$CLASS"} \
  --stacktrace 2>&1 | tee "$OUT/benchmark.log"
[ "${PIPESTATUS[0]}" = 0 ] || status=1
echo "::endgroup::"
cp -r baselineprofile/build/outputs/connected_android_test_additional_output "$OUT/" 2>/dev/null
cp -r baselineprofile/build/outputs/androidTest-results "$OUT/" 2>/dev/null

kill $LOGCAT 2>/dev/null
# L'app ridotta da R8 è partita e ha suonato? Lo dicono il suo log e Music Assistant.
{
  echo "Arresti dell'app: $(grep -c 'FATAL EXCEPTION' "$OUT/logcat.txt")"
  echo "Flussi audio aperti: $(grep -cE 'Xonos *: Stream' "$OUT/logcat.txt")"
  echo "Avvii dell'audio: $(grep -c 'Avvio a' "$OUT/logcat.txt")"
  grep -m 5 -E 'Xonos *: (Stream|Avvio a)' "$OUT/logcat.txt"
  grep -A 20 'FATAL EXCEPTION' "$OUT/logcat.txt" | head -60
} > "$OUT/check.txt"
cat "$OUT/check.txt"
exit $status
