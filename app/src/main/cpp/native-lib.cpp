#include <jni.h>
#include <vector>
#include <string>

// OpenCV
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/imgcodecs.hpp>

using namespace cv;

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_realtimeedgedetection_MainActivity_nativeAdd(
        JNIEnv* /*env*/, jobject /*this*/, jint a, jint b) {
    return a + b;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_example_realtimeedgedetection_NativeBridge_processFrameNV21ToPNG(
        JNIEnv* env,
        jobject /*this*/,
        jbyteArray nv21Data,
        jint width,
        jint height) {
    // Get input bytes
    const jsize len = env->GetArrayLength(nv21Data);
    std::vector<uchar> nv21(len);
    env->GetByteArrayRegion(nv21Data, 0, len, reinterpret_cast<jbyte*>(nv21.data()));

    // Create YUV NV21 Mat (height + height/2) x width, single channel
    Mat yuv(height + height / 2, width, CV_8UC1, nv21.data());

    // Convert to grayscale from YUV NV21 directly
    Mat gray;
    cvtColor(yuv, gray, COLOR_YUV2GRAY_NV21);

    // Apply Canny
    Mat edges;
    const double lowThresh = 50.0;
    const double highThresh = 150.0;
    Canny(gray, edges, lowThresh, highThresh);

    // Optionally convert to 3-channel for nicer PNG (white edges on black)
    Mat edgesColor;
    cvtColor(edges, edgesColor, COLOR_GRAY2BGR);

    // Encode as PNG
    std::vector<uchar> pngBuf;
    imencode(".png", edgesColor, pngBuf);

    // Return as jbyteArray
    jbyteArray out = env->NewByteArray(static_cast<jsize>(pngBuf.size()));
    if (out == nullptr) return nullptr;
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(pngBuf.size()), reinterpret_cast<const jbyte*>(pngBuf.data()));
    return out;
}
