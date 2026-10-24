#!/usr/bin/env bash
# Build self-contained command-line decoders from the source archives in the repository.
set -euo pipefail
project_root=$(cd "$(dirname "$0")/../.." && pwd)
output=${1:-"$project_root/windows/desktop/build/native/djvu"}
mkdir -p "$output"
output=$(cd "$output" && pwd)
work=$(mktemp -d "${TMPDIR:-/tmp}/readerka-djvu.XXXXXX")
trap 'rm -rf "$work"' EXIT
stage="$work/install"
mkdir -p "$stage"
jobs=${READERKA_BUILD_JOBS:-2}
case $(uname -s) in
    Darwin) export MACOSX_DEPLOYMENT_TARGET=12.0 ;;
    Linux) ;; # Allows the same decoder and import tests to run in the Linux development environment.
    *) echo 'DjVu build requires macOS or Linux.' >&2; exit 1 ;;
esac
(
    cd "$project_root/windows/third-party"
    shasum -a 256 -c SHA256SUMS.txt
)
for archive in djvulibre-3.5.29 jpegsrc.v6b tiff-4.0.10 zlib-1.2.11; do
    tar -xzf "$project_root/windows/third-party/$archive.tar.gz" -C "$work"
done
# JPEG 6b predates Apple Silicon; current canonical host detection comes from DjVuLibre.
cp "$work/djvulibre-3.5.29/config/config.guess" "$work/jpeg-6b/config.guess"
cp "$work/djvulibre-3.5.29/config/config.sub" "$work/jpeg-6b/config.sub"
(
    cd "$work/jpeg-6b"
    CFLAGS='-O2 -std=gnu89' ./configure --prefix="$stage" --disable-shared --enable-static
    make -j"$jobs"
    mkdir -p "$stage/include" "$stage/lib"
    cp .libs/libjpeg.a "$stage/lib/"
    cp jpeglib.h jmorecfg.h jconfig.h jerror.h "$stage/include/"
)
(
    cd "$work/zlib-1.2.11"
    # Current Apple SDKs define TARGET_OS_MAC for Darwin too. The old zlib test
    # mistakes this for classic Mac OS and replaces the POSIX fdopen declaration.
    if [[ $(uname -s) == Darwin ]]; then
        sed 's/#if defined(MACOS) || defined(TARGET_OS_MAC)/#if defined(MACOS)/' zutil.h > zutil.h.tmp
        mv zutil.h.tmp zutil.h
    fi
    ./configure --prefix="$stage" --static
    make -j"$jobs"
    make install
)
(
    cd "$work/tiff-4.0.10"
    CPPFLAGS="-I$stage/include" LDFLAGS="-L$stage/lib" ./configure --prefix="$stage" \
        --disable-shared --enable-static --disable-cxx --disable-jbig --disable-lzma \
        --disable-zstd --disable-webp --without-x
    make -C port -j"$jobs"
    make -C libtiff -j"$jobs"
    make -C libtiff install
)
(
    cd "$work/djvulibre-3.5.29"
    JPEG_CFLAGS="-I$stage/include" JPEG_LIBS="-L$stage/lib -ljpeg" \
    TIFF_CFLAGS="-I$stage/include" TIFF_LIBS="$stage/lib/libtiff.a $stage/lib/libjpeg.a $stage/lib/libz.a -lm" \
        ./configure --prefix="$stage" --disable-shared --enable-static --disable-xmltools --disable-desktopfiles
    make -C libdjvu -j"$jobs"
    platform_libs=''
    if [[ $(uname -s) == Darwin ]]; then platform_libs='-framework CoreFoundation'; fi
    make -C tools -j"$jobs" ddjvu djvused "LIBS=$stage/lib/libjpeg.a $stage/lib/libz.a -lm $platform_libs"
    cp tools/ddjvu tools/djvused "$output/"
)
chmod 755 "$output/ddjvu" "$output/djvused"
if [[ $(uname -s) == Darwin ]]; then
    for decoder in "$output/ddjvu" "$output/djvused"; do
        # A release must not reference Homebrew, the build directory, or any other external library.
        if ! otool -L "$decoder" | awk 'NR > 1 && $1 !~ /^\/(usr\/lib|System\/Library)\// { print; bad=1 } END { exit bad }'; then
            echo "Unexpected decoder dependency: $decoder" >&2; exit 1
        fi
        codesign --force --sign - "$decoder"
    done
fi
echo "DjVuLibre decoders: $output"
