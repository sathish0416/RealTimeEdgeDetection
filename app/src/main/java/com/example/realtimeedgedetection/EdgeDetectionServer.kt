package com.example.realtimeedgedetection

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import org.json.JSONObject

class EdgeDetectionServer(
    private val context: Context,
    port: Int = 8080
) : NanoHTTPD(port) {
    
    private val tag = "EdgeDetectionServer"
    private var latestImageBytes: ByteArray? = null
    private var latestImageTimestamp = 0L
    private var currentFps = 0
    private var currentMode = "Edge"
    
    init {
        Log.d(tag, "Server initialized on port $port")
    }
    
    fun updateLatestImage(imageBytes: ByteArray) {
        latestImageBytes = imageBytes
        latestImageTimestamp = System.currentTimeMillis()
    }
    
    fun updateStats(fps: Int, mode: String) {
        currentFps = fps
        currentMode = mode
    }
    
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        
        Log.d(tag, "Request received: $uri")
        
        return when {
            uri == "/" || uri == "/index.html" -> {
                serveIndexPage()
            }
            uri == "/api/latest-image" -> {
                serveLatestImage()
            }
            uri == "/api/stats" -> {
                serveStats()
            }
            uri.startsWith("/api/image/") -> {
                // Serve saved images from external files directory
                val filename = uri.substring("/api/image/".length)
                serveSavedImage(filename)
            }
            else -> {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        }
    }
    
    private fun serveIndexPage(): Response {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Real-Time Edge Detection - Live View</title>
                <style>
                    body { margin: 0; font-family: Inter, system-ui, Arial, sans-serif; background:#0b0b0b; color:#eaeaea; }
                    header { padding: 12px 16px; border-bottom: 1px solid #232323; }
                    .container { display:flex; flex-direction:column; gap:12px; padding:16px; align-items: center; }
                    .stage { position: relative; max-width: 900px; width: 100%; }
                    .stage img { width: 100%; height: auto; display:block; background:#111; border-radius: 8px; }
                    .overlay { position:absolute; left:12px; bottom:12px; background: rgba(0,0,0,0.7); padding:10px 12px; border-radius:8px; font-size:14px; }
                    .row { display:flex; gap:18px; }
                    .muted { color:#bdbdbd; }
                    .status { padding: 12px; background: #1a1a1a; border-radius: 8px; margin-top: 12px; }
                    .controls { display:flex; gap:8px; align-items:center; justify-content:center; margin-top: 12px; }
                    button { background:#2b7cff; color:white; border:none; padding:10px 16px; border-radius:8px; cursor:pointer; font-size: 14px; }
                    button:hover { background:#4a8fff; }
                </style>
            </head>
            <body>
                <header>
                    <strong>📱 Real-Time Edge Detection — Live View from Android</strong>
                </header>
                <div class="container">
                    <div class="stage">
                        <img id="edgeImage" alt="Live edge detection" src="/api/latest-image" />
                        <div class="overlay">
                            <div class="row">
                                <span id="fps">FPS: --</span>
                                <span id="mode" class="muted">Mode: --</span>
                            </div>
                        </div>
                    </div>
                    <div class="status">
                        <span id="status" class="muted">Connected to Android device</span>
                    </div>
                    <div class="controls">
                        <button id="refreshBtn">🔄 Refresh</button>
                    </div>
                </div>
                
                <script>
                    const img = document.getElementById('edgeImage');
                    const fpsEl = document.getElementById('fps');
                    const modeEl = document.getElementById('mode');
                    const statusEl = document.getElementById('status');
                    const refreshBtn = document.getElementById('refreshBtn');
                    
                    let updateInterval;
                    
                    function updateStats() {
                        fetch('/api/stats')
                            .then(res => res.json())
                            .then(data => {
                                fpsEl.textContent = 'FPS: ' + data.fps;
                                modeEl.textContent = 'Mode: ' + data.mode;
                                statusEl.textContent = 'Last update: ' + new Date(data.timestamp).toLocaleTimeString();
                            })
                            .catch(err => {
                                console.error('Failed to fetch stats:', err);
                            });
                    }
                    
                    function refreshImage() {
                        const timestamp = Date.now();
                        img.src = '/api/latest-image?t=' + timestamp;
                    }
                    
                    refreshBtn.addEventListener('click', refreshImage);
                    
                    // Auto-refresh image every 100ms for near real-time updates
                    updateInterval = setInterval(() => {
                        refreshImage();
                        updateStats();
                    }, 100);
                    
                    // Initial load
                    updateStats();
                </script>
            </body>
            </html>
        """.trimIndent()
        
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }
    
    private fun serveLatestImage(): Response {
        val imageBytes = latestImageBytes
        return if (imageBytes != null) {
            newFixedLengthResponse(Response.Status.OK, "image/png", imageBytes.inputStream(), imageBytes.size.toLong())
        } else {
            // Return a placeholder or the most recent saved image
            val fallbackFile = context.getExternalFilesDir(null)?.listFiles()
                ?.filter { it.name.endsWith(".png") }
                ?.maxByOrNull { it.lastModified() }
            
            if (fallbackFile != null) {
                try {
                    val fis = FileInputStream(fallbackFile)
                    newChunkedResponse(Response.Status.OK, "image/png", fis)
                } catch (e: Exception) {
                    Log.e(tag, "Failed to serve fallback image", e)
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No image available")
                }
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No image available yet")
            }
        }
    }
    
    private fun serveStats(): Response {
        val json = JSONObject().apply {
            put("fps", currentFps)
            put("mode", currentMode)
            put("timestamp", latestImageTimestamp)
        }
        
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }
    
    private fun serveSavedImage(filename: String): Response {
        val file = File(context.getExternalFilesDir(null), filename)
        return if (file.exists()) {
            try {
                val fis = FileInputStream(file)
                newChunkedResponse(Response.Status.OK, "image/png", fis)
            } catch (e: Exception) {
                Log.e(tag, "Failed to serve saved image: $filename", e)
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to read image")
            }
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Image not found")
        }
    }
}
