package com.example.kairuntime.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HttpAssetsTest {
    @get:Rule val temp = TemporaryFolder()

    // --- MIME typing -----------------------------------------------------

    @Test fun scriptsAreTypedAsJavaScript() {
        // Regression: mistyped scripts are blocked by GeckoView under nosniff.
        assertEquals("text/javascript; charset=utf-8", HttpAssets.mimeTypeForExtension("js"))
        assertEquals("text/javascript; charset=utf-8", HttpAssets.mimeTypeForExtension("mjs"))
        assertEquals("text/javascript; charset=utf-8", HttpAssets.mimeTypeForExtension("cjs"))
    }

    @Test fun stylesheetsAndDataFormatsAreTypedCorrectly() {
        assertEquals("text/css; charset=utf-8", HttpAssets.mimeTypeForExtension("css"))
        assertEquals("application/json; charset=utf-8", HttpAssets.mimeTypeForExtension("json"))
        assertEquals("application/json; charset=utf-8", HttpAssets.mimeTypeForExtension("webapp"))
        assertEquals("application/wasm", HttpAssets.mimeTypeForExtension("wasm"))
    }

    @Test fun mediaAndFontTypesAreKnown() {
        assertEquals("audio/mpeg", HttpAssets.mimeTypeForExtension("mp3"))
        assertEquals("video/mp4", HttpAssets.mimeTypeForExtension("mp4"))
        assertEquals("font/woff2", HttpAssets.mimeTypeForExtension("woff2"))
        assertEquals("image/svg+xml", HttpAssets.mimeTypeForExtension("SVG"))
    }

    @Test fun unknownExtensionFallsBackToOctetStream() {
        assertEquals("application/octet-stream", HttpAssets.mimeTypeForExtension("xyz"))
        assertEquals("application/octet-stream", HttpAssets.mimeTypeForExtension(""))
    }

    // --- Range parsing ---------------------------------------------------

    @Test fun parsesExplicitRange() {
        assertEquals(0L to 99L, HttpAssets.parseRange("bytes=0-99", 1000))
        assertEquals(100L to 199L, HttpAssets.parseRange("bytes=100-199", 1000))
    }

    @Test fun openEndedRangeClampsToLength() {
        assertEquals(500L to 999L, HttpAssets.parseRange("bytes=500-", 1000))
        assertEquals(0L to 999L, HttpAssets.parseRange("bytes=0-5000", 1000))
    }

    @Test fun suffixRangeReturnsTail() {
        assertEquals(900L to 999L, HttpAssets.parseRange("bytes=-100", 1000))
        assertEquals(0L to 999L, HttpAssets.parseRange("bytes=-5000", 1000))
    }

    @Test fun rejectsUnsatisfiableOrMalformedRanges() {
        assertNull(HttpAssets.parseRange("bytes=1000-1001", 1000))
        assertNull(HttpAssets.parseRange("bytes=abc-def", 1000))
        assertNull(HttpAssets.parseRange("bytes=0-10,20-30", 1000))
        assertNull(HttpAssets.parseRange("items=0-10", 1000))
        assertNull(HttpAssets.parseRange("bytes=-0", 1000))
        assertNull(HttpAssets.parseRange("bytes=50-10", 1000))
        assertNull(HttpAssets.parseRange("bytes=0-99", 0))
    }

    // --- Path resolution -------------------------------------------------

    @Test fun resolvesFileWithinRoot() {
        val root = temp.newFolder("app")
        File(root, "app.js").writeText("console.log(1)")
        val resolved = HttpAssets.resolveFile(root, "app.js")
        assertEquals(File(root, "app.js").canonicalPath, resolved?.canonicalPath)
    }

    @Test fun directoryRequestFallsBackToIndex() {
        val root = temp.newFolder("app")
        val sub = File(root, "settings").apply { mkdirs() }
        File(sub, "index.html").writeText("<html></html>")
        val resolved = HttpAssets.resolveFile(root, "settings")
        assertEquals(File(sub, "index.html").canonicalPath, resolved?.canonicalPath)
    }

    @Test fun blankPathIsNotAutoResolved() {
        // The server normalizes blank paths to index.html before calling resolveFile.
        val root = temp.newFolder("app")
        File(root, "index.html").writeText("<html></html>")
        val resolved = HttpAssets.resolveFile(root, "index.html")
        assertTrue(resolved != null && resolved.isFile)
    }

    @Test fun traversalOutsideRootIsRejected() {
        val root = temp.newFolder("app")
        File(root.parentFile, "secret.txt").writeText("secret")
        assertNull(HttpAssets.resolveFile(root, "../secret.txt"))
    }

    @Test fun missingFileReturnsNull() {
        val root = temp.newFolder("app")
        assertNull(HttpAssets.resolveFile(root, "nope.js"))
    }

    // --- Runtime injection ----------------------------------------------

    @Test fun injectsRuntimeScriptAfterHead() {
        val html = "<html><head><title>x</title></head><body></body></html>"
        val out = HttpAssets.injectRuntime(html)
        assertTrue(out.contains("<script src=\"/${HttpAssets.RUNTIME_SCRIPT_PATH}\"></script>"))
        assertTrue(out.indexOf("<script") > out.indexOf("<head"))
    }

    @Test fun injectsRuntimeScriptWhenNoHead() {
        val html = "<body>hi</body>"
        val out = HttpAssets.injectRuntime(html)
        assertTrue(out.startsWith("<script src=\"/${HttpAssets.RUNTIME_SCRIPT_PATH}\">"))
    }
}
