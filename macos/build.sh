#!/usr/bin/env bash
# Native macOS release: JDK 17 with jpackage and Xcode Command Line Tools are required.
set -euo pipefail
project_root=$(cd "$(dirname "$0")/.." && pwd)
if [[ $(uname -s) != Darwin ]]; then echo 'DMG and .app packaging must run on macOS.' >&2; exit 1; fi
offline=false
case ${1:-} in
    '') ;;
    --offline) offline=true ;;
    *) echo 'Usage: bash macos/build.sh [--offline]' >&2; exit 1 ;;
esac
export JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}
export PATH="$JAVA_HOME/bin:$PATH"
for tool in java jlink jpackage; do
    if [[ ! -x "$JAVA_HOME/bin/$tool" ]]; then echo "JDK tool missing: $tool" >&2; exit 1; fi
done
java_version=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.specification.version = //p')
if [[ $java_version != 17 ]]; then echo 'Use JDK 17 (JAVA_HOME).' >&2; exit 1; fi
jdk_arch=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*os.arch = //p')
case $(uname -m):$jdk_arch in
    arm64:aarch64) arch=arm64 ;;
    x86_64:x86_64|x86_64:amd64) arch=x64 ;;
    *) echo 'Use a JDK matching the native Mac architecture; run outside Rosetta.' >&2; exit 1 ;;
esac
xcrun --find clang >/dev/null
version=$(sed -n 's/^version=//p' "$project_root/windows/gradle.properties" | tr -d '\r')
if [[ ! $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then echo 'Invalid release version.' >&2; exit 1; fi
build="$project_root/windows/desktop/build"
work=$(mktemp -d "${TMPDIR:-/tmp}/readerka-macos.XXXXXX")
trap 'rm -rf "$work"' EXIT
output="$build/release/macos-$arch"
mkdir -p "$output" "$work/input/native/djvu"
bash "$project_root/macos/scripts/build-djvu.sh" "$work/input/native/djvu"
export READERKA_DJVU_DIR="$work/input/native/djvu"
(
    cd "$project_root/windows"
    gradle_args=(--no-daemon --console=plain :shared:test :desktop:test :desktop:desktopLibraries
        -Djava.awt.headless=true "-Dorg.gradle.java.installations.paths=$JAVA_HOME")
    if $offline; then gradle_args+=(--offline); fi
    bash ./gradlew "${gradle_args[@]}"
)
cp "$build/desktop/lib/"*.jar "$work/input/"
# Windows-only binaries must not be shipped in the Mac application.
zip -d "$work/input/desktop-$version.jar" 'native/djvu/*.exe' 'native/djvu/*.dll'
cp "$project_root/macos/README.md" "$work/input/README.md"
cp "$project_root/macos/THIRD_PARTY_NOTICES.md" "$work/input/THIRD_PARTY_NOTICES.md"
cp -R "$project_root/windows/docs" "$work/input/docs"
mkdir -p "$work/input/third-party"
cp -R "$project_root/windows/third-party/licenses" "$work/input/third-party/"
for archive in djvulibre-3.5.29 jpegsrc.v6b tiff-4.0.10 zlib-1.2.11; do
    cp "$project_root/windows/third-party/$archive.tar.gz" "$work/input/third-party/"
done
cp "$project_root/windows/third-party/SHA256SUMS.txt" "$work/input/third-party/"
cp "$project_root/macos/scripts/build-djvu.sh" "$work/input/third-party/"
jlink --add-modules java.base,java.desktop,java.sql,java.logging,java.naming,java.net.http,java.management,jdk.crypto.ec,jdk.unsupported,jdk.charsets,jdk.localedata \
    --strip-debug --no-header-files --no-man-pages --compress=2 --output "$work/runtime"
jpackage --type app-image --name Readerka --app-version "$version" --vendor Readerka \
    --description 'Readerka: books, quotations and notes' --input "$work/input" \
    --main-jar "desktop-$version.jar" --main-class org.readerka.desktop.MainKt --runtime-image "$work/runtime" \
    --java-options '-Dfile.encoding=UTF-8' --mac-package-identifier org.readerka.desktop --dest "$work/image"
image="$work/image/Readerka.app"
codesign --force --deep --sign - "$image"
codesign --verify --deep --strict "$image"
actual_version=$("$image/Contents/MacOS/Readerka" --version)
if [[ $actual_version != "Readerka $version" ]]; then echo "Launcher version mismatch: $actual_version" >&2; exit 1; fi
# Verify the actual packaged JARs, SQLite native library, font and bundled DjVu processes.
env -u READERKA_DJVU_DIR "$image/Contents/runtime/Contents/Home/bin/java" -Djava.awt.headless=true \
    -cp "$image/Contents/app/*:$build/classes/kotlin/test" org.readerka.desktop.MacReleaseSmoke \
    "$project_root/windows/desktop/src/test/resources/formats/mixed.djvu"
jpackage --type dmg --name Readerka --app-version "$version" --vendor Readerka \
    --app-image "$image" --dest "$work/dmg" --mac-package-identifier org.readerka.desktop
dmg=("$work/dmg/"*.dmg)
if [[ ${#dmg[@]} != 1 || ! -f ${dmg[0]} ]]; then echo 'Expected one DMG.' >&2; exit 1; fi
cp "${dmg[0]}" "$output/readerka-$version-macos-$arch.dmg"
ditto -c -k --sequesterRsrc --keepParent "$image" "$output/readerka-$version-macos-$arch.zip"
(
    cd "$output"
    shasum -a 256 "readerka-$version-macos-$arch.dmg" "readerka-$version-macos-$arch.zip" > SHA256SUMS.txt
)
echo "macOS release ($arch): $output"
