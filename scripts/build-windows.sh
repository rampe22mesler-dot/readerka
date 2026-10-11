#!/usr/bin/env bash
# Build a portable Windows x64 distribution on Linux or Windows Git Bash.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f /workspace/toolchains/jdk-17.0.16+8/bin/java ]]; then source scripts/cloud-env.sh; fi
./gradlew :desktop:windowsLibraries --configure-on-demand --max-workers=2
readerka_default_tools="${TMPDIR:-/tmp}/readerka-desktop-toolchains"
if [[ -d /workspace/toolchains/desktop ]]; then readerka_default_tools=/workspace/toolchains/desktop; fi
readerka_toolchains="${READERKA_DESKTOP_TOOLCHAINS:-$readerka_default_tools}"
mkdir -p "$readerka_toolchains/downloads"
readerka_jre_zip="$readerka_toolchains/downloads/windows-jre.zip"
if [[ ! -f "$readerka_jre_zip" ]]; then
  curl -fL --retry 2 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.16%2B8/OpenJDK17U-jre_x64_windows_hotspot_17.0.16_8.zip' -o "$readerka_jre_zip"
fi
python3 scripts/package-windows.py "$readerka_jre_zip"
