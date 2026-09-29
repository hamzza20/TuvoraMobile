package com.tuvora.tvos.screens

import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.cloud.CloudLibraryFile
import com.nuvio.app.features.cloud.CloudLibraryItem
import com.nuvio.app.features.cloud.CloudLibraryPlaybackResult
import com.nuvio.app.features.cloud.CloudLibraryRepository
import com.nuvio.app.features.cloud.CloudLibraryUiState
import com.nuvio.app.features.cloud.playbackVideoId
import com.nuvio.app.features.cloud.providerPosterUrl
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.tuvora.tvos.player.TvPlayerSession
import kotlinx.coroutines.flow.StateFlow

/**
 * NuvioTV's Library → Cloud view: files in the connected debrid accounts (the phone's shared
 * CloudLibraryRepository). A file plays through the same launch the phone builds
 * (MainAppContent.launchCloudLibraryFile), resuming where the viewer left off.
 */
object TvCloudLibrary {
    val state: StateFlow<CloudLibraryUiState> get() = CloudLibraryRepository.uiState

    fun ensureLoaded() = CloudLibraryRepository.ensureLoaded()
    fun refresh() = CloudLibraryRepository.refresh()

    /** A player session for [file], or null when the account can't resolve it (the screen says so). */
    suspend fun play(item: CloudLibraryItem, file: CloudLibraryFile): TvPlayerSession? {
        val resolved = CloudLibraryRepository.resolvePlayback(item, file) as? CloudLibraryPlaybackResult.Success ?: return null
        val title = resolved.filename?.takeIf { it.isNotBlank() } ?: file.name.ifBlank { item.name }
        val videoId = item.playbackVideoId(file)
        val resume = WatchProgressRepository.progressForVideo(videoId = videoId, parentMetaId = item.stableKey)?.takeIf { it.isResumable }
        return TvPlayerSession(
            PlayerLaunch(
                profileId = ProfileRepository.activeProfileId,
                title = title,
                sourceUrl = resolved.url,
                streamTitle = title,
                streamSubtitle = item.name.takeIf { it != title },
                providerName = item.providerName,
                providerAddonId = "cloud:${item.providerId}",
                poster = item.providerPosterUrl(),
                contentType = CloudLibraryContentType,
                videoId = videoId,
                parentMetaId = item.stableKey,
                parentMetaType = CloudLibraryContentType,
                initialPositionMs = resume?.lastPositionMs ?: 0L,
            ),
        )
    }

    /** NuvioTV cloud card line: "1 playable file" / "N playable files" / "No playable files". */
    fun fileCountLabel(item: CloudLibraryItem): String = when (val n = item.playableFiles.size) {
        0 -> "No playable files"
        1 -> "1 playable file"
        else -> "$n playable files"
    }
}
