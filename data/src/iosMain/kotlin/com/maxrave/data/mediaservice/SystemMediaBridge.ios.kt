@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.maxrave.data.mediaservice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.*
import platform.MediaPlayer.*
import platform.UIKit.UIImage
import kotlin.coroutines.resume

internal actual fun createSystemMediaBridge(): SystemMediaBridge = IosSystemMediaBridge()

private class IosSystemMediaBridge : SystemMediaBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val center = MPNowPlayingInfoCenter.defaultCenter()
    private val commands = MPRemoteCommandCenter.sharedCommandCenter()
    private val targets = mutableListOf<Pair<MPRemoteCommand, Any>>()
    private var info = mutableMapOf<Any?, Any?>()
    private var playing = false
    private var artworkUrl: String? = null

    override fun initialize(): Boolean = true

    override fun setRemoteCommandListener(listener: SystemRemoteCommandListener) {
        scope.launch {
            fun bind(command: MPRemoteCommand, action: (MPRemoteCommandEvent) -> Unit) {
                val token = command.addTargetWithHandler { event ->
                    if (event != null) action(event)
                    MPRemoteCommandHandlerStatusSuccess
                }
                if (token != null) targets += command to token
            }
            bind(commands.playCommand) { listener.onPlay() }
            bind(commands.pauseCommand) { listener.onPause() }
            bind(commands.togglePlayPauseCommand) { listener.onTogglePlayPause() }
            bind(commands.stopCommand) { listener.onStop() }
            bind(commands.nextTrackCommand) { listener.onNextTrack() }
            bind(commands.previousTrackCommand) { listener.onPreviousTrack() }
            bind(commands.skipForwardCommand) { listener.onSeekForward() }
            bind(commands.skipBackwardCommand) { listener.onSeekBackward() }
            bind(commands.changePlaybackPositionCommand) {
                (it as? MPChangePlaybackPositionCommandEvent)?.let { event ->
                    listener.onChangePlaybackPosition(event.positionTime)
                }
            }
        }
    }

    override fun updateNowPlayingInfo(info: SystemNowPlayingInfo) {
        scope.launch {
            artworkUrl = info.artworkUrl
            this@IosSystemMediaBridge.info = mutableMapOf(
                MPMediaItemPropertyTitle to info.title,
                MPMediaItemPropertyArtist to info.artist,
                MPMediaItemPropertyAlbumTitle to info.album,
                MPMediaItemPropertyPlaybackDuration to info.durationSeconds,
                MPNowPlayingInfoPropertyElapsedPlaybackTime to info.elapsedTimeSeconds,
                MPNowPlayingInfoPropertyPlaybackRate to if (playing) info.playbackRate else 0.0,
                MPNowPlayingInfoPropertyPlaybackQueueIndex to info.queueIndex,
                MPNowPlayingInfoPropertyPlaybackQueueCount to info.queueCount,
            )
            publish()
        }
    }

    override suspend fun loadAndSetArtwork(url: String) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        val data = suspendCancellableCoroutine<NSData?> { continuation ->
            val task = NSURLSession.sharedSession.dataTaskWithURL(nsUrl) { bytes, _, _ ->
                if (continuation.isActive) continuation.resume(bytes)
            }
            continuation.invokeOnCancellation { task.cancel() }
            task.resume()
        } ?: return
        withContext(Dispatchers.Main.immediate) {
            if (artworkUrl != url) return@withContext
            val image = UIImage.imageWithData(data) ?: return@withContext
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(image.size) { image }
            publish()
        }
    }

    override fun updatePlaybackState(isPlaying: Boolean) {
        scope.launch {
            playing = isPlaying
            info[MPNowPlayingInfoPropertyPlaybackRate] = if (isPlaying) 1.0 else 0.0
            publish()
        }
    }

    override fun updateCommandsEnabled(hasNext: Boolean, hasPrevious: Boolean, canSeek: Boolean) {
        scope.launch {
            commands.nextTrackCommand.enabled = hasNext
            commands.previousTrackCommand.enabled = hasPrevious
            commands.changePlaybackPositionCommand.enabled = canSeek
            commands.skipForwardCommand.enabled = canSeek
            commands.skipBackwardCommand.enabled = canSeek
        }
    }

    override fun updateElapsedTime(seconds: Double, rate: Double) {
        scope.launch {
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = seconds
            info[MPNowPlayingInfoPropertyPlaybackRate] = if (playing) rate else 0.0
            publish()
        }
    }

    private fun publish() { center.nowPlayingInfo = info.toMap() }
    override fun clearNowPlayingInfo() { scope.launch { info.clear(); artworkUrl = null; center.nowPlayingInfo = null } }
    override fun release() {
        scope.launch {
            targets.forEach { (command, target) -> command.removeTarget(target) }
            targets.clear()
            center.nowPlayingInfo = null
            scope.cancel()
        }
    }
}
