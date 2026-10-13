#!/usr/bin/env bash
set -euo pipefail
readerka_root=$(cd "$(dirname "$0")/.." && pwd)
readerka_sdk=${1:?Android SDK path required}
readerka_output=${2:-"$readerka_root/app/build/generated/djvu"}
readerka_native="$readerka_root/app/build/djvu-native"
readerka_llvm="$readerka_sdk/ndk/26.3.11579264/toolchains/llvm/prebuilt/linux-x86_64/bin"
[[ -x "$readerka_llvm/clang++" ]] || { echo 'Install Android NDK 26.3.11579264 first' >&2; exit 1; }
mkdir -p "$readerka_native/src" "$readerka_output"
cd "$readerka_root/windows/third-party"
sha256sum -c SHA256SUMS.txt
if [[ ! -f "$readerka_native/src/djvulibre-3.5.29/configure" ]]; then
    tar -xzf djvulibre-3.5.29.tar.gz -C "$readerka_native/src"
    tar -xzf jpegsrc.v6b.tar.gz -C "$readerka_native/src"
    tar -xzf tiff-4.0.10.tar.gz -C "$readerka_native/src"
    tar -xzf zlib-1.2.11.tar.gz -C "$readerka_native/src"
fi
[[ -d "$readerka_native/src/tiff-4.0.10" ]] || tar -xzf tiff-4.0.10.tar.gz -C "$readerka_native/src"
[[ -d "$readerka_native/src/zlib-1.2.11" ]] || tar -xzf zlib-1.2.11.tar.gz -C "$readerka_native/src"
for readerka_abi in arm64-v8a x86_64; do
    if [[ "$readerka_abi" == arm64-v8a ]]; then readerka_target=aarch64-linux-android; readerka_jpeg_host=arm-linux; else readerka_target=x86_64-linux-android; readerka_jpeg_host=x86_64-linux; fi
    readerka_build="$readerka_native/$readerka_abi"
    mkdir -p "$readerka_build/jpeg" "$readerka_build/djvu" "$readerka_build/tiff" "$readerka_build/zlib" "$readerka_build/prefix" "$readerka_output/$readerka_abi"
    export CC="$readerka_llvm/${readerka_target}28-clang"
    export CXX="$readerka_llvm/${readerka_target}28-clang++"
    export AR="$readerka_llvm/llvm-ar" RANLIB="$readerka_llvm/llvm-ranlib"
    export CFLAGS='-O2 -fPIC -std=gnu89' CXXFLAGS='-O2 -fPIC -std=c++11'
    if [[ ! -f "$readerka_build/prefix/lib/libjpeg.a" ]]; then
        # IJG 6b supports an out-of-source build once its source make rules are copied.
        cd "$readerka_build/jpeg"
        "$readerka_native/src/jpeg-6b/configure" --host="$readerka_jpeg_host" --build=x86_64-linux --disable-shared --prefix="$readerka_build/prefix" > configure.log 2>&1
        make -j2 libjpeg.a > build.log 2>&1
        mkdir -p "$readerka_build/prefix/lib" "$readerka_build/prefix/include"
        cp libjpeg.a "$readerka_build/prefix/lib/"
        cp "$readerka_native/src/jpeg-6b/jpeglib.h" "$readerka_native/src/jpeg-6b/jmorecfg.h" "$readerka_native/src/jpeg-6b/jerror.h" jconfig.h "$readerka_build/prefix/include/"
    fi
    if [[ ! -f "$readerka_build/prefix/lib/libz.a" ]]; then
        cd "$readerka_build/zlib"
        CHOST="$readerka_target" "$readerka_native/src/zlib-1.2.11/configure" --static --prefix="$readerka_build/prefix" > configure.log 2>&1
        make -j2 > build.log 2>&1
        make install >> build.log 2>&1
    fi
    cd "$readerka_build/tiff"
    if [[ ! -f Makefile ]]; then
        CPPFLAGS="-I$readerka_build/prefix/include" LDFLAGS="-L$readerka_build/prefix/lib" "$readerka_native/src/tiff-4.0.10/configure" \
            --host="$readerka_target" --build=x86_64-linux-gnu --enable-static --disable-shared --disable-tools --disable-tests --disable-docs --disable-cxx --disable-lzma --disable-jbig --disable-webp --prefix="$readerka_build/prefix" > configure.log 2>&1
    fi
    make -C port -j2 > build.log 2>&1
    make -C libtiff -j2 libtiff.la >> build.log 2>&1
    cd "$readerka_build/djvu"
    if [[ ! -f Makefile ]]; then
        CPPFLAGS="-I$readerka_build/prefix/include" LDFLAGS="-L$readerka_build/prefix/lib" "$readerka_native/src/djvulibre-3.5.29/configure" \
            --host="$readerka_target" --build=x86_64-linux-gnu --enable-static --disable-shared --disable-desktopfiles --disable-xmltools --prefix="$readerka_build/prefix" > configure.log 2>&1
    fi
    make -C libdjvu -j2 > build.log 2>&1
    "$CXX" -shared -fPIC -O2 -std=c++11 -static-libstdc++ \
        -I"$readerka_native/src/djvulibre-3.5.29/libdjvu" \
        -I"$readerka_native/src/tiff-4.0.10/libtiff" -I"$readerka_build/tiff/libtiff" \
        "$readerka_root/app/src/main/cpp/djvu_jni.cpp" \
        "$readerka_root/app/src/main/cpp/tiff_jni.cpp" \
        libdjvu/.libs/libdjvulibre.a "$readerka_build/tiff/libtiff/.libs/libtiff.a" "$readerka_build/prefix/lib/libjpeg.a" "$readerka_build/prefix/lib/libz.a" -ljnigraphics -llog \
        -o "$readerka_output/$readerka_abi/libreaderka_djvu.so"
done
