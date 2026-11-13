@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.maxrave.data.player

import com.maxrave.common.Config
import com.maxrave.data.io.fileDir
import com.maxrave.data.io.fileSystem
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.repository.StreamRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.decodeBase64
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun audioDownloadPath(videoId: String): Path =
    (fileDir() + "/downloads").toPath() / (videoId.encodeUtf8().base64Url().trimEnd('=') + ".m4a")

internal class IosDownloads(
    private val dataStore: DataStoreManager,
    private val streams: StreamRepository,
    private val songs: SongRepository,
) : DownloadHandler, CacheRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<String, Job>()
    private val mutableDownloads = MutableStateFlow<Map<String, Pair<DownloadHandler.Download?, DownloadHandler.Download?>>>(emptyMap())
    private val mutableTasks = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val downloads = mutableDownloads.asStateFlow()
    override val downloadTask = mutableTasks.asStateFlow()

    override suspend fun downloadTrack(videoId: String, title: String, thumbnail: String) = withContext(Dispatchers.Main.immediate) {
        if (jobs.containsKey(videoId)) return@withContext
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                publish(videoId, DownloadHandler.STATE_DOWNLOADING, DownloadState.STATE_DOWNLOADING)
                val url = withContext(Dispatchers.IO) {
                    streams.getStream(dataStore, videoId, true, false, audioMimeType = "audio/mp4").filterNotNull().firstOrNull()
                } ?: error("No downloadable AAC audio stream")
                val target = audioDownloadPath(videoId)
                fileSystem().createDirectories(target.parent!!)
                download(url, target)
                ensureActive()
                publish(videoId, DownloadHandler.STATE_COMPLETED, DownloadState.STATE_DOWNLOADED)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                publish(videoId, DownloadHandler.STATE_FAILED, DownloadState.STATE_NOT_DOWNLOADED)
            } finally { jobs.remove(videoId) }
        }
        jobs[videoId] = job
        job.start()
        Unit
    }

    private suspend fun publish(id: String, state: Int, databaseState: Int) {
        mutableDownloads.update { it + (id to (DownloadHandler.Download(state) to null)) }
        mutableTasks.update { it + (id to databaseState) }
        songs.updateDownloadState(id, databaseState)
    }

    private suspend fun download(url: String, target: Path) {
        val staged = target.parent!! / (NSUUID().UUIDString + ".part")
        try {
            fetch(url, staged)
            currentCoroutineContext().ensureActive()
            // Back on Main: cancellation/removal cannot interleave with committing the file.
            fileSystem().atomicMove(staged, target)
        } finally {
            fileSystem().delete(staged, mustExist = false)
        }
    }

    private suspend fun fetch(url: String, staged: Path): Unit = suspendCancellableCoroutine { continuation ->
        val remote = NSURL.URLWithString(url) ?: return@suspendCancellableCoroutine continuation.resumeWithException(IllegalArgumentException("Invalid stream URL"))
        val task = NSURLSession.sharedSession.downloadTaskWithURL(remote) { temporary, response, error ->
            if (!continuation.isActive) return@downloadTaskWithURL
            val status = (response as? NSHTTPURLResponse)?.statusCode ?: 0
            if (error != null || temporary == null || status !in 200..299) {
                continuation.resumeWithException(IllegalStateException(error?.localizedDescription ?: "Download returned HTTP $status"))
            } else {
                val manager = NSFileManager.defaultManager
                manager.removeItemAtPath(staged.toString(), null)
                if (!manager.copyItemAtPath(temporary.path!!, staged.toString(), null)) {
                    continuation.resumeWithException(IllegalStateException("Cannot save downloaded audio"))
                } else {
                    // NSURLSession owns the original temporary file until this callback returns.
                    // A cancellation after copying must also remove our staged copy.
                    continuation.resume(Unit, onCancellation = { _, _, _ ->
                        fileSystem().delete(staged, mustExist = false)
                    })
                }
            }
        }
        continuation.invokeOnCancellation { task.cancel() }
        task.resume()
    }

    override fun removeDownload(videoId: String) {
        scope.launch {
            jobs.remove(videoId)?.cancelAndJoin()
            withContext(Dispatchers.IO) { fileSystem().delete(audioDownloadPath(videoId), mustExist = false) }
            mutableDownloads.update { it - videoId }
            mutableTasks.update { it + (videoId to DownloadState.STATE_NOT_DOWNLOADED) }
            songs.updateDownloadState(videoId, DownloadState.STATE_NOT_DOWNLOADED)
        }
    }
    override fun removeAllDownloads() { scope.launch { (getFullyCachedSongIds() + jobs.keys).distinct().forEach(::removeDownload) } }
    override suspend fun getFullyCachedSongIds(): List<String> = withContext(Dispatchers.IO) {
        fileSystem().listOrNull((fileDir() + "/downloads").toPath()).orEmpty()
            .filter { it.name.endsWith(".m4a") }
            .mapNotNull { it.name.removeSuffix(".m4a").decodeBase64()?.utf8() }
    }
    override suspend fun getAllCacheKeys(cacheName: String): List<String> =
        if (cacheName == Config.DOWNLOAD_CACHE) getFullyCachedSongIds() else emptyList()
    override suspend fun getCacheSize(cacheName: String): Long = withContext(Dispatchers.IO) {
        if (cacheName != Config.DOWNLOAD_CACHE) return@withContext 0L
        getFullyCachedSongIds().sumOf { fileSystem().metadataOrNull(audioDownloadPath(it))?.size ?: 0L }
    }
    override fun clearCache(cacheName: String) { if (cacheName == Config.DOWNLOAD_CACHE) removeAllDownloads() }
    // AVPlayer owns its transient streaming cache; deleting it must preserve offline downloads.
    override suspend fun removeCachedSong(videoId: String) = Unit
}
