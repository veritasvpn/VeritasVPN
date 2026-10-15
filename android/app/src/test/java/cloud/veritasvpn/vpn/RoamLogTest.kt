package cloud.veritasvpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoamLogTest {
    @Test
    fun stepsStayInARingWithoutAddressesOrSecrets() {
        val log = RoamLog(capacity = 3)
        assertEquals("handle 10 -> 20", RoamLog.sanitize("handle 10 -> 20"))
        assertEquals("peer-read net=CELLULAR handle=20", RoamLog.sanitize("peer-read net=CELLULAR handle=20"))
        assertEquals("endpoint class=public", RoamLog.sanitize("endpoint class=public"))
        assertEquals("endpoint class=LAN", RoamLog.sanitize("endpoint class=LAN"))
        assertEquals("endpoint class=stealth", RoamLog.sanitize("endpoint class=stealth"))
        assertEquals("socket swap", RoamLog.sanitize("socket swap"))
        assertEquals("handshake ok", RoamLog.sanitize("handshake ok"))
        assertEquals("handshake fail", RoamLog.sanitize("handshake fail"))
        assertEquals("rebuild", RoamLog.sanitize("rebuild"))
        assertNull(RoamLog.sanitize("endpoint 192.168.0.6:51820"))
        assertNull(RoamLog.sanitize("token=abc"))
        assertNull(RoamLog.sanitize("private key=abcd"))

        log.record("handle 10 -> 20")
        log.record("peer-read net=CELLULAR handle=20")
        log.record("endpoint class=public")
        log.record("socket swap")
        log.record("endpoint 192.168.0.6:51820")
        log.record("handshake ok")
        val text = log.snapshot()
        assertFalse(text.contains("handle 10"))
        assertTrue(text.contains("endpoint class=public"))
        assertTrue(text.contains("socket swap"))
        assertTrue(text.contains("handshake ok"))
        assertFalse(text.contains("192.168"))
        assertFalse(text.contains("51820"))
    }
}