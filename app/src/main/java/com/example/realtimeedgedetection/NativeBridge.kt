package com.example.realtimeedgedetection

object NativeBridge {
    init {
        System.loadLibrary("native-lib")
    }
    external fun processFrameNV21ToPNG(nv21: ByteArray, width: Int, height: Int): ByteArray
}
