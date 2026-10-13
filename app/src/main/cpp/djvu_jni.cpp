#include <jni.h>
#include <android/bitmap.h>
#include <ddjvuapi.h>
#include <miniexp.h>
#include <chrono>
#include <thread>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

struct Document {
    ddjvu_context_t *context = nullptr;
    ddjvu_document_t *document = nullptr;
    ~Document() { if (document) ddjvu_document_release(document); if (context) ddjvu_context_release(context); }
};
static void error(JNIEnv *env, const std::exception &e) { env->ThrowNew(env->FindClass("java/io/IOException"), e.what()); }
static Document &get(jlong handle) { if (!handle) throw std::runtime_error("DjVu is closed"); return *reinterpret_cast<Document*>(handle); }
static void messages(Document &d) {
    while (const ddjvu_message_t *message = ddjvu_message_peek(d.context)) {
        std::string failure = message->m_any.tag == DDJVU_ERROR ? message->m_error.message : "";
        ddjvu_message_pop(d.context);
        if (!failure.empty()) throw std::runtime_error(failure);
    }
}
template<typename Predicate> static void wait(Document &d, Predicate ready) {
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(60);
    while (!ready()) {
        messages(d);
        if (std::chrono::steady_clock::now() > deadline) throw std::runtime_error("DjVu decoding timed out");
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    messages(d);
}
struct Page {
    ddjvu_page_t *page;
    Page(Document &d, int number) : page(ddjvu_page_create_by_pageno(d.document, number)) {
        if (!page) throw std::runtime_error("Cannot open DjVu page");
        try { wait(d, [&] { return ddjvu_page_decoding_done(page); });
            if (ddjvu_page_decoding_error(page)) throw std::runtime_error("Cannot decode DjVu page");
        } catch (...) { ddjvu_page_release(page); throw; }
    }
    ~Page() { ddjvu_page_release(page); }
};

extern "C" JNIEXPORT jlong JNICALL Java_org_readerka_importer_NativeDjvu_open(JNIEnv *env, jclass, jstring path) {
    try {
        std::unique_ptr<Document> d(new Document());
        d->context = ddjvu_context_create("Readerka Android");
        if (!d->context) throw std::runtime_error("Cannot create DjVu context");
        const char *name = env->GetStringUTFChars(path, nullptr);
        d->document = ddjvu_document_create_by_filename_utf8(d->context, name, 1);
        env->ReleaseStringUTFChars(path, name);
        if (!d->document) throw std::runtime_error("Cannot open DjVu file");
        wait(*d, [&] { return ddjvu_document_decoding_done(d->document); });
        if (ddjvu_document_decoding_error(d->document) || ddjvu_document_get_pagenum(d->document) <= 0) throw std::runtime_error("Invalid DjVu document");
        return reinterpret_cast<jlong>(d.release());
    } catch (const std::exception &e) { error(env, e); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_org_readerka_importer_NativeDjvu_close(JNIEnv*, jclass, jlong handle) { delete reinterpret_cast<Document*>(handle); }
extern "C" JNIEXPORT jint JNICALL Java_org_readerka_importer_NativeDjvu_pageCount(JNIEnv *env, jclass, jlong handle) {
    try { return ddjvu_document_get_pagenum(get(handle).document); } catch (const std::exception &e) { error(env, e); return 0; }
}
extern "C" JNIEXPORT jintArray JNICALL Java_org_readerka_importer_NativeDjvu_pageSize(JNIEnv *env, jclass, jlong handle, jint index) {
    try {
        Document &d = get(handle); Page p(d, index);
        jint values[2] = {ddjvu_page_get_width(p.page), ddjvu_page_get_height(p.page)};
        if (values[0] <= 0 || values[1] <= 0) throw std::runtime_error("Invalid DjVu page dimensions");
        jintArray result = env->NewIntArray(2); env->SetIntArrayRegion(result, 0, 2, values); return result;
    } catch (const std::exception &e) { error(env, e); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_org_readerka_importer_NativeDjvu_render(JNIEnv *env, jclass, jlong handle, jint index, jobject bitmap) {
    try {
        Document &d = get(handle); Page p(d, index);
        AndroidBitmapInfo info;
        if (AndroidBitmap_getInfo(env, bitmap, &info) || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || !info.width || !info.height || uint64_t(info.width) * info.height > 4000000)
            throw std::runtime_error("Invalid or oversized DjVu bitmap");
        std::vector<char> rgb(size_t(info.width) * info.height * 3);
        ddjvu_rect_t rectangle = {0, 0, info.width, info.height};
        ddjvu_format_t *format = ddjvu_format_create(DDJVU_FORMAT_RGB24, 0, nullptr);
        ddjvu_format_set_row_order(format, 1); ddjvu_format_set_y_direction(format, 1);
        int ok = ddjvu_page_render(p.page, DDJVU_RENDER_COLOR, &rectangle, &rectangle, format, info.width * 3, rgb.data());
        ddjvu_format_release(format);
        if (!ok) throw std::runtime_error("Cannot render DjVu page");
        void *pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels)) throw std::runtime_error("Cannot lock bitmap");
        for (uint32_t y = 0; y < info.height; ++y) {
            auto *out = static_cast<unsigned char*>(pixels) + y * info.stride;
            const auto *in = reinterpret_cast<const unsigned char*>(rgb.data()) + y * info.width * 3;
            for (uint32_t x = 0; x < info.width; ++x) { out[x*4] = in[x*3]; out[x*4+1] = in[x*3+1]; out[x*4+2] = in[x*3+2]; out[x*4+3] = 255; }
        }
        AndroidBitmap_unlockPixels(env, bitmap);
    } catch (const std::exception &e) { error(env, e); }
}
static std::string quoted(const char *value) {
    std::string result = "\"";
    for (const unsigned char *p = reinterpret_cast<const unsigned char*>(value); *p; ++p) {
        if (*p == '"' || *p == '\\') { result += '\\'; result += char(*p); }
        else if (*p < 32) { const char hex[] = "0123456789abcdef"; result += "\\u00"; result += hex[*p >> 4]; result += hex[*p & 15]; }
        else result += char(*p);
    }
    return result + "\"";
}
static void words(miniexp_t zone, std::string &out, size_t &count, int depth) {
    if (!miniexp_listp(zone) || zone == miniexp_nil) return;
    if (depth > 32 || count > 100000 || out.size() > 16*1024*1024) throw std::runtime_error("DjVu OCR exceeds supported size");
    miniexp_t text = miniexp_nth(5, zone);
    if (miniexp_stringp(text)) {
        if (count++) out += ',';
        out += "{\"x0\":" + std::to_string(miniexp_to_int(miniexp_nth(1, zone))) + ",\"y0\":" + std::to_string(miniexp_to_int(miniexp_nth(2, zone)));
        out += ",\"x1\":" + std::to_string(miniexp_to_int(miniexp_nth(3, zone))) + ",\"y1\":" + std::to_string(miniexp_to_int(miniexp_nth(4, zone)));
        out += ",\"text\":" + quoted(miniexp_to_str(text)) + "}";
    } else {
        miniexp_t children = zone;
        for (int i = 0; i < 5; ++i) children = miniexp_cdr(children);
        while (children != miniexp_nil && miniexp_listp(children)) { words(miniexp_car(children), out, count, depth+1); children = miniexp_cdr(children); }
    }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_org_readerka_importer_NativeDjvu_text(JNIEnv *env, jclass, jlong handle, jint index) {
    try {
        Document &d = get(handle);
        ddjvu_pageinfo_t info;
        wait(d, [&] { return ddjvu_document_get_pageinfo(d.document, index, &info) >= DDJVU_JOB_OK; });
        miniexp_t expression = miniexp_dummy;
        wait(d, [&] { expression = ddjvu_document_get_pagetext(d.document, index, "word"); return expression != miniexp_dummy; });
        // The API page-info dimensions already include rotation; OCR coordinates do not.
        int width = (info.rotation & 1) ? info.height : info.width;
        int height = (info.rotation & 1) ? info.width : info.height;
        std::string result = "{\"width\":" + std::to_string(width) + ",\"height\":" + std::to_string(height) + ",\"rotation\":" + std::to_string(info.rotation) + ",\"words\":[";
        size_t count = 0; words(expression, result, count, 0); result += "]}";
        ddjvu_miniexp_release(d.document, expression);
        jbyteArray bytes = env->NewByteArray(result.size()); env->SetByteArrayRegion(bytes, 0, result.size(), reinterpret_cast<const jbyte*>(result.data())); return bytes;
    } catch (const std::exception &e) { error(env, e); return nullptr; }
}
