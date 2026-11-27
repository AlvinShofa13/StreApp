package com.studiopocket.source.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RemoteProtocolTest {

    @Test
    fun testEncodeVideoConfigWithSpsAndPps() {
        val sps = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1F)
        val pps = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())

        val encoded = RemoteNodeClient.encodeVideoConfig(1280, 720, sps, pps)
        assertEquals(16 + sps.size + pps.size, encoded.size)

        val buf = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        assertEquals(1280, buf.int)
        assertEquals(720, buf.int)
        val spsLen = buf.int
        assertEquals(sps.size, spsLen)
        val decodedSps = ByteArray(spsLen).also { buf.get(it) }
        assertArrayEquals(sps, decodedSps)
        val ppsLen = buf.int
        assertEquals(pps.size, ppsLen)
        val decodedPps = ByteArray(ppsLen).also { buf.get(it) }
        assertArrayEquals(pps, decodedPps)
    }

    @Test
    fun testEncodeVideoConfigWithCombinedCsd0AndEmptyPps() {
        val csd0 = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x00, 0x00, 0x01, 0x68)
        val encoded = RemoteNodeClient.encodeVideoConfig(1280, 720, csd0, ByteArray(0))
        assertEquals(16 + csd0.size, encoded.size)

        val buf = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        assertEquals(1280, buf.int)
        assertEquals(720, buf.int)
        val spsLen = buf.int
        assertEquals(csd0.size, spsLen)
        buf.position(buf.position() + spsLen)
        assertEquals(0, buf.int)
    }

    @Test
    fun testPairingInfoUriRoundTrip() {
        val info = PairingInfo(
            host = "192.168.42.129",
            port = 45678,
            token = "ABCD2345",
            hostId = "host1234",
        )
        val parsed = PairingInfo.parse(info.toUri())
        assertNotNull(parsed)
        assertEquals(info, parsed)
    }

    @Test
    fun testRemoteHostServerInitializesPairingWithHostId() {
        val server = RemoteHostServer(hostName = "StreApp")
        assertNotNull(server.pairing)
        assertEquals(8, server.pairing.hostId.length)
        assertEquals(8, server.pairing.token.length)
    }
}
