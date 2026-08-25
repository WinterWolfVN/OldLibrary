if you have JNI_onLoad in other file you need and place it after the namespace. 

#include <android/api-level.h>
bool RegisterInMemoryDexBridge(JNIEnv* env) {
    if (env == nullptr) {
        return false;
    }
    if (android_get_device_api_level() > 26) {
        return true;
    }
    jclass clazz = env->FindClass("oldlib/dalvik/system/DexFile");
    if (clazz == nullptr) {
        return false;
    }
    return env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0])) == JNI_OK;
}

else

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
