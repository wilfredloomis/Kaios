package com.example.kairuntime.installer

import android.content.Context
import android.net.Uri
import com.example.kairuntime.database.AppDatabase
import com.example.kairuntime.database.InstalledKaiApp
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

            val manifestFile = File(staging, MANIFEST_NAME)
            require(manifestFile.isFile) { "manifest.webapp must be at the ZIP root" }
            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            val name = manifest.optString("name").trim()
            require(name.isNotEmpty()) { "Manifest name is required" }

            val launchPath = PackageSafety.normalizeManifestPath(manifest.optString("launch_path", "/index.html"))
            require(File(staging, launchPath).isFile) { "launch_path does not point to a file" }
            val iconPath = selectIcon(manifest)?.let(PackageSafety::normalizeManifestPath)?.takeIf {
                File(staging, it).isFile
            }
            val permissions = manifest.optJSONObject("permissions") ?: JSONObject()

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
                appType = "packaged",
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
            val relative = PackageSafety.normalizeZipEntry(entry.name.trimEnd('/'))
            PackageSafety.rejectNativeBinary(relative)
            val output = File(root, relative).canonicalFile
            require(output.path.startsWith(root.path + File.separator)) { "Unsafe ZIP path" }
            if (entry.isDirectory) {
                require(output.mkdirs() || output.isDirectory) { "Could not create directory" }
            } else {
                val parent = requireNotNull(output.parentFile)
                require(parent.mkdirs() || parent.isDirectory) { "Could not create directory" }
                FileOutputStream(output).use { stream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        require(totalBytes <= MAX_EXTRACTED_BYTES) { "Package exceeds 50 MB" }
                        stream.write(buffer, 0, count)
                    }
                }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }

    private fun selectIcon(manifest: JSONObject): String? {
        val icons = manifest.optJSONObject("icons") ?: return null
        return icons.keys().asSequence()
            .mapNotNull { size -> size.toIntOrNull()?.let { it to icons.optString(size) } }
            .filter { it.second.isNotBlank() }
            .maxByOrNull { it.first }
            ?.second
    }

    private fun allocatePort(): Int = ServerSocket(0).use { it.localPort }

    companion object {
        const val MANIFEST_NAME = "manifest.webapp"
        const val MAX_ENTRIES = 2_000
        const val MAX_EXTRACTED_BYTES = 50L * 1024 * 1024
    }
}
