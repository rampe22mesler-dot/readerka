#include <jni.h>
#include <android/bitmap.h>
#include <tiffio.h>
#include <memory>
#include <vector>
#include <stdexcept>

using Tiff = std::unique_ptr<TIFF, decltype(&TIFFClose)>;
static Tiff open(JNIEnv *env, jstring path, int page) {
    const char *name = env->GetStringUTFChars(path, nullptr);
    TIFF *raw = TIFFOpen(name, "r"); env->ReleaseStringUTFChars(path, name);
    Tiff file(raw, &TIFFClose);
    if (!file || page < 0 || page > 20000 || !TIFFSetDirectory(file.get(), page)) throw std::runtime_error("Cannot open TIFF page");
    return file;
}
static void fail(JNIEnv *env, const std::exception &e) { env->ThrowNew(env->FindClass("java/io/IOException"), e.what()); }
extern "C" JNIEXPORT jint JNICALL Java_org_readerka_importer_NativeTiff_pageCount(JNIEnv *env, jclass, jstring path) {
    try { auto file = open(env, path, 0); return TIFFNumberOfDirectories(file.get()); } catch (const std::exception &e) { fail(env, e); return 0; }
}
extern "C" JNIEXPORT jintArray JNICALL Java_org_readerka_importer_NativeTiff_pageSize(JNIEnv *env, jclass, jstring path, jint index) {
    try {
        auto file = open(env, path, index); uint32_t w = 0, h = 0;
        TIFFGetField(file.get(), TIFFTAG_IMAGEWIDTH, &w); TIFFGetField(file.get(), TIFFTAG_IMAGELENGTH, &h);
        if (!w || !h || uint64_t(w) * h > 40000000) throw std::runtime_error("TIFF exceeds 40 million pixels");
        jint size[2] = {jint(w), jint(h)}; auto result = env->NewIntArray(2); env->SetIntArrayRegion(result, 0, 2, size); return result;
    } catch (const std::exception &e) { fail(env, e); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_org_readerka_importer_NativeTiff_render(JNIEnv *env, jclass, jstring path, jint index, jobject bitmap) {
    try {
        auto file = open(env, path, index); uint32_t w = 0, h = 0;
        TIFFGetField(file.get(), TIFFTAG_IMAGEWIDTH, &w); TIFFGetField(file.get(), TIFFTAG_IMAGELENGTH, &h);
        if (!w || !h || uint64_t(w) * h > 40000000) throw std::runtime_error("TIFF exceeds 40 million pixels");
        AndroidBitmapInfo info;
        if (AndroidBitmap_getInfo(env, bitmap, &info) || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || !info.width || !info.height || uint64_t(info.width) * info.height > 4000000) throw std::runtime_error("Invalid TIFF bitmap");
        std::vector<uint32_t> raster(size_t(w) * h);
        if (!TIFFReadRGBAImageOriented(file.get(), w, h, raster.data(), ORIENTATION_TOPLEFT, 0)) throw std::runtime_error("Cannot decode TIFF");
        void *pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels)) throw std::runtime_error("Cannot lock TIFF bitmap");
        for (uint32_t y = 0; y < info.height; ++y) {
            auto *out = static_cast<unsigned char*>(pixels) + y * info.stride;
            for (uint32_t x = 0; x < info.width; ++x) {
                uint32_t color = raster[size_t(uint64_t(y) * h / info.height) * w + uint64_t(x) * w / info.width];
                // LibTIFF's RGBA raster is premultiplied; flatten transparency against white.
                unsigned alpha = TIFFGetA(color);
                out[x*4] = TIFFGetR(color) + 255 - alpha; out[x*4+1] = TIFFGetG(color) + 255 - alpha;
                out[x*4+2] = TIFFGetB(color) + 255 - alpha; out[x*4+3] = 255;
            }
        }
        AndroidBitmap_unlockPixels(env, bitmap);
    } catch (const std::exception &e) { fail(env, e); }
}
