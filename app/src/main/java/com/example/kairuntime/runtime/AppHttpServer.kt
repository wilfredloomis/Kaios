package com.example.kairuntime.runtime

import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.Executors

class AppHttpServer(private val root: File, port: Int, private val runtimeScript: ByteArray) : Closeable {
    private val server = ServerSocket(port, 32, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool()
    private val safeRoot = root.canonicalFile
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
        client.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine()?.split(' ') ?: return@use
        val method = requestLine.firstOrNull().orEmpty()
        if (requestLine.size < 2 || method !in ALLOWED_METHODS) {
            respondBytes(client, 405, "text/plain; charset=utf-8", "Method not allowed".toByteArray(), method == "HEAD")
            return@use
        }
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) headers[line.substring(0, separator).trim().lowercase(Locale.ROOT)] = line.substring(separator + 1).trim()
        }

        val rawPath = requestLine[1].substringBefore('?').substringBefore('#')
        val decoded = runCatching {
            // URLDecoder treats '+' as a form-space, which is incorrect for URL paths.
            URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
        }.getOrElse {
            respondBytes(client, 400, "text/plain; charset=utf-8", "Bad request".toByteArray(), method == "HEAD")
            return@use
        }
        val relative = decoded.removePrefix("/").ifBlank { "index.html" }
        if (relative == RUNTIME_SCRIPT_PATH) {
            respondBytes(client, 200, "text/javascript; charset=utf-8", runtimeScript, method == "HEAD")
            return@use
        }

        val file = File(safeRoot, relative).canonicalFile
        if (!file.path.startsWith(safeRoot.path + File.separator) || !file.isFile) {
            respondBytes(client, 404, "text/plain; charset=utf-8", "Not found".toByteArray(), method == "HEAD")
            return@use
        }

        if (isHtml(file)) {
            val body = injectRuntime(file.readText(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
            respondBytes(client, 200, mimeType(file), body, method == "HEAD")
            return@use
        }

        respondFile(client, file, headers["range"], method == "HEAD")
    }

    private fun respondFile(socket: Socket, file: File, rangeHeader: String?, headOnly: Boolean) {
        val length = file.length()
        val range = parseRange(rangeHeader, length)
        if (rangeHeader != null && range == null) {
            val output = socket.getOutputStream()
            writeHeaders(output, 416, mimeType(file), 0, mapOf("Content-Range" to "bytes */$length"))
            output.flush()
            return
        }

        val start = range?.first ?: 0L
        val end = range?.last ?: (length - 1L).coerceAtLeast(-1L)
        val bodyLength = if (length == 0L) 0L else end - start + 1L
        val extra = linkedMapOf("Accept-Ranges" to "bytes")
        if (range != null) extra["Content-Range"] = "bytes $start-$end/$length"
        val output = socket.getOutputStream()
        writeHeaders(output, if (range == null) 200 else 206, mimeType(file), bodyLength, extra)
        if (!headOnly && bodyLength > 0L) {
            FileInputStream(file).use { input ->
                var skipped = 0L
                while (skipped < start) {
                    val count = input.skip(start - skipped)
                    if (count <= 0L) break
                    skipped += count
                }
                val buffer = ByteArray(64 * 1024)
                var remaining = bodyLength
                while (remaining > 0L) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
        }
        output.flush()
    }

    private fun parseRange(header: String?, length: Long): LongRange? {
        if (header.isNullOrBlank() || length <= 0L) return null
        val match = RANGE_REGEX.matchEntire(header.trim()) ?: return null
        val first = match.groupValues[1]
        val second = match.groupValues[2]
        val start: Long
        val end: Long
        if (first.isBlank()) {
            val suffixLength = second.toLongOrNull()?.coerceAtMost(length) ?: return null
            if (suffixLength <= 0L) return null
            start = length - suffixLength
            end = length - 1L
        } else {
            start = first.toLongOrNull() ?: return null
            end = if (second.isBlank()) length - 1L else second.toLongOrNull() ?: return null
        }
        if (start < 0L || start >= length || end < start) return null
        return start..minOf(end, length - 1L)
    }

    private fun injectRuntime(html: String): String {
        val tag = "<script src=\"/$RUNTIME_SCRIPT_PATH\"></script>"
        if (html.contains(RUNTIME_SCRIPT_PATH)) return html
        val head = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(html)
        return if (head != null) html.replaceRange(head.range.last + 1, head.range.last + 1, tag) else tag + html
    }

    private fun respondBytes(socket: Socket, status: Int, type: String, body: ByteArray, headOnly: Boolean) {
        val output = socket.getOutputStream()
        writeHeaders(output, status, type, body.size.toLong())
        if (!headOnly) output.write(body)
        output.flush()
    }

    private fun writeHeaders(output: OutputStream, status: Int, type: String, length: Long, extra: Map<String, String> = emptyMap()) {
        val reason = when (status) {
            200 -> "OK"
            206 -> "Partial Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            416 -> "Range Not Satisfiable"
            else -> "Error"
        }
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: $length\r\n")
            append("Cache-Control: no-cache\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Cross-Origin-Resource-Policy: cross-origin\r\n")
            extra.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(Charsets.ISO_8859_1))
    }

    private fun isHtml(file: File) = file.extension.equals("html", true) || file.extension.equals("htm", true)

    private fun mimeType(file: File): String = when (file.extension.lowercase(Locale.ROOT)) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "webapp" -> "application/x-web-app-manifest+json; charset=utf-8"
        "webmanifest" -> "application/manifest+json; charset=utf-8"
        "xml" -> "application/xml; charset=utf-8"
        "txt" -> "text/plain; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "bmp" -> "image/bmp"
        "mp3" -> "audio/mpeg"
        "ogg", "oga" -> "audio/ogg"
        "wav" -> "audio/wav"
        "m4a", "aac" -> "audio/mp4"
        "flac" -> "audio/flac"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "ogv" -> "video/ogg"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "wasm" -> "application/wasm"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    override fun close() {
        running = false
        runCatching { server.close() }
        workers.shutdownNow()
    }

    companion object {
        private const val RUNTIME_SCRIPT_PATH = "__kai_runtime__/page-runtime.js"
        private val ALLOWED_METHODS = setOf("GET", "HEAD")
        private val RANGE_REGEX = Regex("bytes=(\\d*)-(\\d*)", RegexOption.IGNORE_CASE)
    }
}
