package com.example.kairuntime.runtime

import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Loopback-only static file server for a single installed KaiOS package.
 *
 * The server intentionally exposes only files below [root]. It injects the runtime
 * bridge script into HTML documents and serves a synthetic script at
 * [HttpAssets.RUNTIME_SCRIPT_PATH]. Correct MIME typing matters because GeckoView
 * enforces `X-Content-Type-Options: nosniff`: a script served as `text/plain` is
 * refused, which is the failure that broke real KaiOS apps (e.g. `common/js/cache.js`).
 *
 * Byte-range requests are supported so audio/video seeking works, and directory
 * requests fall back to `index.html`.
 */
class AppHttpServer(private val root: File, port: Int, private val runtimeScript: ByteArray) : Closeable {
    private val server = ServerSocket(port, 32, InetAddress.getByName("127.0.0.1"))
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
        client.soTimeout = 15_000
        client.tcpNoDelay = true
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
        val requestLine = reader.readLine() ?: return@use
        if (requestLine.length > MAX_REQUEST_LINE) {
            respond(client, 414, "text/plain; charset=utf-8", "URI too long".toByteArray(), false)
            return@use
        }
        val request = requestLine.split(' ')
        val method = request.firstOrNull()?.uppercase().orEmpty()
        val headOnly = method == "HEAD"

        // Read request headers (retaining Range) with a bound to avoid abuse.
        var rangeHeader: String? = null
        var headerCount = 0
        while (true) {
            val line = reader.readLine()
            if (line.isNullOrEmpty()) break
            if (++headerCount > MAX_HEADERS) break
            if (line.length >= 6 && line.regionMatches(0, "Range:", 0, 6, ignoreCase = true)) {
                rangeHeader = line.substring(6).trim()
            }
        }

        if (method == "OPTIONS") {
            respondOptions(client)
            return@use
        }
        if (method != "GET" && method != "HEAD") {
            respond(client, 405, "text/plain; charset=utf-8", "Method not allowed".toByteArray(), false)
            return@use
        }
        if (request.size < 2) {
            respond(client, 400, "text/plain; charset=utf-8", "Bad request".toByteArray(), headOnly)
            return@use
        }

        val rawPath = request[1].substringBefore('?').substringBefore('#')
        val decoded = runCatching { URLDecoder.decode(rawPath, "UTF-8") }.getOrElse {
            respond(client, 400, "text/plain; charset=utf-8", "Bad request".toByteArray(), headOnly)
            return@use
        }
        val relative = decoded.removePrefix("/").ifBlank { "index.html" }
        if (relative == HttpAssets.RUNTIME_SCRIPT_PATH) {
            respond(client, 200, "text/javascript; charset=utf-8", runtimeScript, headOnly)
            return@use
        }

        val resolved = HttpAssets.resolveFile(root, relative)
        if (resolved == null) {
            respond(client, 404, "text/plain; charset=utf-8", "Not found".toByteArray(), headOnly)
            return@use
        }

        // HTML documents are rewritten to inject the runtime bridge; they never
        // participate in range requests because their length changes on the fly.
        if (HttpAssets.isHtml(resolved)) {
            val body = HttpAssets.injectRuntime(resolved.readText(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
            respond(client, 200, HttpAssets.mimeType(resolved), body, headOnly)
            return@use
        }

        serveFile(client, resolved, rangeHeader, headOnly)
    }

    private fun serveFile(client: Socket, file: File, rangeHeader: String?, headOnly: Boolean) {
        val length = file.length()
        val type = HttpAssets.mimeType(file)
        val range = rangeHeader?.let { HttpAssets.parseRange(it, length) }
        if (range != null) {
            val (start, end) = range
            val count = end - start + 1
            val extraHeaders = "Content-Range: bytes $start-$end/$length\r\nAccept-Ranges: bytes\r\n"
            writeHeaders(client, 206, type, count, extraHeaders)
            if (!headOnly) {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(start)
                    streamBytes(client, raf, count)
                }
            }
        } else {
            writeHeaders(client, 200, type, length, "Accept-Ranges: bytes\r\n")
            if (!headOnly) {
                RandomAccessFile(file, "r").use { raf -> streamBytes(client, raf, length) }
            }
        }
        client.getOutputStream().flush()
    }

    private fun streamBytes(client: Socket, raf: RandomAccessFile, count: Long) {
        val output = client.getOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = count
        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            val read = raf.read(buffer, 0, toRead)
            if (read < 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun respondOptions(client: Socket) {
        val output = client.getOutputStream()
        output.write(
            ("HTTP/1.1 204 No Content\r\n" +
                "Allow: GET, HEAD, OPTIONS\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
        )
        output.flush()
    }

    private fun respond(socket: Socket, status: Int, type: String, body: ByteArray, headOnly: Boolean) {
        writeHeaders(socket, status, type, body.size.toLong(), "")
        val output = socket.getOutputStream()
        if (!headOnly) output.write(body)
        output.flush()
    }

    private fun writeHeaders(socket: Socket, status: Int, type: String, contentLength: Long, extraHeaders: String) {
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n")
            append("Content-Type: ").append(type).append("\r\n")
            append("Content-Length: ").append(contentLength).append("\r\n")
            append("Cache-Control: no-cache\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            if (extraHeaders.isNotEmpty()) append(extraHeaders)
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().write(header.toByteArray(Charsets.US_ASCII))
    }

    private fun reasonPhrase(status: Int): String = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        414 -> "URI Too Long"
        else -> "Error"
    }

    override fun close() {
        running = false
        server.close()
        workers.shutdownNow()
    }

    companion object {
        private const val MAX_REQUEST_LINE = 8_192
        private const val MAX_HEADERS = 100
    }
}
