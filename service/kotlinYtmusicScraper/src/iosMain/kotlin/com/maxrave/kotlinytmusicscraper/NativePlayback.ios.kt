package com.maxrave.kotlinytmusicscraper

import com.maxrave.kotlinytmusicscraper.models.YouTubeClient

// Direct AAC and HLS are returned by this Apple client without a JVM signature decoder.
// Client parameters: yt-dlp/extractor/youtube/_base.py, INNERTUBE_CLIENTS['visionos'].
internal actual fun nativePlaybackClient(): YouTubeClient? = YouTubeClient(
    clientName = "VISIONOS", clientVersion = "1.02", api_key = "",
    deviceMake = "Apple", deviceModel = "RealityDevice17,1",
    osName = "visionOS", osVersion = "26.5.23O471", xClientName = 101,
    userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
)
