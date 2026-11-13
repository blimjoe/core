package com.maxrave.data.di.loader

import com.maxrave.common.Config.SERVICE_SCOPE
import com.maxrave.data.player.AvPlayerAdapter
import com.maxrave.data.player.IosDownloads
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.domain.repository.LibrarySyncRepository
import com.maxrave.domain.data.model.librarysync.LibrarySyncState
import com.maxrave.domain.data.model.librarysync.PendingSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.module

actual fun loadMediaService() {
    loadKoinModules(module {
        single<CoroutineScope>(named(SERVICE_SCOPE)) {
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }
        single { AvPlayerAdapter(get(), get()) }
        single<MediaPlayerInterface> { get<AvPlayerAdapter>() }
        single { IosDownloads(get(), get(), get()) }
        single<DownloadHandler> { get<IosDownloads>() }
        single<CacheRepository> { get<IosDownloads>() }
    })
}

actual fun loadLoginSyncModule() {
    loadKoinModules(module {
        // iOS does not participate in device pairing. Shared settings and history deletion
        // still depend on this interface; no host/phone implementation is registered.
        single<LibrarySyncRepository> { IosLibrarySyncRepository() }
    })
}

private class IosLibrarySyncRepository : LibrarySyncRepository {
    override val state = MutableStateFlow(LibrarySyncState()).asStateFlow()
    override val pendingSettings = MutableStateFlow<PendingSettings?>(null).asStateFlow()
    override suspend fun syncNow() = error("Device pairing is unavailable on iOS")
    override suspend fun unpairAll() = Unit
    override suspend fun applyPendingSettings() = Unit
    override suspend fun dismissPendingSettings() = Unit
}
