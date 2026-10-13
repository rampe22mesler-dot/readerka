#!/usr/bin/env bash
set -euo pipefail
readerka_root=$(cd "$(dirname "$0")/.." && pwd)
source "$readerka_root/scripts/cloud-env.sh"
export JAVA_OPTS="$(cat /workspace/toolchains/java-network-options)"
readerka_api=${READERKA_ANDROID_API:-30}
[[ "$readerka_api" == 28 || "$readerka_api" == 30 ]] || { echo "Supported test APIs: 28, 30" >&2; exit 1; }
readerka_avd="readerka-tablet-api$readerka_api"
if [[ ! -f "$ANDROID_AVD_HOME/$readerka_avd.ini" ]]; then
    printf 'no\n' | avdmanager create avd -n "$readerka_avd" -k "system-images;android-$readerka_api;default;x86_64" --device 'Nexus 10'
    READERKA_AVD_NAME="$readerka_avd" python3 - <<'PY'
import os,pathlib
p=pathlib.Path(os.environ['ANDROID_AVD_HOME'])/(os.environ['READERKA_AVD_NAME']+'.avd/config.ini')
values=dict(line.split('=',1) for line in p.read_text().splitlines() if '=' in line)
values.update({'hw.lcd.width':'1280','hw.lcd.height':'900','hw.lcd.density':'160','hw.keyboard':'yes','hw.ramSize':'2048'})
p.write_text(''.join(k+' = '+v.strip()+'\n' for k,v in values.items()))
PY
fi
# Run in the foreground so the caller controls this emulator's lifetime.
# Use ANDROID_SERIAL from `adb devices` when another emulator is running.
exec "$ANDROID_HOME/emulator/emulator" -avd "$readerka_avd" -accel off -no-window -no-audio -no-boot-anim -gpu "${READERKA_GPU:-lavapipe}" -memory 3072 -cores 4 -no-snapshot -skip-adb-auth -no-metrics
