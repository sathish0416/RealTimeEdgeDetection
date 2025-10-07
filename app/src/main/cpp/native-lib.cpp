#include <jni.h>

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_realtimeedgedetection_MainActivity_nativeAdd(
        JNIEnv* /*env*/, jobject /*this*/, jint a, jint b) {
    return a + b;
}
