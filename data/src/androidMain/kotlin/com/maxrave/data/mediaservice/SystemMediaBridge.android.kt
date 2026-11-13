package com.maxrave.data.mediaservice

// Android uses MediaServiceHandlerImpl and Media3's MediaSession, never the shared handler.
internal actual fun createSystemMediaBridge(): SystemMediaBridge = error("Android uses the Media3 media session")
