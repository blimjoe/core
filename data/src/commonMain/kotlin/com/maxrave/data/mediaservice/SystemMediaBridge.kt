package com.maxrave.data.mediaservice

interface SystemRemoteCommandListener {
    fun onPlay()

    fun onPause()

    fun onTogglePlayPause()

    fun onStop()

    fun onNextTrack()

    fun onPreviousTrack()

    fun onSeekForward()

    fun onSeekBackward()

    fun onChangePlaybackPosition(positionSeconds: Double)
}

/**
 * Data class for Now Playing info
 */
data class SystemNowPlayingInfo(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSeconds: Double,
    val elapsedTimeSeconds: Double = 0.0,
    val playbackRate: Double = 1.0,
    val artworkUrl: String? = null,
    val queueIndex: Int = 0,
    val queueCount: Int = 1,
)

interface SystemMediaBridge {
    fun initialize(): Boolean
    fun setRemoteCommandListener(listener: SystemRemoteCommandListener)
    fun updateNowPlayingInfo(info: SystemNowPlayingInfo)
    suspend fun loadAndSetArtwork(url: String)
    fun updatePlaybackState(isPlaying: Boolean)
    fun updateCommandsEnabled(hasNext: Boolean, hasPrevious: Boolean, canSeek: Boolean)
    fun updateElapsedTime(seconds: Double, rate: Double)
    fun clearNowPlayingInfo()
    fun release()
}

internal expect fun createSystemMediaBridge(): SystemMediaBridge
