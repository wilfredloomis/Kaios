package com.example.kairuntime.installer

import android.content.Context
import android.net.Uri
import com.example.kairuntime.database.AppDatabase
import com.example.kairuntime.database.InstalledKaiApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.util.UUID
import java.util.zip.ZipInputStream

class KaiPackageInstaller(private val context: Context) {
    fun install(uri: Uri): InstalledKaiApp {
        val id = UUID.randomUUID().toString()
        val appsRoot = File(context.filesDir, "apps").apply { mkdirs() }
        val staging = File(appsRoot, ".staging-$id")
        check(staging.mkdir()) { "Could not create installation directory" }

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip -> extract(zip, staging) }
            } ?: error("The selected package could not be opened")

            flattenSingleTopLevelDirectory(staging)
            val sourceManifest = findManifest(staging)
            val manifest = JSONObject(sourceManifest.readText(Charsets.UTF_8))
            val isPwaManifest = sourceManifest.name != MANIFEST_NAME
            val name = manifest.optString("name").ifBlank { manifest.optString("short_name") }.trim()
            require(name.isNotEmpty()) { "Manifest name is required" }

            val rawLaunchPath = if (isPwaManifest) {
                manifest.optString("start_url", "/index.html").substringBefore('?').substringBefore('#')
            } else {
                manifest.optString("launch_path", "/index.html")
            }
            val launchPath = PackageSafety.normalizeManifestPath(rawLaunchPath)
            require(File(staging, launchPath).isFile) { "launch/start path does not point to a file: $launchPath" }
            val iconPath = selectIcon(manifest)?.let(PackageSafety::normalizeManifestPath)?.takeIf {
                File(staging, it).isFile
            }
            val permissions = manifest.optJSONObject("permissions") ?: JSONObject()

            // The compatibility runtime and legacy applications always expect this canonical path.
            val canonicalManifest = File(staging, MANIFEST_NAME)
            if (sourceManifest != canonicalManifest) {
                val normalized = JSONObject(manifest.toString())
                    .put("name", name)
                    .put("launch_path", "/$launchPath")
                canonicalManifest.writeText(normalized.toString(), Charsets.UTF_8)
            }

            val installation = File(appsRoot, id)
            check(staging.renameTo(installation)) { "Could not finalize installation" }
            val app = InstalledKaiApp(
                id = id,
                name = name,
                version = manifest.optString("version").takeIf { it.isNotBlank() },
                launchPath = launchPath,
                manifestPath = File(installation, MANIFEST_NAME).absolutePath,
                iconPath = iconPath?.let { File(installation, it).absolutePath },
                installationPath = installation.absolutePath,
                appType = if (isPwaManifest) "pwa" else manifest.optString("type", "packaged").ifBlank { "packaged" },
                permissionsJson = permissions.toString(),
                port = allocatePort(),
            )
            try {
                AppDatabase(context).use { it.insert(app) }
            } catch (error: Throwable) {
                installation.deleteRecursively()
                throw error
            }
            return app
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun extract(zip: ZipInputStream, destination: File) {
        val root = destination.canonicalFile
        var totalBytes = 0L
        var entries = 0
        var entry = zip.nextEntry
        while (entry != null) {
            entries++
            require(entries <= MAX_ENTRIES) { "Package contains too many files" }
            val trimmedName = entry.name.trimEnd('/')
            if (trimmedName.isNotBlank()) {
                val relative = PackageSafety.normalizeZipEntry(trimmedName)
                PackageSafety.rejectNativeBinary(relative)
                val output = File(root, relative).canonicalFile
                require(output.path.startsWith(root.path + File.separator)) { "Unsafe ZIP path" }
                if (entry.isDirectory) {
                    require(output.mkdirs() || output.isDirectory) { "Could not create directory" }
                } else {
                    val parent = requireNotNull(output.parentFile)
                    require(parent.mkdirs() || parent.isDirectory) { "Could not create directory" }
                    var entryBytes = 0L
                    FileOutputStream(output).use { stream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            entryBytes += count
                            totalBytes += count
                            require(entryBytes <= MAX_SINGLE_FILE_BYTES) { "A package file exceeds 100 MB" }
                            require(totalBytes <= MAX_EXTRACTED_BYTES) { "Package exceeds 250 MB" }
                            stream.write(buffer, 0, count)
                        }
                    }
                }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }

    private fun flattenSingleTopLevelDirectory(staging: File) {
        if (MANIFEST_CANDIDATES.any { File(staging, it).isFile }) return
        val children = staging.listFiles().orEmpty().filterNot { it.name.startsWith("__MACOSX") }
        if (children.size != 1 || !children.single().isDirectory) return
        val nested = children.single()
        if (MANIFEST_CANDIDATES.none { File(nested, it).isFile }) return
        nested.listFiles().orEmpty().forEach { child ->
            val target = File(staging, child.name)
            check(child.renameTo(target)) { "Could not normalize package root" }
        }
        nested.delete()
    }

    private fun findManifest(root: File): File {
        return MANIFEST_CANDIDATES.asSequence()
            .map { File(root, it) }
            .firstOrNull(File::isFile)
            ?: error("Package must contain manifest.webapp, manifest.webmanifest, or manifest.json at its root")
    }

    private fun selectIcon(manifest: JSONObject): String? {
        val icons = manifest.opt("icons") ?: return null
        return when (icons) {
            is JSONObject -> icons.keys().asSequence()
                .mapNotNull { size -> size.toIntOrNull()?.let { it to icons.optString(size) } }
                .filter { it.second.isNotBlank() }
                .maxByOrNull { it.first }
                ?.second
            is JSONArray -> (0 until icons.length()).asSequence()
                .mapNotNull { icons.optJSONObject(it) }
                .mapNotNull { icon ->
                    val src = icon.optString("src").takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val largest = Regex("(\\d+)x(\\d+)").findAll(icon.optString("sizes"))
                        .mapNotNull { match -> match.groupValues.getOrNull(1)?.toIntOrNull() }
                        .maxOrNull() ?: 0
                    largest to src
                }
                .maxByOrNull { it.first }
                ?.second
            else -> null
        }
    }

    private fun allocatePort(): Int {
        val usedPorts = AppDatabase(context).use { database -> database.getAll().mapTo(mutableSetOf()) { it.port } }
        repeat(64) {
            val candidate = ServerSocket(0).use { it.localPort }
            if (candidate !in usedPorts) return candidate
        }
        error("Could not allocate a unique localhost port")
    }

    companion object {
        const val MANIFEST_NAME = "manifest.webapp"
        private val MANIFEST_CANDIDATES = listOf(MANIFEST_NAME, "manifest.webmanifest", "manifest.json")
        const val MAX_ENTRIES = 10_000
        const val MAX_EXTRACTED_BYTES = 250L * 1024 * 1024
        const val MAX_SINGLE_FILE_BYTES = 100L * 1024 * 1024
    }
}
