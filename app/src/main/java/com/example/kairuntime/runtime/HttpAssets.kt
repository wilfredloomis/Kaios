package com.example.kairuntime.runtime

import java.io.File

/**
 * Pure, side-effect-free helpers behind [AppHttpServer]. Keeping MIME typing,
 * byte-range parsing, and path resolution here makes the correctness-critical
 * logic unit-testable on the JVM without opening a socket.
 */
internal object HttpAssets {
    const val RUNTIME_SCRIPT_PATH = "__kai_runtime__/page-runtime.js"
    val DIRECTORY_INDEX_FILES = listOf("index.html", "index.htm")

    /**
     * Content-Type table covering the asset kinds KaiOS packages ship. Correct
     * types are required because GeckoView refuses mistyped scripts/styles under
     * `X-Content-Type-Options: nosniff`; a script mislabelled `text/plain` (the
     * failure seen with `common/js/cache.js`) is silently blocked.
     */
    val MIME_TYPES: Map<String, String> = mapOf(
        // Documents
        "html" to "text/html; charset=utf-8",
        "htm" to "text/html; charset=utf-8",
        "xhtml" to "application/xhtml+xml; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "js" to "text/javascript; charset=utf-8",
        "mjs" to "text/javascript; charset=utf-8",
        "cjs" to "text/javascript; charset=utf-8",
        "json" to "application/json; charset=utf-8",
        "map" to "application/json; charset=utf-8",
        "webapp" to "application/json; charset=utf-8",
        "webmanifest" to "application/manifest+json; charset=utf-8",
        "txt" to "text/plain; charset=utf-8",
        "xml" to "application/xml; charset=utf-8",
        "csv" to "text/csv; charset=utf-8",
        "wasm" to "application/wasm",
        // Images
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "ico" to "image/x-icon",
        "avif" to "image/avif",
        "apng" to "image/apng",
        // Audio
        "mp3" to "audio/mpeg",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/ogg",
        "wav" to "audio/wav",
        "weba" to "audio/webm",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "flac" to "audio/flac",
        // Video
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "webm" to "video/webm",
        "ogv" to "video/ogg",
        "3gp" to "video/3gpp",
        // Fonts
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "eot" to "application/vnd.ms-fontobject",
        // Misc
        "pdf" to "application/pdf",
        "zip" to "application/zip",
    )

    fun mimeType(file: File): String = mimeTypeForExtension(file.extension)

    fun mimeTypeForExtension(extension: String): String =
        MIME_TYPES[extension.lowercase()] ?: "application/octet-stream"

    fun isHtml(file: File): Boolean =
        file.extension.equals("html", true) || file.extension.equals("htm", true)

    /**
     * Maps a request path to a real file below [root], applying directory index
     * fallback (`/dir/` -> `/dir/index.html`) that KaiOS apps rely on. Returns
     * null when the target escapes the root or does not resolve to a file.
     */
    fun resolveFile(root: File, relative: String): File? {
        val safeRoot = root.canonicalFile
        val candidate = File(root, relative).canonicalFile
        val insideRoot = candidate.path == safeRoot.path ||
            candidate.path.startsWith(safeRoot.path + File.separator)
        if (!insideRoot) return null
        if (candidate.isFile) return candidate
        if (candidate.isDirectory) {
            for (index in DIRECTORY_INDEX_FILES) {
                val indexFile = File(candidate, index)
                if (indexFile.isFile) return indexFile
            }
        }
        return null
    }

    /**
     * Parses a single-range `bytes=start-end` header against a resource of the
     * given [length]. Returns an inclusive start/end pair, or null when the range
     * is unsatisfiable, malformed, or specifies multiple ranges.
     */
    fun parseRange(header: String, length: Long): Pair<Long, Long>? {
        if (length <= 0) return null
        val spec = header.substringAfter("bytes=", "").trim()
        if (spec.isEmpty() || spec.contains(',')) return null
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()
        return try {
            when {
                startText.isEmpty() -> {
                    val suffix = endText.toLong()
                    if (suffix <= 0) return null
                    val start = maxOf(0L, length - suffix)
                    start to (length - 1)
                }
                endText.isEmpty() -> {
                    val start = startText.toLong()
                    if (start < 0 || start >= length) return null
                    start to (length - 1)
                }
                else -> {
                    val start = startText.toLong()
                    val end = minOf(endText.toLong(), length - 1)
                    if (start < 0 || start > end || start >= length) return null
                    start to end
                }
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun injectRuntime(html: String): String {
        val tag = "<script src=\"/$RUNTIME_SCRIPT_PATH\"></script>"
        val head = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(html)
        return if (head != null) html.replaceRange(head.range.last + 1, head.range.last + 1, tag)
        else tag + html
    }
}
