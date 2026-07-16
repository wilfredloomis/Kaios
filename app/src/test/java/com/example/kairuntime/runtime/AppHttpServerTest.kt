package com.example.kairuntime.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlin.io.path.createTempDirectory

class AppHttpServerTest {
    @Test fun injectsRuntimeAndServesByteRanges() {
        val root = createTempDirectory("kai-http-").toFile()
        try {
            File(root, "index.html").writeText("<html><head></head><body>ok</body></html>")
            File(root, "media.bin").writeBytes(ByteArray(100) { it.toByte() })
            val port = ServerSocket(0).use { it.localPort }
            AppHttpServer(root, port, "window.__kaiRuntime = true".toByteArray()).use {
                Thread.sleep(50)
                val page = request(port, "GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    .toString(Charsets.ISO_8859_1)
                assertTrue(page.startsWith("HTTP/1.1 200"))
                assertTrue(page.contains("/__kai_runtime__/page-runtime.js"))

                val response = request(
                    port,
                    "GET /media.bin HTTP/1.1\r\nHost: localhost\r\nRange: bytes=10-19\r\n\r\n",
                )
                val separator = response.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n")
                val headers = response.copyOfRange(0, separator).toString(Charsets.ISO_8859_1)
                val body = response.copyOfRange(separator + 4, response.size)
                assertTrue(headers.startsWith("HTTP/1.1 206"))
                assertTrue(headers.contains("Content-Range: bytes 10-19/100"))
                assertArrayEquals(ByteArray(10) { (it + 10).toByte() }, body)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rejectsTraversalOutsideAppRoot() {
        val root = createTempDirectory("kai-http-").toFile()
        try {
            File(root, "index.html").writeText("ok")
            val port = ServerSocket(0).use { it.localPort }
            AppHttpServer(root, port, ByteArray(0)).use {
                Thread.sleep(50)
                val response = request(port, "GET /../secret HTTP/1.1\r\nHost: localhost\r\n\r\n")
                    .toString(Charsets.ISO_8859_1)
                assertTrue(response.startsWith("HTTP/1.1 404"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun request(port: Int, request: String): ByteArray = Socket("127.0.0.1", port).use { socket ->
        socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
        socket.getOutputStream().flush()
        socket.getInputStream().readBytes()
    }
}
