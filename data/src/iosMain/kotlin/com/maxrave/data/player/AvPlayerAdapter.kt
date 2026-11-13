@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.maxrave.data.player

import com.maxrave.domain.data.player.*
import com.maxrave.data.io.fileSystem
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.domain.repository.StreamRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import platform.AVFoundation.*
import platform.AVFAudio.*
import platform.CoreMedia.*
import platform.Foundation.*
import platform.darwin.NSObjectProtocol

/** All queue and AVFoundation mutations are serialized on the main dispatcher. */
class AvPlayerAdapter(
    private val dataStore: DataStoreManager,
    private val streams: StreamRepository,
) : MediaPlayerInterface {
    val nativePlayer = AVPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val items = mutableListOf<GenericMediaItem>()
    private val order = mutableListOf<Int>()
    private val listeners = mutableSetOf<MediaPlayerListener>()
    private var index = -1
    private var loadJob: Job? = null
    private var generation = 0L
    private var released = false
    private var lastPlaying = false
    private var interrupted = false
    private var state = PlayerConstants.STATE_IDLE
    private val notifications = NSNotificationCenter.defaultCenter
    private val observers = mutableListOf<NSObjectProtocol>()

    init {
        observers += notifications.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, null,
            NSOperationQueue.mainQueue) { notification ->
            if (notification?.`object` == nativePlayer.currentItem) {
                when {
                    repeatMode == PlayerConstants.REPEAT_MODE_ONE -> seekTo(index, 0)
                    hasNextMediaItem() -> next(PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                    else -> { playWhenReady = false; setState(PlayerConstants.STATE_ENDED) }
                }
            }
        }
        observers += notifications.addObserverForName(AVAudioSessionInterruptionNotification, null,
            NSOperationQueue.mainQueue) { notification ->
            val type = (notification?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.unsignedLongValue
            if (type == AVAudioSessionInterruptionTypeBegan) {
                interrupted = playWhenReady
                nativePlayer.pause()
            } else {
                val options = (notification?.userInfo?.get(AVAudioSessionInterruptionOptionKey) as? NSNumber)?.unsignedLongValue ?: 0uL
                val resume = interrupted && options and AVAudioSessionInterruptionOptionShouldResume != 0uL
                interrupted = false
                if (resume) applyPlayback()
            }
        }
        observers += notifications.addObserverForName(AVAudioSessionRouteChangeNotification, null,
            NSOperationQueue.mainQueue) { notification ->
            val reason = (notification?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)?.unsignedLongValue
            if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) pause()
        }
        scope.launch {
            while (isActive) {
                val item = nativePlayer.currentItem
                when (item?.status) {
                    AVPlayerItemStatusReadyToPlay -> if (state == PlayerConstants.STATE_BUFFERING) setState(PlayerConstants.STATE_READY)
                    AVPlayerItemStatusFailed -> if (state != PlayerConstants.STATE_IDLE) {
                        fail(item.error?.localizedDescription ?: "Unable to play this stream")
                    }
                }
                val playing = isPlaying
                if (playing != lastPlaying) {
                    lastPlaying = playing
                    listeners.toList().forEach { it.onIsPlayingChanged(playing) }
                }
                delay(250)
            }
        }
    }

    private fun mutate(block: () -> Unit) { if (!released) scope.launch { block() } }
    private fun setState(value: Int) {
        if (state == value) return
        state = value
        listeners.toList().forEach { it.onPlaybackStateChanged(value) }
    }
    private fun fail(message: String) {
        nativePlayer.pause()
        setState(PlayerConstants.STATE_IDLE)
        listeners.toList().forEach { it.onPlayerError(PlayerError(2000, "AVPLAYER_ERROR", message)) }
    }
    private fun activateAudio(): Boolean {
        val audio = AVAudioSession.sharedInstance()
        return audio.setCategory(AVAudioSessionCategoryPlayback, error = null) && audio.setActive(true, error = null)
    }
    private fun applyPlayback() {
        if (playWhenReady && !interrupted) {
            if (activateAudio()) nativePlayer.playImmediatelyAtRate(playbackParameters.speed)
            else fail("Unable to activate the audio session")
        } else nativePlayer.pause()
    }
    private fun load(position: Long = 0) {
        loadJob?.cancel()
        val request = ++generation
        val media = currentMediaItem ?: return
        nativePlayer.pause()
        nativePlayer.replaceCurrentItemWithPlayerItem(null)
        setState(PlayerConstants.STATE_BUFFERING)
        loadJob = scope.launch {
            try {
                val uri = media.uri
                val localAudio = audioDownloadPath(media.mediaId)
                val resolved = if (fileSystem().exists(localAudio)) NSURL.fileURLWithPath(localAudio.toString()).absoluteString
                else if (uri != null && (uri.startsWith("https://") || uri.startsWith("http://") || uri.startsWith("file://"))) uri
                else withContext(Dispatchers.IO) {
                    // HLS combines audio/video in a format AVFoundation can decode without
                    // the separate WebM/Opus demuxing used by the Android and desktop engines.
                    streams.getStream(dataStore, media.mediaId, false, false, audioMimeType = "audio/mp4").filterNotNull().firstOrNull()
                        ?: streams.getStream(dataStore, media.mediaId, false, false, muxed = true).filterNotNull().firstOrNull()
                }
                ensureActive()
                if (request != generation) return@launch
                val url = resolved?.let { NSURL.URLWithString(it) } ?: error("No playable stream was returned")
                nativePlayer.replaceCurrentItemWithPlayerItem(AVPlayerItem(uRL = url))
                if (position > 0) nativePlayer.seekToTime(CMTimeMakeWithSeconds(position / 1000.0, 1000))
                applyPlayback()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (request == generation) fail(error.message ?: "Playback failed") }
        }
    }
    private fun rebuildOrder() {
        order.clear()
        order.addAll(items.indices)
        if (shuffleModeEnabled) {
            order.shuffle()
            if (index in order) { order.remove(index); order.add(0, index) }
        }
    }
    private fun timeline() { listeners.toList().forEach { it.onTimelineChanged(getCurrentMediaTimeLine(), "playlist_changed") } }
    private fun select(newIndex: Int, reason: Int, position: Long = 0) {
        if (newIndex !in items.indices) return
        index = newIndex
        listeners.toList().forEach { it.onMediaItemTransition(currentMediaItem, reason) }
        load(position)
    }
    private fun next(reason: Int) {
        val position = order.indexOf(index)
        val target = order.getOrNull(position + 1) ?: if (repeatMode == PlayerConstants.REPEAT_MODE_ALL) order.firstOrNull() else null
        target?.let { select(it, reason) }
    }

    override fun play() { playWhenReady = true; if (nativePlayer.currentItem == null) prepare() }
    override fun pause() { playWhenReady = false }
    override fun stop() = mutate {
        playWhenReady = false; loadJob?.cancel(); generation++
        nativePlayer.replaceCurrentItemWithPlayerItem(null); setState(PlayerConstants.STATE_IDLE)
    }
    override fun prepare() = mutate { if (index < 0 && items.isNotEmpty()) index = 0; if (nativePlayer.currentItem == null) load() }
    override fun seekTo(positionMs: Long) = mutate {
        val position = positionMs.coerceAtLeast(0)
        nativePlayer.seekToTime(CMTimeMakeWithSeconds(position / 1000.0, 1000))
        listeners.toList().forEach { it.onSeeked(position) }
    }
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = mutate { select(mediaItemIndex, PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_SEEK, positionMs) }
    override fun seekBack() = seekTo((currentPosition - 10_000).coerceAtLeast(0))
    override fun seekForward() = seekTo(if (duration > 0) (currentPosition + 10_000).coerceAtMost(duration) else currentPosition + 10_000)
    override fun seekToNext() = mutate { next(PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_SEEK) }
    override fun seekToPrevious() { if (currentPosition > 3_000) seekTo(0) else seekToPreviousMediaItem() }
    override fun seekToPreviousMediaItem() = mutate {
        val target = order.getOrNull(order.indexOf(index) - 1)
            ?: if (repeatMode == PlayerConstants.REPEAT_MODE_ALL) order.lastOrNull() else null
        if (target != null) select(target, PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_SEEK) else seekTo(0)
    }
    override fun setMediaItem(mediaItem: GenericMediaItem) = mutate { clearMediaItems(); addMediaItem(mediaItem) }
    override fun addMediaItem(mediaItem: GenericMediaItem) = addMediaItem(items.size, mediaItem)
    override fun addMediaItem(index: Int, mediaItem: GenericMediaItem) = mutate {
        val at = index.coerceIn(0, items.size)
        items.add(at, mediaItem)
        if (this.index >= at) this.index++
        if (this.index < 0) this.index = 0
        rebuildOrder(); timeline()
    }
    override fun removeMediaItem(index: Int) = removeMediaItems(index, index + 1) {}
    override fun removeMediaItems(fromIndex: Int, toIndex: Int, onRemoved: (List<String>) -> Unit) = mutate {
        if (fromIndex !in 0..items.size || toIndex !in fromIndex..items.size) { onRemoved(emptyList()); return@mutate }
        val oldIndex = index
        val removedCurrent = oldIndex in fromIndex until toIndex
        val removed = items.subList(fromIndex, toIndex).map { it.mediaId }
        items.subList(fromIndex, toIndex).clear()
        index = when {
            items.isEmpty() -> -1
            oldIndex >= toIndex -> oldIndex - (toIndex - fromIndex)
            removedCurrent -> fromIndex.coerceAtMost(items.lastIndex)
            else -> oldIndex
        }
        rebuildOrder(); onRemoved(removed); timeline()
        if (removedCurrent) { if (items.isEmpty()) stop() else select(index, PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) }
    }
    override fun moveMediaItem(fromIndex: Int, toIndex: Int) = mutate {
        if (fromIndex !in items.indices || toIndex !in items.indices) return@mutate
        items.add(toIndex, items.removeAt(fromIndex))
        index = when {
            index == fromIndex -> toIndex
            fromIndex < index && toIndex >= index -> index - 1
            fromIndex > index && toIndex <= index -> index + 1
            else -> index
        }
        rebuildOrder(); timeline()
    }
    override fun moveShuffledItem(fromShuffledIndex: Int, toShuffledIndex: Int) = mutate {
        if (!shuffleModeEnabled || fromShuffledIndex !in order.indices || toShuffledIndex !in order.indices) return@mutate
        order.add(toShuffledIndex, order.removeAt(fromShuffledIndex)); timeline()
    }
    override fun clearMediaItems() = mutate { stop(); items.clear(); order.clear(); index = -1; timeline() }
    override fun replaceMediaItem(index: Int, mediaItem: GenericMediaItem) = mutate {
        if (index in items.indices) { items[index] = mediaItem; timeline() }
    }
    override fun getMediaItemAt(index: Int): GenericMediaItem? = items.getOrNull(index)
    override fun getCurrentMediaTimeLine(): List<GenericMediaItem> = order.mapNotNull(items::getOrNull)
    override fun getUnshuffledIndex(shuffledIndex: Int): Int = order.getOrElse(shuffledIndex) { -1 }
    override val isPlaying get() = nativePlayer.rate > 0f && nativePlayer.error == null
    override val currentPosition get() = secondsToMillis(CMTimeGetSeconds(nativePlayer.currentTime()))
    override val duration get() = nativePlayer.currentItem?.let { secondsToMillis(CMTimeGetSeconds(it.duration)) } ?: 0L
    override val bufferedPosition get() = nativePlayer.currentItem?.loadedTimeRanges?.lastOrNull()?.let {
        val range = (it as NSValue).CMTimeRangeValue
        secondsToMillis(CMTimeGetSeconds(CMTimeRangeGetEnd(range)))
    } ?: 0L
    override val bufferedPercentage get() = if (duration > 0) (bufferedPosition * 100 / duration).toInt().coerceIn(0, 100) else 0
    override val currentMediaItem get() = items.getOrNull(index)
    override val currentMediaItemIndex get() = index
    override val mediaItemCount get() = items.size
    override val contentPosition get() = currentPosition
    override val playbackState get() = state
    override fun hasNextMediaItem() = index in items.indices && (order.indexOf(index) < order.lastIndex || repeatMode == PlayerConstants.REPEAT_MODE_ALL)
    override fun hasPreviousMediaItem() = order.indexOf(index) > 0 || (items.isNotEmpty() && repeatMode == PlayerConstants.REPEAT_MODE_ALL)
    override var shuffleModeEnabled = false
        set(value) { field = value; mutate { rebuildOrder(); listeners.toList().forEach { it.onShuffleModeEnabledChanged(value, getCurrentMediaTimeLine()) } } }
    override var repeatMode = PlayerConstants.REPEAT_MODE_OFF
        set(value) { field = value; mutate { listeners.toList().forEach { it.onRepeatModeChanged(value) } } }
    override var playWhenReady = false
        set(value) { field = value; mutate { applyPlayback() } }
    override var playbackParameters = GenericPlaybackParameters.DEFAULT
        set(value) { field = value; mutate { applyPlayback() } }
    override val audioSessionId = PlayerConstants.AUDIO_SESSION_ID_UNSET
    override var volume = 1f
        set(value) { field = value.coerceIn(0f, 1f); mutate { nativePlayer.volume = field * sleepFadeFactor; listeners.toList().forEach { it.onVolumeChanged(field) } } }
    override var sleepFadeFactor = 1f
        set(value) { field = value.coerceIn(0f, 1f); mutate { nativePlayer.volume = volume * field } }
    override var crossfadeSuppressed = false
    override var albumTrackIds: Set<String> = emptySet()
    override var skipSilenceEnabled = false
    override fun addListener(listener: MediaPlayerListener) { listeners.add(listener) }
    override fun removeListener(listener: MediaPlayerListener) { listeners.remove(listener) }
    override fun release() = mutate {
        released = true; generation++; loadJob?.cancel(); nativePlayer.pause()
        nativePlayer.replaceCurrentItemWithPlayerItem(null)
        observers.forEach(notifications::removeObserver); observers.clear(); listeners.clear(); scope.cancel()
    }
    private fun secondsToMillis(seconds: Double): Long = if (seconds.isFinite() && seconds >= 0) (seconds * 1000).toLong() else 0L
}
