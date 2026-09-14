#!/usr/bin/env bash
# Run the RMS Android app in the emulator against a local RMS dev server.
#
#   ./dev-emulator.sh                      # app -> http://10.0.2.2:5173 (host's localhost:5173)
#   ./dev-emulator.sh https://10.0.2.2:5173  # Vite started with HTTPS=1
#   ./dev-emulator.sh prod                 # back to https://rms2.dfirentals.com
#
# Start the RMS first:  cd ~/Desktop/rms && npm run dev
# Needs: Android SDK (local.properties), an AVD, and a JDK 11-15 for Gradle 6.7
# (brew install openjdk@11). Release builds ignore the URL override entirely.
set -euo pipefail
cd "$(dirname "$0")"

SDK="$(sed -n 's/^sdk.dir=//p' local.properties)"
ADB="$SDK/platform-tools/adb"
EMU="$SDK/emulator/emulator"
AVD="${AVD:-$("$EMU" -list-avds | head -1)}"
URL="${1:-http://10.0.2.2:5173}"
[ "$URL" = "prod" ] && URL=""

if [ -z "${JAVA_HOME:-}" ]; then
  for c in /opt/homebrew/opt/openjdk@11/libexec/openjdk.jdk/Contents/Home \
           /usr/local/opt/openjdk@11/libexec/openjdk.jdk/Contents/Home \
           /Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home; do
    [ -d "$c" ] && { export JAVA_HOME="$c"; break; }
  done
fi
[ -n "${JAVA_HOME:-}" ] || { echo "No JDK 11 found. brew install openjdk@11"; exit 1; }
echo "JAVA_HOME=$JAVA_HOME"

if ! "$ADB" devices | grep -q 'device$'; then
  echo "Booting emulator $AVD ..."
  "$EMU" -avd "$AVD" -netdelay none -netspeed full >/dev/null 2>&1 &
  "$ADB" wait-for-device
fi
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
echo "Emulator ready."

./gradlew assembleDebug -q
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell am force-stop com.dfirentals.rms
"$ADB" shell am start -n com.dfirentals.rms/.MainActivity --es rms_url "$URL"
echo "Launched -> ${URL:-https://rms2.dfirentals.com}"
