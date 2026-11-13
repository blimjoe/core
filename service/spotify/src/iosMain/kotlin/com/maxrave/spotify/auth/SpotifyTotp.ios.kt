package com.maxrave.spotify.auth

import com.maxrave.ktorext.crypto.nativeHmac

actual fun generateTotp(secret: String, timestamp: Long): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    var value = 0
    var bits = 0
    val decoded = mutableListOf<Byte>()
    for (char in secret.uppercase().trimEnd('=')) {
        val digit = alphabet.indexOf(char)
        require(digit >= 0) { "Invalid Base32 secret" }
        value = (value shl 5) or digit
        bits += 5
        if (bits >= 8) {
            bits -= 8
            decoded += (value ushr bits).toByte()
        }
    }
    val counter = timestamp / 30_000L
    val message = ByteArray(8) { index -> (counter ushr ((7 - index) * 8)).toByte() }
    val mac = nativeHmac("HmacSHA1", decoded.toByteArray(), message)
    val offset = mac.last().toInt() and 15
    val binary = ((mac[offset].toInt() and 127) shl 24) or
        ((mac[offset + 1].toInt() and 255) shl 16) or
        ((mac[offset + 2].toInt() and 255) shl 8) or (mac[offset + 3].toInt() and 255)
    return (binary % 1_000_000).toString().padStart(6, '0')
}
