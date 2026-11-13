package com.maxrave.spotify.auth

import kotlin.test.*

class SpotifyTotpTest {
    @Test fun rfc6238Sha1VectorsWithSixDigits() {
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        assertEquals("287082", generateTotp(secret, 59_000))
        assertEquals("081804", generateTotp(secret, 1_111_111_109_000))
        assertEquals("050471", generateTotp(secret, 1_111_111_111_000))
    }
}
