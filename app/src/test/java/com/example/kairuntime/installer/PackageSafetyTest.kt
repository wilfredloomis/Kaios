package com.example.kairuntime.installer

import org.junit.Assert.assertEquals
import org.junit.Test

class PackageSafetyTest {
    @Test fun acceptsNormalPackagePaths() {
        assertEquals("assets/app.js", PackageSafety.normalizeZipEntry("assets/app.js"))
        assertEquals("index.html", PackageSafety.normalizeManifestPath("/index.html"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsParentTraversal() {
        PackageSafety.normalizeZipEntry("assets/../../secret")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAbsoluteZipPath() {
        PackageSafety.normalizeZipEntry("/etc/passwd")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWindowsTraversal() {
        PackageSafety.normalizeZipEntry("assets\\..\\secret")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNativeLibrary() {
        PackageSafety.rejectNativeBinary("lib/arm64-v8a/payload.so")
    }
}
