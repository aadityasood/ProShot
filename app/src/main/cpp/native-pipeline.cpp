#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "ProShotNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C"
JNIEXPORT jstring JNICALL
Java_com_proshot_app_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}

/**
 * Native bridge for the image processing pipeline.
 * All JNI functions follow the Google C++ Style Guide and the project naming convention.
 */

// TODO: Add alignment and merging JNI stubs in future tasks.
