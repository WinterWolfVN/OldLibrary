#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <sys/mman.h>
#include <memory>
#include <string>
#include <vector>
#include <cstring>
#include <android/api-level.h>

namespace art {

class OatDexFile;

class MemMap {
public:
    uint8_t* begin_;
    size_t size_;
    uint8_t* Begin() const {
        return begin_;
    }
    size_t Size() const {
        return size_;
    }
};

class DexFile {
public:
    typedef std::unique_ptr<const DexFile> (*OpenMemoryFn)(const uint8_t*, size_t, const std::string&, uint32_t, std::unique_ptr<MemMap>, const OatDexFile*, std::string*);
};

}

using namespace art;

DexFile::OpenMemoryFn ResolveOpenMemory() {
    static DexFile::OpenMemoryFn fn = reinterpret_cast<DexFile::OpenMemoryFn>(dlsym(RTLD_DEFAULT, "_ZN3art7DexFile11OpenMemoryEPKhjRKNSt3__112basic_stringIcNS3_11char_traitsIcEENS3_9allocatorIcEEEEjSt10unique_ptrINS_6MemMapESt14default_deleteISB_EEPKNS_10OatDexFileEPS8_"));
    return fn;
}

MemMap* ResolveMapAnonymous(const char* name, uint8_t* addr, size_t size, int prot, bool low4gb, bool reuse, std::string* error, bool useAshmem) {
    typedef MemMap* (*MapAnonymousFn)(const char*, uint8_t*, size_t, int, bool, bool, std::string*, bool);
    static MapAnonymousFn fn = reinterpret_cast<MapAnonymousFn>(dlsym(RTLD_DEFAULT, "_ZN3art6MemMap12MapAnonymousEPKcPhjibbPNSt3__112basic_stringIcNS4_11char_traitsIcEENS4_9allocatorIcEEEEb"));
    if (fn == nullptr) {
        if (error != nullptr) {
            *error = "MemMap::MapAnonymous not found";
        }
        return nullptr;
    }
    return fn(name, addr, size, prot, low4gb, reuse, error, useAshmem);
}

static void ThrowIOException(JNIEnv* env, const char* message) {
    jclass cls = env->FindClass("java/io/IOException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
        env->DeleteLocalRef(cls);
    }
}

static void ThrowIllegalArgument(JNIEnv* env, const char* message) {
    jclass cls = env->FindClass("java/lang/IllegalArgumentException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
        env->DeleteLocalRef(cls);
    }
}

static void ThrowNullPointer(JNIEnv* env, const char* message) {
    jclass cls = env->FindClass("java/lang/NullPointerException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
        env->DeleteLocalRef(cls);
    }
}

std::unique_ptr<MemMap> MakeDexMemMap(JNIEnv* env, jobject buffer, jint start, jint end, jboolean hasArray, jobject arrayObject, jint arrayOffset, std::string* error) {
    if (buffer == nullptr) {
        ThrowNullPointer(env, "buffer == null");
        return nullptr;
    }
    if (start < 0 || end <= start) {
        ThrowIllegalArgument(env, "Invalid buffer range");
        return nullptr;
    }
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    uint8_t* direct = reinterpret_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const bool isDirect = direct != nullptr && capacity >= 0;
    const size_t size = static_cast<size_t>(end - start);
    std::string mapError;
    MemMap* raw = ResolveMapAnonymous("InMemoryDexClassLoader", nullptr, size, PROT_READ | PROT_WRITE, false, false, &mapError, true);
    if (raw == nullptr) {
        if (error != nullptr)
            *error = mapError;
        return nullptr;
    }
    std::unique_ptr<MemMap> map(raw);

    // DirectByteBuffer
    if (isDirect) {
        if (static_cast<jlong>(end) > capacity) {
            ThrowIllegalArgument(env, "Buffer limit exceeds capacity");
            return nullptr;
        }
        std::memcpy(map->Begin(), direct + start, size);
        return map;
    }

    // Heap ByteBuffer
    if (!hasArray || arrayObject == nullptr) {
        ThrowIllegalArgument(env, "ByteBuffer has no accessible array");
        return nullptr;
    }
    jbyteArray array = reinterpret_cast<jbyteArray>(arrayObject);
    jsize arrayLength = env->GetArrayLength(array);
    jint sourceStart = arrayOffset + start;
    jint sourceEnd = arrayOffset + end;
    if (sourceStart < 0 || sourceEnd > arrayLength || sourceStart >= sourceEnd) {
        ThrowIllegalArgument(env, "Invalid ByteBuffer array range");
        return nullptr;
    }
    jbyte* bytes = env->GetByteArrayElements(array, nullptr);
    if (bytes == nullptr)
        return nullptr;
    std::memcpy(map->Begin(), bytes + sourceStart, size);
    env->ReleaseByteArrayElements(array, bytes, JNI_ABORT);
    return map;
}

jobject AllocateDexFileObject(JNIEnv* env, jclass dexFileClass, jfieldID cookieField, jfieldID internalCookieField, DexFile* nativeDexFile) {
    jobject object = env->AllocObject(dexFileClass);
    if (object == nullptr) {
        return nullptr;
    }
    jlongArray cookie = env->NewLongArray(2);
    jlong values[2];
    values[0] = 0;
    values[1] = static_cast<jlong>(reinterpret_cast<uintptr_t>(nativeDexFile));
    env->SetLongArrayRegion(cookie, 0, 2, values);
    env->SetObjectField(object, cookieField, cookie);
    env->SetObjectField(object, internalCookieField, cookie);
    env->DeleteLocalRef(cookie);
    return object;
}

static jobjectArray CreateDexFile(JNIEnv* env, jclass, jobjectArray buffers, jintArray positions, jintArray limits, jbooleanArray hasArrays, jobjectArray arrays, jintArray arrayOffsets) {
    if (buffers == nullptr || positions == nullptr || limits == nullptr || hasArrays == nullptr || arrays == nullptr || arrayOffsets == nullptr) {
        ThrowNullPointer(env, "Null argument");
        return nullptr;
    }
    const jsize count = env->GetArrayLength(buffers);
    DexFile::OpenMemoryFn OpenMemory = ResolveOpenMemory();
    jclass dexFileClass = env->FindClass("dalvik/system/DexFile");
    jfieldID cookieField = env->GetFieldID(dexFileClass, "mCookie", "Ljava/lang/Object;");
    jfieldID internalCookieField = env->GetFieldID(dexFileClass, "mInternalCookie", "Ljava/lang/Object;");
    if (cookieField == nullptr || internalCookieField == nullptr) {
        env->DeleteLocalRef(dexFileClass);
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(count, dexFileClass, nullptr);
    jint* positionPtr = env->GetIntArrayElements(positions, nullptr);
    jint* limitPtr = env->GetIntArrayElements(limits, nullptr);
    jboolean* hasArrayPtr = env->GetBooleanArrayElements(hasArrays, nullptr);
    jint* arrayOffsetPtr = env->GetIntArrayElements(arrayOffsets, nullptr);
    for (jsize i = 0; i < count; ++i) {
        jobject buffer = env->GetObjectArrayElement(buffers, i);
        jobject arrayObject = env->GetObjectArrayElement(arrays, i);
        std::string error;
        std::unique_ptr<MemMap> map = MakeDexMemMap(env, buffer, positionPtr[i], limitPtr[i], hasArrayPtr[i], arrayObject, arrayOffsetPtr[i], &error);
        env->DeleteLocalRef(buffer);
        if (arrayObject != nullptr)
            env->DeleteLocalRef(arrayObject);
        if (!map)
            goto cleanup;
        const uint8_t* base = map->Begin();
        const size_t size = map->Size();
        std::string location = "InMemoryDexClassLoader-" + std::to_string(static_cast<int>(i));
        std::unique_ptr<const DexFile> dex = OpenMemory(base, size, location, 0, std::move(map), nullptr, &error);
        if (!dex)
            goto cleanup;
        DexFile* nativeDexFile = const_cast<DexFile*>(dex.release());
        jobject javaDexFile = AllocateDexFileObject(env, dexFileClass, cookieField, internalCookieField, nativeDexFile);
        env->SetObjectArrayElement(result, i, javaDexFile);
        env->DeleteLocalRef(javaDexFile);
    }

cleanup:
    env->ReleaseIntArrayElements(positions, positionPtr, JNI_ABORT);
    env->ReleaseIntArrayElements(limits, limitPtr, JNI_ABORT);
    env->ReleaseBooleanArrayElements(hasArrays, hasArrayPtr, JNI_ABORT);
    env->ReleaseIntArrayElements(arrayOffsets, arrayOffsetPtr, JNI_ABORT);
    env->DeleteLocalRef(dexFileClass);
    return result;
}

static jobject NativeBridge(JNIEnv* env, jclass, jstring name, jobject loader) {
    if (name == nullptr) {
        return nullptr;
    }
    if (loader == nullptr) {
        return nullptr;
    }
    return nullptr;
}

static const JNINativeMethod gMethods[] = {
    {"CreateDexFile", "([Ljava/nio/ByteBuffer;[I[I[Z[Ljava/lang/Object;[I)[Ldalvik/system/DexFile;", reinterpret_cast<void*>(CreateDexFile)},
    {"NativeBridge", "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/Class;", reinterpret_cast<void*>(NativeBridge)}
};

extern "C" jint RegisterInMemoryDexClassLoader(JNIEnv* env) {
    if (env == nullptr) {
        return JNI_ERR;
    }
    if (android_get_device_api_level() >= 26) {
        return JNI_OK;
    }
    jclass clazz = env->FindClass("oldlib/dalvik/system/InMemoryDexClassLoader");
    if (clazz == nullptr) {
        return JNI_ERR;
    }
    const int result = env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0]));
    env->DeleteLocalRef(clazz);
    return result == JNI_OK ? JNI_OK : JNI_ERR;
}