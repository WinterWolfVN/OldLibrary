if you have JNI_onLoad in other file you need 

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
