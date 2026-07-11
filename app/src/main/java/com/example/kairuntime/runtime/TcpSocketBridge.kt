package com.example.kairuntime.runtime

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocketFactory

/**
 * Native TCP/TLS socket backend for the Firefox OS / KaiOS `navigator.mozTCPSocket`
 * polyfill. Real messaging apps (Telegram ports, chat clients) open raw sockets
 * instead of relying on XHR; without this bridge they crash on
 * `navigator.mozTCPSocket is undefined`.
 *
 * Sockets are confined to public internet hosts. Private / loopback targets are
 * rejected so a compromised package cannot scan the LAN or the host device.
 */
class TcpSocketBridge(
    private val emit: (JSONObject) -> Unit,
) {
    private val workers = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap<Int, ManagedSocket>()
    private val nextId = AtomicInteger(1)

    fun open(host: String, port: Int, useSecureTransport: Boolean, binaryType: String): Int {
        require(port in 1..65535) { "Invalid port" }
        require(isPublicHost(host)) { "TCP target is not a public host" }
        val id = nextId.getAndIncrement()
        val managed = ManagedSocket(id, host, port, useSecureTransport, binaryType == "arraybuffer")
        sockets[id] = managed
        workers.execute {
            try {
                val socket = if (useSecureTransport) {
                    SSLSocketFactory.getDefault().createSocket()
                } else {
                    Socket()
                }
                socket.tcpNoDelay = true
                socket.soTimeout = 0
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                managed.socket = socket
                managed.input = BufferedInputStream(socket.getInputStream())
                managed.output = BufferedOutputStream(socket.getOutputStream())
                emitEvent(id, "open")
                readLoop(managed)
            } catch (error: Exception) {
                emitEvent(id, "error", JSONObject().put("message", error.message ?: "connect failed"))
                closeInternal(id, notify = true)
            }
        }
        return id
    }

    fun send(id: Int, dataBase64: String?, text: String?) {
        val managed = sockets[id] ?: error("Socket $id is not open")
        val bytes = when {
            dataBase64 != null -> android.util.Base64.decode(dataBase64, android.util.Base64.DEFAULT)
            text != null -> text.toByteArray(Charsets.UTF_8)
            else -> ByteArray(0)
        }
        workers.execute {
            try {
                val output = managed.output ?: error("Socket is not connected")
                output.write(bytes)
                output.flush()
            } catch (error: Exception) {
                emitEvent(id, "error", JSONObject().put("message", error.message ?: "send failed"))
                closeInternal(id, notify = true)
            }
        }
    }

    fun close(id: Int) {
        closeInternal(id, notify = true)
    }

    fun closeAll() {
        sockets.keys.toList().forEach { closeInternal(it, notify = false) }
        workers.shutdownNow()
    }

    private fun readLoop(managed: ManagedSocket) {
        val buffer = ByteArray(READ_BUFFER)
        try {
            while (!managed.closed) {
                val input = managed.input ?: break
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                val payload = buffer.copyOf(read)
                val data = JSONObject()
                if (managed.binary) {
                    data.put(
                        "dataBase64",
                        android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP),
                    )
                } else {
                    data.put("text", String(payload, Charsets.UTF_8))
                }
                emitEvent(managed.id, "data", data)
            }
        } catch (_: Exception) {
            // Closed while reading, or remote reset.
        } finally {
            closeInternal(managed.id, notify = true)
        }
    }

    private fun closeInternal(id: Int, notify: Boolean) {
        val managed = sockets.remove(id) ?: return
        managed.closed = true
        runCatching { managed.output?.close() }
        runCatching { managed.input?.close() }
        runCatching { managed.socket?.close() }
        if (notify) emitEvent(id, "close")
    }

    private fun emitEvent(id: Int, event: String, data: JSONObject = JSONObject()) {
        emit(
            JSONObject()
                .put("type", "tcp")
                .put("socketId", id)
                .put("event", event)
                .put("data", data),
        )
    }

    private data class ManagedSocket(
        val id: Int,
        val host: String,
        val port: Int,
        val secure: Boolean,
        val binary: Boolean,
        @Volatile var socket: Socket? = null,
        @Volatile var input: BufferedInputStream? = null,
        @Volatile var output: BufferedOutputStream? = null,
        @Volatile var closed: Boolean = false,
    )

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_BUFFER = 16 * 1024

        fun isPublicHost(host: String): Boolean {
            val value = host.trim().lowercase()
            if (value.isEmpty() || value == "localhost" || value == "::1" || value.endsWith(".local")) {
                return false
            }
            if (value.startsWith("127.") || value.startsWith("10.") ||
                value.startsWith("169.254.") || value.startsWith("192.168.")
            ) {
                return false
            }
            val m172 = Regex("""^172\.(\d{1,3})\.""").find(value)
            if (m172 != null) {
                val second = m172.groupValues[1].toIntOrNull() ?: return false
                if (second in 16..31) return false
            }
            // Reject bare IPv6 unique-local / link-local prefixes.
            if (value.startsWith("fc") || value.startsWith("fd") || value.startsWith("fe80:")) {
                return false
            }
            return true
        }
    }
}
