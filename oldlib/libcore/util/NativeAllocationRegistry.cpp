#include <jni.h>
#include <stdint.h>

namespace {

using FreeFunction = void (*)(void*);

static void NativeAllocationRegistry_applyFreeFunction(JNIEnv*, jclass, jlong freeFunction, jlong nativePtr) {
    void* ptr = reinterpret_cast<void*>(static_cast<uintptr_t>(nativePtr));
    FreeFunction function = reinterpret_cast<FreeFunction>(static_cast<uintptr_t>(freeFunction));
    if (function != nullptr) {
        function(ptr);
    }
}

static const JNINativeMethod gMethods[] = {
    {
        "applyFreeFunction",
        "(JJ)V",
        reinterpret_cast<void*>(NativeAllocationRegistry_applyFreeFunction)
    }
};

/* Use if you have a file .so
 * static jint RegisterNativeAllocationRegistry(JNIEnv* env) {
 *   jclass clazz = env->FindClass("oldlib/libcore/util/NativeAllocationRegistry");
 *   if (clazz == nullptr) {
 *       return JNI_ERR;
 *   }
 *   const jint result = env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0]));
 *   env->DeleteLocalRef(clazz);
 *   return result;
 *  } 
 *
 *  Places on file has JNI_Load
 *  jint RegisterNativeAllocationRegistry(JNIEnv* env) 
 *  JNI_Load {
 *    RegisterNativeAllocationRegistry(env) 
 *  }
*/

} // namespace

extern "C"
JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm == nullptr) {
        return JNI_ERR;
    }
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    if (RegisterNativeAllocationRegistry(env) != JNI_OK) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}