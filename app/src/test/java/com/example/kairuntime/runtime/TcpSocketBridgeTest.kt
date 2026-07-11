package com.example.kairuntime.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpSocketBridgeTest {
    @Test fun acceptsPublicHosts() {
        assertTrue(TcpSocketBridge.isPublicHost("api.telegram.org"))
        assertTrue(TcpSocketBridge.isPublicHost("graph.facebook.com"))
        assertTrue(TcpSocketBridge.isPublicHost("1.1.1.1"))
        assertTrue(TcpSocketBridge.isPublicHost("example.com"))
    }

    @Test fun rejectsPrivateAndLoopbackHosts() {
        assertFalse(TcpSocketBridge.isPublicHost("localhost"))
        assertFalse(TcpSocketBridge.isPublicHost("127.0.0.1"))
        assertFalse(TcpSocketBridge.isPublicHost("10.0.0.5"))
        assertFalse(TcpSocketBridge.isPublicHost("192.168.1.1"))
        assertFalse(TcpSocketBridge.isPublicHost("172.16.0.1"))
        assertFalse(TcpSocketBridge.isPublicHost("172.31.255.255"))
        assertFalse(TcpSocketBridge.isPublicHost("169.254.1.1"))
        assertFalse(TcpSocketBridge.isPublicHost("printer.local"))
        assertFalse(TcpSocketBridge.isPublicHost(""))
    }

    @Test fun acceptsPublic172RangeOutsidePrivateBlock() {
        assertTrue(TcpSocketBridge.isPublicHost("172.15.0.1"))
        assertTrue(TcpSocketBridge.isPublicHost("172.32.0.1"))
    }
}
