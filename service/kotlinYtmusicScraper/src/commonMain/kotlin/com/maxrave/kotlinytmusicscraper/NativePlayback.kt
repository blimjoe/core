package com.maxrave.kotlinytmusicscraper

import com.maxrave.kotlinytmusicscraper.models.YouTubeClient

/** AVFoundation consumes direct AAC/HLS; JVM/Android keep their existing extractor. */
internal expect fun nativePlaybackClient(): YouTubeClient?
