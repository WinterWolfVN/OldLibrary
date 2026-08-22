#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stddef.h>
#include <string>
#include <vector>
#include <mutex>
#include <unordered_map>
#include <sys/mman.h>

#define TAG "InMemoryDexBridge"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct DexMemory {
    void* address;
    size_t size;
};

std::mutex gMutex;
std::unordered_map<uintptr_t, DexMemory> gMemory;

// API-25 ART 7.x exported C++ symbol.
// ARM64 uses size_t == unsigned long.
static const char* kOpenMemoryArm64 = "_ZN3art7DexFile10OpenMemoryEPKhmRKNSt3__112basic_stringIcNS3_11char_traitsIcEENS3_9allocatorIcEEEEjPNS_6MemMapEPKNS_10OatDexFileEPS9_";

// ART's return object is std::unique_ptr<const DexFile>.
// On arm64 its returned pointer is in x0, so the raw-pointer ABI can
// be consumed directly here.
using OpenMemoryArm64 = const void* (*)
    (const uint8_t*,
     size_t,
     const std::string&,
     uint32_t,
     void*,
     const void*,
     std::string*);

static void throwIOException(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/io/IOException");
    if (cls != nullptr) {
        env->ThrowNew(cls, msg);
    }
}

static jobject makeCookie(JNIEnv* env, const void* dexFile) {
    // API 25 cookie layout:
    // [0] = OatFile*
    // [1...] = DexFile*
    //
    // ART's ConvertJavaArrayToDexFiles() reads exactly this layout.
    jlongArray cookie = env->NewLongArray(2);
    if (cookie == nullptr) {
        return nullptr;
    }
    jlong values[2];
    values[0] = 0;
    values[1] = static_cast<jlong>(
        reinterpret_cast<uintptr_t>(dexFile));
    env->SetLongArrayRegion(cookie, 0, 2, values);
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    return cookie;
}

static const void* openMemory(const uint8_t* base, size_t size, const char* location) {
    void* libart = dlopen("libart.so", RTLD_NOW);
    if (libart == nullptr) {
        LOGE("dlopen(libart.so): %s", dlerror());
        return nullptr;
    }
    void* symbol = dlsym(libart, kOpenMemoryArm64);
    if (symbol == nullptr) {
        LOGE("DexFile::OpenMemory symbol not found: %s", dlerror());
        dlclose(libart);
        return nullptr;
    }
    OpenMemoryArm64 fn = reinterpret_cast<OpenMemoryArm64>(symbol);
    std::string error;
    // We deliberately pass nullptr for MemMap/OatDexFile because the
    // API-25 overload accepts an optionally-backed memory mapping.
    //
    // The mapping is kept separately by this bridge so the bytes remain
    // alive for the lifetime of the DexFile.
    DexFile* dexFile = fn(base, size, std::string(location ? location : ""), 0, nullptr, nullptr, &error);
    if (dexFile == nullptr) {
        LOGE("DexFile::OpenMemory failed: %s", error.c_str());
    }    
    return dexFile;
}

static jobject openBytes(JNIEnv* env, const uint8_t* source, size_t size) {
    if (size == 0) {
        throwIOException(env, "empty dex buffer");
        return nullptr;
    }
    if ((reinterpret_cast<uintptr_t>(source) & 3u) != 0) {
        throwIOException(env, "DEX buffer is not 4-byte aligned");
        return nullptr;
    }

    // Make an app-owned mapping so the source Java byte[]/ByteBuffer does
    // not have to remain pinned after JNI returns.
    void* mapping = mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mapping == MAP_FAILED) {
        throwIOException(env, "mmap failed");
        return nullptr;
    }
    memcpy(mapping, source, size);
    const void* dexFile = openMemory(static_cast<const uint8_t*>(mapping), size, "InMemoryDex");
    if (dexFile == nullptr) {
        munmap(mapping, size);
        throwIOException(env, "DexFile::OpenMemory failed");
        return nullptr;
    }
    jobject cookie = makeCookie(env, dexFile);
    if (cookie == nullptr) {
        delete reinterpret_cast<DexFile*>(const_cast<void*>(dexFile));
        munmap(mapping, size);
        return nullptr;
    }    

    {
        std::lock_guard<std::mutex> lock(gMutex);
        gMemory[reinterpret_cast<uintptr_t>(dexFile)] = { mapping, size };
    }
    return cookie;
}

} // namespace

static jclass gDexFileClass = nullptr;
static bool InitDexFileClass(JNIEnv* env, jobject loader) {
    jclass cl = env->FindClass("java/lang/ClassLoader");
    if (cl == nullptr) {
        return false;
    }
    jmethodID loadClass = env->GetMethodID(
        cl,
        "loadClass",
        "(Ljava/lang/String;)Ljava/lang/Class;");

    if (loadClass == nullptr) {
        return false;
    }
    jstring name = env->NewStringUTF("oldlib.dalvik.system.DexFile");
    jobject result = env->CallObjectMethod(loader, loadClass, name);
    env->DeleteLocalRef(name);
    if (env->ExceptionCheck() || result == nullptr) {
        return false;
    }

    gDexFileClass = reinterpret_cast<jclass>(env->NewGlobalRef(result));
    env->DeleteLocalRef(result);
    return gDexFileClass != nullptr;
}

extern "C"
JNIEXPORT jobject JNICALL
Java_oldlib_dalvik_system_DexFile_createCookieWithDirectBuffer(JNIEnv* env, jclass, jobject buffer, jint start, jint end) {
    uint8_t* address = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (address == nullptr || capacity < 0) {
        throwIOException(env, "ByteBuffer is not a direct buffer");
        return nullptr;
    }
    if (start < 0 || end < start || static_cast<jlong>(end) > capacity) {
        throwIOException(env, "invalid ByteBuffer range");
        return nullptr;
    }
    return openBytes(env, address + start, static_cast<size_t>(end - start));
}

extern "C"
JNIEXPORT jobject JNICALL
Java_oldlib_dalvik_system_DexFile_createCookieWithArray(JNIEnv* env, jclass, jbyteArray array, jint start, jint end) {
    if (array == nullptr) {
        throwIOException(env, "byte array == null");
        return nullptr;
    }
    jsize length = env->GetArrayLength(array);
    if (start < 0 || end < start || end > length) {
        throwIOException(env, "invalid byte array range");
        return nullptr;
    }
    const size_t size = static_cast<size_t>(end - start);
    std::vector<uint8_t> temp(size);
    env->GetByteArrayRegion(array, start, end - start, reinterpret_cast<jbyte*>(temp.data()));
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    return openBytes(env, temp.data(), size);
}
