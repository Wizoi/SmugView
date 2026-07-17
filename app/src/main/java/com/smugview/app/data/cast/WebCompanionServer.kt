package com.smugview.app.data.cast

import android.util.Log
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A tiny HTTP server that mirrors the currently-cast photo/video to a device that cannot
 * receive a normal cast stream (e.g. an Amazon Echo Show's built-in browser).
 *
 * SECURITY: this serves the URLs of what may be *private* SmugMug galleries over cleartext
 * HTTP on the local network. To limit exposure it:
 *   - binds and accepts only from an explicit allow-listed client IP (the cast target) when one
 *     is supplied; all other LAN peers get 403,
 *   - applies a read timeout so idle/hostile sockets can't pin handler threads (Slowloris),
 *   - uses a small bounded thread pool instead of an unbounded thread-per-connection,
 *   - sends `Cache-Control: no-store` so intermediaries/the browser don't retain the URLs.
 * It is still only appropriate on a trusted network; document that for users.
 */
class WebCompanionServer(
    private val getActiveMediaUrl: () -> String?,
    private val isVideoCheck: (String) -> Boolean
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workers = Executors.newFixedThreadPool(4)
    /** When non-empty, only this client IP may connect. */
    @Volatile private var allowedClientIp: String = ""

    @Synchronized
    fun start(port: Int = 8080, allowedClientIp: String = "") {
        if (running.get()) return
        this.allowedClientIp = allowedClientIp
        val socket = try {
            ServerSocket(port)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind Web Companion server on port $port", e)
            return
        }
        serverSocket = socket
        if (workers.isShutdown) workers = Executors.newFixedThreadPool(4)
        running.set(true)
        acceptThread = Thread {
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (e: SocketException) {
                    break // server stopped
                } catch (e: Exception) {
                    Log.w(TAG, "accept() failed", e)
                    break
                }
                try {
                    workers.execute { handleConnection(client) }
                } catch (e: Exception) {
                    // Pool saturated or shutting down — drop the connection rather than block.
                    closeQuietly(client)
                }
            }
        }.apply { name = "web-companion-accept"; start() }
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing server socket", e)
        }
        serverSocket = null
        workers.shutdownNow()
        try {
            workers.awaitTermination(1, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            // Reject any client that is not the allow-listed cast target.
            val allowed = allowedClientIp
            if (allowed.isNotEmpty() && socket.inetAddress?.hostAddress != allowed) {
                serveForbidden(socket.getOutputStream())
                return
            }
            socket.soTimeout = READ_TIMEOUT_MS
            val reader = socket.getInputStream().bufferedReader()
            val writer = socket.getOutputStream()
            val requestLine = reader.readLine() ?: return

            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val path = parts[1]

            when (path) {
                "/" -> serveHtml(writer)
                "/feed" -> serveFeed(writer)
                else -> serveNotFound(writer)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling companion connection", e)
        } finally {
            closeQuietly(socket)
        }
    }

    private fun serveHtml(os: OutputStream) {
        writeResponse(os, "200 OK", "text/html; charset=UTF-8", HTML)
    }

    private fun serveFeed(os: OutputStream) {
        val url = getActiveMediaUrl() ?: ""
        val isVideo = if (url.isNotEmpty()) isVideoCheck(url) else false
        val json = """{"url":${jsonString(url)},"isVideo":$isVideo}"""
        writeResponse(os, "200 OK", "application/json; charset=UTF-8", json)
    }

    private fun serveNotFound(os: OutputStream) {
        writeResponse(os, "404 Not Found", "text/plain; charset=UTF-8", "")
    }

    private fun serveForbidden(os: OutputStream) {
        writeResponse(os, "403 Forbidden", "text/plain; charset=UTF-8", "")
    }

    private fun writeResponse(os: OutputStream, status: String, contentType: String, body: String) {
        val bytes = body.toByteArray()
        val response = "HTTP/1.1 $status\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
        os.write(response.toByteArray())
        os.write(bytes)
        os.flush()
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (e: Exception) {
            // ignore
        }
    }

    /** Minimal JSON string escaping so odd characters in URLs can't break the client parse. */
    private fun jsonString(value: String): String {
        val sb = StringBuilder("\"")
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }

    companion object {
        private const val TAG = "WebCompanionServer"
        private const val READ_TIMEOUT_MS = 5000

        private val HTML = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>SmugView Companion</title>
                <style>
                    body { margin: 0; background: black; display: flex; justify-content: center; align-items: center; height: 100vh; overflow: hidden; }
                    img, video { max-width: 100%; max-height: 100%; object-fit: contain; }
                </style>
            </head>
            <body>
                <div id="media-container"></div>
                <script>
                    let currentUrl = '';
                    function poll() {
                        fetch('/feed')
                            .then(r => r.json())
                            .then(data => {
                                if (data.url !== currentUrl) {
                                    currentUrl = data.url;
                                    const container = document.getElementById('media-container');
                                    container.innerHTML = '';
                                    if (currentUrl) {
                                        if (data.isVideo) {
                                            const video = document.createElement('video');
                                            video.src = currentUrl;
                                            video.autoplay = true;
                                            video.controls = true;
                                            video.loop = true;
                                            container.appendChild(video);
                                        } else {
                                            const img = document.createElement('img');
                                            img.src = currentUrl;
                                            container.appendChild(img);
                                        }
                                    }
                                }
                                setTimeout(poll, 1000);
                            })
                            .catch(() => setTimeout(poll, 2000));
                    }
                    poll();
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}
