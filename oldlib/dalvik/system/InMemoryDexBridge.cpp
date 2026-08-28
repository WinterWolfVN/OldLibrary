#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stddef.h>
#include <string>
#include <memory>
#include <vector>
#include <mutex>
#include <unordered_map>

namespace {

struct DexFile {};
struct MemMap {};
struct OatDexFile {};

static jfieldID gDexFileCookieField = nullptr;
typedef std::unique_ptr<const DexFile> (*OpenMemoryFn)(const uint8_t*, size_t, const std::string&, uint32_t, MemMap*, const OatDexFile*, std::string*);
std::mutex gMutex;
std::unordered_map<const DexFile*, std::vector<uint8_t>> gBuffers;

OpenMemoryFn GetOpenMemory() {
    static OpenMemoryFn fn = nullptr;
    static bool initialized = false;

    if (initialized) {
        return fn;
    }
    initialized = true;
    void* handle = dlopen("/system/lib/libart.so", RTLD_NOW);
    if (handle == nullptr) {
        return nullptr;
    }
    fn = reinterpret_cast<OpenMemoryFn>(dlsym(handle, "_ZN3art7DexFile10OpenMemoryEPKhjRKNSt3__112basic_stringIcNS3_11char_traitsIcEENS3_9allocatorIcEEEEjPNS_6MemMapEPKNS_10OatDexFileEPS9_"));
    return fn;
}

jobject CreateDexFileObject(JNIEnv* env, jclass, jobject cookie) {
    if (cookie == nullptr) {
        jclass npe = env->FindClass("java/lang/NullPointerException");
        if (npe != nullptr) {
            env->ThrowNew(npe, "cookie == null");
        }
        return nullptr;
    }
    jclass dexFileClass = env->FindClass("dalvik/system/DexFile");
    if (dexFileClass == nullptr) {
        return nullptr;
    }
    if (gDexFileCookieField == nullptr) {
        gDexFileCookieField = env->GetFieldID(dexFileClass, "mCookie", "Ljava/lang/Object;");
        if (gDexFileCookieField == nullptr) {
            return nullptr;
        }
    }
    jobject dexFile = env->AllocObject(dexFileClass);
    if (dexFile == nullptr) {
        return nullptr;
    }
    env->SetObjectField(dexFile, gDexFileCookieField, cookie);
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(dexFile);
        return nullptr;
    }
    return dexFile;
}

jobject CreateCookie(JNIEnv* env, const DexFile* dexFile) {
    jlongArray cookie = env->NewLongArray(2);
    if (cookie == nullptr) {
        return nullptr;
    }
    jlong values[2];
    values[0] = 0;
    values[1] = static_cast<jlong>(reinterpret_cast<uintptr_t>(dexFile));
    env->SetLongArrayRegion(cookie, 0, 2, values);
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    return cookie;
}

jobject OpenMemory(JNIEnv* env, const uint8_t* data, size_t size) {
    OpenMemoryFn openMemory = GetOpenMemory();

    if (openMemory == nullptr) {
        jclass exceptionClass = env->FindClass("java/io/IOException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, "DexFile::OpenMemory not found");
        }
        return nullptr;
    }

    std::vector<uint8_t> buffer(data, data + size);
    std::string location = "InMemoryDex";
    std::string error;
    uint32_t checksum = 0;
    if (size >= 36) {
        checksum = static_cast<uint32_t>(buffer[8] | (buffer[9] << 8) | (buffer[10] << 16) | (buffer[11] << 24));
    }
    std::unique_ptr<const DexFile> dex = openMemory(buffer.data(), buffer.size(), location, checksum, nullptr, nullptr, &error);

    if (!dex) {
        jclass exceptionClass = env->FindClass("java/io/IOException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, error.empty() ? "OpenMemory failed" : error.c_str());
        }
        return nullptr;
    }

    const DexFile* dexFile = dex.release();
    {
        std::lock_guard<std::mutex> lock(gMutex);
        gBuffers.emplace(dexFile, std::move(buffer));
    }
    return CreateCookie(env, dexFile);
}

jobject CreateCookieWithArray(JNIEnv* env, jclass, jbyteArray array, jint start, jint end) {
    if (array == nullptr) {
        jclass exceptionClass = env->FindClass("java/lang/NullPointerException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, "buffer == null");
        }
        return nullptr;
    }
    jsize length = env->GetArrayLength(array);
    if (start < 0 || end < start || end > length) {
        jclass exceptionClass = env->FindClass("java/lang/IndexOutOfBoundsException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, "invalid buffer range");
        }
        return nullptr;
    }

    jsize size = end - start;
    jbyte* bytes = env->GetByteArrayElements(array, nullptr);
    if (bytes == nullptr) {
        return nullptr;
    }
    jobject result = OpenMemory(env, reinterpret_cast<const uint8_t*>(bytes + start), static_cast<size_t>(size));
    env->ReleaseByteArrayElements(array, bytes, JNI_ABORT);
    return result;
}

jobject CreateCookieWithDirectBuffer(JNIEnv* env, jclass, jobject buffer, jint start, jint end) {
    if (buffer == nullptr) {
        jclass exceptionClass = env->FindClass("java/lang/NullPointerException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, "buffer == null");
        }
        return nullptr;
    }
    void* address = env->GetDirectBufferAddress(buffer);
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (address == nullptr || capacity < 0 || start < 0 || end < start ||
        static_cast<jlong>(end) > capacity) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalArgumentException");
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, "invalid direct ByteBuffer");
        }
        return nullptr;
    }
    return OpenMemory(env, static_cast<const uint8_t*>(address) + start, static_cast<size_t>(end - start));
}

const JNINativeMethod gMethods[] = {
        {
        "createCookieWithArray", "([BII)Ljava/lang/Object;", reinterpret_cast<void*>(CreateCookieWithArray)
        },
        {
        "createCookieWithDirectBuffer", "(Ljava/nio/ByteBuffer;II)Ljava/lang/Object;", reinterpret_cast<void*>(CreateCookieWithDirectBuffer)
        }
        {
        "createDexFileObject", "(Ljava/lang/Object;)Ldalvik/system/DexFile;", reinterpret_cast<void*>(CreateDexFileObject)
        },
};

}

extern "C"
JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6
    ) != JNI_OK) {
        return JNI_ERR;
    }

    jclass clazz = env->FindClass("oldlib/dalvik/system/DexFile");
    if (clazz == nullptr) {
        return JNI_ERR;
    }
    if (env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0])) != JNI_OK) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}