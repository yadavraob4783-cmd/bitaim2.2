#include <jni.h>
#include <string>
#include <android/log.h>
#include <unistd.h>
#include <stdio.h>
#include <dlfcn.h>
#include <inttypes.h>

#define LOG_TAG "AimAssistNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// The target library to find in memory
const char* TARGET_LIB = "libgame-CARROM-GooglePlay-Gold-Release-Module.so";

uintptr_t get_base_address(const char* lib_name) {
    FILE *fp;
    char line[512];
    uintptr_t base_address = 0;

    // Use /proc/self/maps if running inside the target process (Root/VirtualSpace injection)
    // For external memory reading, we would need to read /proc/<pid>/maps instead.
    fp = fopen("/proc/self/maps", "r");
    if (fp == NULL) {
        LOGE("Failed to open /proc/self/maps");
        return 0;
    }

    while (fgets(line, sizeof(line), fp)) {
        if (strstr(line, lib_name)) {
            sscanf(line, "%" PRIxPTR "-", &base_address);
            LOGI("Found %s at %" PRIxPTR, lib_name, base_address);
            break;
        }
    }

    fclose(fp);
    return base_address;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bitaim_carromaim_cv_MemoryEngine_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Native Hook Engine Initialized";
    return env->NewStringUTF(hello.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_bitaim_carromaim_cv_MemoryEngine_findTargetBaseAddress(
        JNIEnv* env,
        jobject /* this */) {
    return (jlong) get_base_address(TARGET_LIB);
}
