package com.example.kairuntime.installer

internal object PackageSafety {
    private val nativeExtensions = setOf("so", "dex", "elf", "exe", "dll", "bin")

    fun normalizeZipEntry(rawPath: String): String {
        require(!rawPath.startsWith('/') && !rawPath.startsWith('\\')) { "Absolute paths are not allowed" }
        return normalize(rawPath)
    }

    fun normalizeManifestPath(rawPath: String): String = normalize(rawPath.removePrefix("/").removePrefix("./"))

    fun rejectNativeBinary(path: String) {
        val extension = path.substringAfterLast('.', "").lowercase()
        require(extension !in nativeExtensions) { "Native executable files are not supported" }
    }

    private fun normalize(rawPath: String): String {
        val path = rawPath.replace('\\', '/')
        require(path.isNotBlank() && '\u0000' !in path) { "Invalid empty path" }
        val parts = path.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) { "Unsafe package path" }
        require(":" !in parts.first()) { "Absolute paths are not allowed" }
        return parts.joinToString("/")
    }
}
