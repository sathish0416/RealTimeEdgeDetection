package com.example.realtimeedgedetection

import android.media.Image

object YuvUtils {
    // Convert YUV_420_888 to NV21 byte array
    fun toNV21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val yBase = yBuffer.position()
        val yLimit = yBuffer.limit()

        var pos = 0
        // Copy Y using absolute indexed reads to respect row/pixel stride
        var row = 0
        while (row < height) {
            // If tightly packed and pixel stride is 1, we can bulk copy the row
            val rowStart = yBase + row * yRowStride
            if (yPixelStride == 1 && rowStart + width <= yLimit) {
                // Use a temporary slice without changing the original buffer's position
                val tmp = ByteArray(width)
                val oldPos = yBuffer.position()
                try {
                    yBuffer.position(rowStart)
                    yBuffer.get(tmp, 0, width)
                } finally {
                    yBuffer.position(oldPos)
                }
                System.arraycopy(tmp, 0, nv21, pos, width)
                pos += width
            } else {
                var col = 0
                while (col < width) {
                    val yIndex = rowStart + col * yPixelStride
                    if (yIndex >= yLimit) break
                    nv21[pos++] = yBuffer.get(yIndex)
                    col++
                }
                // If the row was shorter due to limit, pad remaining with last valid
                while (pos < row * width + (row + 1) * width && pos < ySize) {
                    nv21[pos] = nv21[pos - 1]
                    pos++
                }
            }
            row++
        }

        // Copy VU (NV21 expects V then U interleaved)
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vBase = vBuffer.position()
        val vLimit = vBuffer.limit()
        val uBase = uBuffer.position()
        val uLimit = uBuffer.limit()

        val rowCount = height / 2
        val colCount = width / 2

        row = 0
        while (row < rowCount) {
            var col = 0
            val vRowStart = vBase + row * vRowStride
            val uRowStart = uBase + row * uRowStride
            while (col < colCount) {
                val vIndex = vRowStart + col * vPixelStride
                val uIndex = uRowStart + col * uPixelStride
                if (vIndex >= vLimit || uIndex >= uLimit) break
                nv21[pos++] = vBuffer.get(vIndex)
                nv21[pos++] = uBuffer.get(uIndex)
                col++
            }
            row++
        }
        return nv21
    }
}
