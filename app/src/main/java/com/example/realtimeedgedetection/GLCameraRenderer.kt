package com.example.realtimeedgedetection

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface

private const val VERTEX_SHADER = """
attribute vec4 aPosition;
attribute vec4 aTexCoord;
varying vec2 vTexCoord;
uniform mat4 uMVP;
uniform mat4 uTexMatrix;
void main() {
    gl_Position = uMVP * aPosition;
    vTexCoord = (uTexMatrix * aTexCoord).xy;
}
"""

private const val FRAGMENT_SHADER = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTexCoord;
uniform samplerExternalOES uTexture;
uniform vec2 uTexelSize; // 1/width, 1/height

// Simple Sobel edge on luminance
float luminance(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

void main() {
    vec2 t = uTexelSize;
    vec3 c00 = texture2D(uTexture, vTexCoord + vec2(-t.x, -t.y)).rgb;
    vec3 c10 = texture2D(uTexture, vTexCoord + vec2( 0.0, -t.y)).rgb;
    vec3 c20 = texture2D(uTexture, vTexCoord + vec2( t.x, -t.y)).rgb;
    vec3 c01 = texture2D(uTexture, vTexCoord + vec2(-t.x,  0.0)).rgb;
    vec3 c11 = texture2D(uTexture, vTexCoord).rgb;
    vec3 c21 = texture2D(uTexture, vTexCoord + vec2( t.x,  0.0)).rgb;
    vec3 c02 = texture2D(uTexture, vTexCoord + vec2(-t.x,  t.y)).rgb;
    vec3 c12 = texture2D(uTexture, vTexCoord + vec2( 0.0,  t.y)).rgb;
    vec3 c22 = texture2D(uTexture, vTexCoord + vec2( t.x,  t.y)).rgb;

    float l00=luminance(c00), l10=luminance(c10), l20=luminance(c20);
    float l01=luminance(c01), l11=luminance(c11), l21=luminance(c21);
    float l02=luminance(c02), l12=luminance(c12), l22=luminance(c22);

    float gx = -l00 - 2.0*l01 - l02 + l20 + 2.0*l21 + l22;
    float gy = -l00 - 2.0*l10 - l20 + l02 + 2.0*l12 + l22;

    float g = clamp(length(vec2(gx, gy)), 0.0, 1.0);
    gl_FragColor = vec4(vec3(g), 1.0);
}
"""

class GLCameraRenderer(
    private val onSurfaceReady: (surface: Surface, width: Int, height: Int) -> Unit,
    private val desiredWidth: Int = 1280,
    private val desiredHeight: Int = 720,
    private val onPngReady: ((pngBytes: ByteArray) -> Unit)? = null,
) : GLSurfaceView.Renderer {

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uMVP = 0
    private var uTexMatrix = 0
    private var uTexture = 0
    private var uTexelSize = 0

    private val mvpMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)

    private var oesTexId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    private var viewWidth = 1
    private var viewHeight = 1
    @Volatile private var captureNextFrame = false

    override fun onSurfaceCreated(unused: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMVP = GLES20.glGetUniformLocation(program, "uMVP")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        uTexelSize = GLES20.glGetUniformLocation(program, "uTexelSize")

        Matrix.setIdentityM(mvpMatrix, 0)
        Matrix.setIdentityM(texMatrix, 0)

        oesTexId = createOESTexture()
        surfaceTexture = SurfaceTexture(oesTexId).apply {
            setDefaultBufferSize(desiredWidth, desiredHeight)
        }
        surface = Surface(surfaceTexture)
        // Notify activity to attach camera output to this Surface
        onSurfaceReady.invoke(surface!!, desiredWidth, desiredHeight)

        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    override fun onSurfaceChanged(unused: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(unused: javax.microedition.khronos.opengles.GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        surfaceTexture?.updateTexImage()
        surfaceTexture?.getTransformMatrix(texMatrix)
        // Apply vertical flip (to fix upside-down) only; keep horizontal as-is to avoid mirror on back camera
        val flipV = FloatArray(16)
        Matrix.setIdentityM(flipV, 0)
        // translate(0,1,0) * scale(1,-1,1)
        Matrix.translateM(flipV, 0, 0f, 1f, 0f)
        Matrix.scaleM(flipV, 0, 1f, -1f, 1f)
        val tmp = FloatArray(16)
        Matrix.multiplyMM(tmp, 0, texMatrix, 0, flipV, 0)
        System.arraycopy(tmp, 0, texMatrix, 0, 16)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform2f(uTexelSize, 1.0f / desiredWidth.toFloat(), 1.0f / desiredHeight.toFloat())

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(0x8D65 /* GL_TEXTURE_EXTERNAL_OES */, oesTexId)
        GLES20.glUniform1i(uTexture, 0)

        // Fullscreen quad
        val verts = floatArrayOf(
            -1f, -1f, 0f,   0f, 1f,
             1f, -1f, 0f,   1f, 1f,
            -1f,  1f, 0f,   0f, 0f,
             1f,  1f, 0f,   1f, 0f
        )
        drawQuad(verts)

        if (captureNextFrame) {
            try {
                val png = readCurrentFrameToPNG()
                onPngReady?.invoke(png)
            } catch (_: Exception) {
                // ignore; best-effort capture
            } finally {
                captureNextFrame = false
            }
        }
    }

    fun requestCapture() {
        captureNextFrame = true
    }

    fun release() {
        try { surface?.release() } catch (_: Exception) {}
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surface = null
        surfaceTexture = null
    }

    private fun drawQuad(verts: FloatArray) {
        val bb = java.nio.ByteBuffer.allocateDirect(verts.size * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        bb.put(verts).position(0)
        val stride = 5 * 4

        bb.position(0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, stride, bb)

        bb.position(3)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, stride, bb)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    private fun createOESTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        val id = tex[0]
        GLES20.glBindTexture(0x8D65 /* GL_TEXTURE_EXTERNAL_OES */, id)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id
    }

    private fun readCurrentFrameToPNG(): ByteArray {
        val w = viewWidth
        val h = viewHeight
        val buf = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        buf.rewind()

        // Convert to Bitmap and vertically flip (GL origin is bottom-left)
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buf)
        val m = android.graphics.Matrix().apply { preScale(1f, -1f); postTranslate(0f, h.toFloat()) }
        val flipped = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, w, h, m, false)

        val baos = java.io.ByteArrayOutputStream()
        flipped.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
        val bytes = baos.toByteArray()
        try { baos.close() } catch (_: Exception) {}
        bitmap.recycle()
        if (flipped != bitmap) flipped.recycle()
        return bytes
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile error: $log")
        }
        return shader
    }

    private fun createProgram(vs: String, fs: String): Int {
        val v = compileShader(GLES20.GL_VERTEX_SHADER, vs)
        val f = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, v)
        GLES20.glAttachShader(prog, f)
        GLES20.glBindAttribLocation(prog, 0, "aPosition")
        GLES20.glBindAttribLocation(prog, 1, "aTexCoord")
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("Program link error: $log")
        }
        return prog
    }
}
