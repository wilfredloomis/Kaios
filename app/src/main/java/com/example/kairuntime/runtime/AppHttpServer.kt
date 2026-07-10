package com.example.kairuntime.runtime

import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

class AppHttpServer(private val root: File, port: Int, private val runtimeScript: ByteArray) : Closeable {
    private val server = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool()
    @Volatile private var running = true

    init {
        workers.execute {
            while (running) {
                try {
                    val socket = server.accept()
                    workers.execute { handle(socket) }
                } catch (_: Exception) {
                    if (running) continue
                }
            }
        }
    }

    private fun handle(socket: Socket) = socket.use { client ->
        client.soTimeout = 5_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
        val request = reader.readLine()?.split(' ') ?: return@use
        if (request.size < 2 || request[0] !in setOf("GET", "HEAD")) {
            respond(client, 405, "text/plain", "Method not allowed".toByteArray(), request.firstOrNull() == "HEAD")
            return@use
        }
        while (!reader.readLine().isNullOrEmpty()) Unit

        val rawPath = request[1].substringBefore('?').substringBefore('#')
        val decoded = runCatching { URLDecoder.decode(rawPath, "UTF-8") }.getOrElse {
            respond(client, 400, "text/plain", "Bad request".toByteArray(), request[0] == "HEAD")
            return@use
        }
        val relative = decoded.removePrefix("/").ifBlank { "index.html" }
        if (relative == RUNTIME_SCRIPT_PATH) {
            respond(client, 200, "text/javascript; charset=utf-8", runtimeScript, request[0] == "HEAD")
            return@use
        }
        val file = File(root, relative).canonicalFile
        val safeRoot = root.canonicalFile
        if (!file.path.startsWith(safeRoot.path + File.separator) || !file.isFile) {
            respond(client, 404, "text/plain", "Not found".toByteArray(), request[0] == "HEAD")
            return@use
        }
        val body = if (file.extension.equals("html", true) || file.extension.equals("htm", true)) {
            injectRuntime(file.readText(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        } else {
            file.readBytes()
        }
        respond(client, 200, mimeType(file), body, request[0] == "HEAD")
    }

    private fun injectRuntime(html: String): String {
        val tag = "<script src=\"/$RUNTIME_SCRIPT_PATH\"></script>"
        val head = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(html)
        return if (head != null) html.replaceRange(head.range.last + 1, head.range.last + 1, tag)
        else tag + html
    }

    private fun respond(socket: Socket, status: Int, type: String, body: ByteArray, headOnly: Boolean) {
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; else -> "Method Not Allowed" }
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-cache\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        if (!headOnly) output.write(body)
        output.flush()
    }

    private fun mimeType(file: File): String = when (file.extension.lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "json", "webapp" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        "mp4" -> "video/mp4"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    override fun close() {
        running = false
        server.close()
        workers.shutdownNow()
    }

    companion object {
        private const val RUNTIME_SCRIPT_PATH = "__kai_runtime__/page-runtime.js"
    }
}
