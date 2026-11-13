package com.maxrave.ktorext.encoding

import io.ktor.util.ContentEncoder

// Do not advertise a codec without a decoder. Darwin handles its own transport
// compression; Ktor can still negotiate the configured gzip/deflate codecs.
actual fun createBrotliEncoder(): ContentEncoder? = null
