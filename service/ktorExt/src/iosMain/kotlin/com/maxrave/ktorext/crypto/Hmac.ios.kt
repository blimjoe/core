@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.maxrave.ktorext.crypto

import kotlinx.cinterop.*
import platform.CoreCrypto.*
import kotlin.io.encoding.Base64
import kotlin.time.Clock

/** CommonCrypto performs HMAC without retaining mutable signing state across requests. */
fun nativeHmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
    val (identifier, size) = when (algorithm.lowercase().replace("-", "")) {
        "hmacsha1" -> kCCHmacAlgSHA1 to CC_SHA1_DIGEST_LENGTH
        "hmacsha256" -> kCCHmacAlgSHA256 to CC_SHA256_DIGEST_LENGTH
        "hmacsha384" -> kCCHmacAlgSHA384 to CC_SHA384_DIGEST_LENGTH
        "hmacsha512" -> kCCHmacAlgSHA512 to CC_SHA512_DIGEST_LENGTH
        else -> error("Unsupported HMAC algorithm: $algorithm")
    }
    val result = ByteArray(size)
    key.usePinned { secret -> data.usePinned { input -> result.usePinned { output ->
        CCHmac(identifier, if (key.isEmpty()) null else secret.addressOf(0), key.size.toULong(),
            if (data.isEmpty()) null else input.addressOf(0), data.size.toULong(), output.addressOf(0))
    } } }
    return result
}

actual class Hmac actual constructor(private val algorithm: String, secretKey: String) {
    private val key = secretKey.encodeToByteArray()
    actual fun getMacTimestampPair(uri: String): Pair<String, String> {
        val timestamp = Clock.System.now().toEpochMilliseconds().toString()
        return generateHmac(timestamp + uri) to timestamp
    }
    actual fun generateHmac(data: String): String = Base64.encode(nativeHmac(algorithm, key, data.encodeToByteArray()))
    actual fun validateHmac(data: String, hmac: String): Boolean {
        val expected = generateHmac(data)
        if (expected.length != hmac.length) return false
        var difference = 0
        for (i in expected.indices) difference = difference or (expected[i].code xor hmac[i].code)
        return difference == 0
    }
    actual fun isValidTimestamp(timestamp: String): Boolean {
        val value = timestamp.toLongOrNull() ?: return false
        val now = Clock.System.now().toEpochMilliseconds()
        return value <= now && value >= now - 300_000L
    }
}
