package com.smugview.app.data.cast

import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

class WebCompanionServer(
    private val getActiveMediaUrl: () -> String?,
    private val isVideoCheck: (String) -> Boolean
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    fun start(port: Int = 8080) {
        if (isRunning) return
        isRunning = true
        Thread {
            try {
                serverSocket = ServerSocket(port)
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    Thread {
                        handleConnection(socket)
                    }.start()
                }
            } catch (e: SocketException) {
                // Server stopped
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverSocket = null
    }

    private fun handleConnection(socket: Socket) {
        try {
            val reader = socket.getInputStream().bufferedReader()
            val writer = socket.getOutputStream()
            val requestLine = reader.readLine() ?: return
            
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val path = parts[1]

            if (path == "/") {
                serveHtml(writer)
            } else if (path == "/feed") {
                serveFeed(writer)
            } else {
                serveNotFound(writer)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                socket.close()
            } catch (ex: Exception) {}
        }
    }

    private fun serveHtml(os: OutputStream) {
        val html = """
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

        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n" +
                "Content-Length: ${html.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n" +
                html

        os.write(response.toByteArray())
        os.flush()
    }

    private fun serveFeed(os: OutputStream) {
        val url = getActiveMediaUrl() ?: ""
        val isVideo = if (url.isNotEmpty()) isVideoCheck(url) else false
        val json = """{"url":"$url","isVideo":$isVideo}"""
        
        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n" +
                "Content-Length: ${json.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n" +
                json

        os.write(response.toByteArray())
        os.flush()
    }

    private fun serveNotFound(os: OutputStream) {
        val response = "HTTP/1.1 404 Not Found\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n"
        os.write(response.toByteArray())
        os.flush()
    }
}
