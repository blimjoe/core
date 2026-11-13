package com.maxrave.ktorext.crypto

import kotlin.test.*

class HmacTest {
    @Test fun sha256KnownVectorAndValidation() {
        val signer = Hmac("HmacSHA256", "key")
        val message = "The quick brown fox jumps over the lazy dog"
        assertEquals("97yD9DBThCSxMpjmqm+xQ+9NWaFJRhdZl0edvC0aPNg=", signer.generateHmac(message))
        assertTrue(signer.validateHmac(message, signer.generateHmac(message)))
        assertFalse(signer.validateHmac(message + ".", signer.generateHmac(message)))
    }
    @Test fun timestampPairAndInvalidInput() {
        val signer = Hmac("HmacSHA256", "key")
        val (mac, timestamp) = signer.getMacTimestampPair("/v1")
        assertTrue(signer.isValidTimestamp(timestamp))
        assertTrue(signer.validateHmac(timestamp + "/v1", mac))
        assertFalse(signer.isValidTimestamp("invalid"))
        assertFalse(signer.isValidTimestamp("0"))
        assertFalse(signer.isValidTimestamp(Long.MAX_VALUE.toString()))
    }
}
