package com.maxrave.data.mediaservice

import com.maxrave.data.mediaservice.mac.MacOSMediaIntegration
import com.maxrave.data.mediaservice.mac.MacOSRemoteCommandListener
import com.maxrave.data.mediaservice.mac.NowPlayingInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.simpmusic.nowplayingcenter.NPYC
import org.simpmusic.nowplayingcenter.domain.NowPlayingListener
import org.simpmusic.nowplayingcenter.domain.Platform

internal actual fun createSystemMediaBridge(): SystemMediaBridge = DesktopSystemMediaBridge()

private class DesktopSystemMediaBridge : SystemMediaBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mac = if (MacOSMediaIntegration.isSupported()) MacOSMediaIntegration.getInstance() else null
    private val desktop = if (mac == null) runCatching {
        val platform = if (System.getProperty("os.name").lowercase().contains("win")) Platform.Windows
        else Platform.Linux("SimpMusic", "com-maxrave-simpmusic-MainKt")
        NPYC(platform)
    }.getOrNull() else null
    private var playing = false
    private var next = false
    private var previous = false

    override fun initialize(): Boolean = mac?.initialize() ?: (desktop != null)

    override fun setRemoteCommandListener(listener: SystemRemoteCommandListener) {
        desktop?.setListener(object : NowPlayingListener {
            override fun onPlayPause() = listener.onTogglePlayPause()
            override fun onNext() = listener.onNextTrack()
            override fun onPrevious() = listener.onPreviousTrack()
            override fun onStop() = listener.onStop()
        })
        mac?.setRemoteCommandListener(object : MacOSRemoteCommandListener {
            override fun onPlay() = listener.onPlay()
            override fun onPause() = listener.onPause()
            override fun onTogglePlayPause() = listener.onTogglePlayPause()
            override fun onStop() = listener.onStop()
            override fun onNextTrack() = listener.onNextTrack()
            override fun onPreviousTrack() = listener.onPreviousTrack()
            override fun onSeekForward() = listener.onSeekForward()
            override fun onSeekBackward() = listener.onSeekBackward()
            override fun onChangePlaybackPosition(positionSeconds: Double) = listener.onChangePlaybackPosition(positionSeconds)
        })
    }

    override fun updateNowPlayingInfo(info: SystemNowPlayingInfo) {
        scope.launch { desktop?.setNowPlaying(info.title, info.artist, info.album, info.artworkUrl) }
        mac?.updateNowPlayingInfo(NowPlayingInfo(info.title, info.artist, info.album, info.durationSeconds,
            info.elapsedTimeSeconds, info.playbackRate, info.artworkUrl, info.queueIndex, info.queueCount))
    }

    override suspend fun loadAndSetArtwork(url: String) { mac?.loadAndSetArtwork(url) }
    override fun updatePlaybackState(isPlaying: Boolean) {
        playing = isPlaying
        mac?.updatePlaybackState(isPlaying)
        updateDesktopButtons()
    }

    override fun updateCommandsEnabled(hasNext: Boolean, hasPrevious: Boolean, canSeek: Boolean) {
        next = hasNext
        previous = hasPrevious
        mac?.updateCommandsEnabled(hasNext, hasPrevious, canSeek)
        updateDesktopButtons()
    }

    private fun updateDesktopButtons() { scope.launch { desktop?.setButtonEnabled(playing, next, previous) } }
    override fun updateElapsedTime(seconds: Double, rate: Double) { mac?.updateElapsedTime(seconds, rate) }
    override fun clearNowPlayingInfo() { mac?.clearNowPlayingInfo() }
    override fun release() {
        desktop?.removeListener()
        mac?.release()
        scope.cancel()
    }
}
