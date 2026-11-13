package com.maxrave.data.io

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import platform.Foundation.NSURL

actual suspend fun readLocalImageBytes(uri: String): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        val path = if (uri.startsWith("file://")) requireNotNull(NSURL.URLWithString(uri)?.path) else uri
        fileSystem().read(path.toPath()) { readByteArray() }
    }.getOrNull()
}
