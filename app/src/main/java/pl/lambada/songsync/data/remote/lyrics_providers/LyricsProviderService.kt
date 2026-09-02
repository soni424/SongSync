package pl.lambada.songsync.data.remote.lyrics_providers

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import pl.lambada.songsync.data.remote.lyrics_providers.apple.AppleAPI
import pl.lambada.songsync.data.remote.lyrics_providers.others.LRCLibAPI
import pl.lambada.songsync.data.remote.lyrics_providers.others.MusixmatchAPI
import pl.lambada.songsync.data.remote.lyrics_providers.others.NeteaseAPI
import pl.lambada.songsync.data.remote.lyrics_providers.others.QQMusicAPI
import pl.lambada.songsync.data.remote.lyrics_providers.spotify.SpotifyAPI
import pl.lambada.songsync.data.remote.lyrics_providers.spotify.SpotifyLyricsAPI
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.util.EmptyQueryException
import pl.lambada.songsync.util.InternalErrorException
import pl.lambada.songsync.util.NoTrackFoundException
import pl.lambada.songsync.util.Providers
import java.net.UnknownHostException

class LyricsProviderService(
    providers: List<LyricsProvider>? = null,
    hasValidatedNetwork: () -> Boolean = { true },
) {
    private val musixmatchAPI = MusixmatchAPI()
    private val coordinator = LyricsCoordinator(
        providers ?: createDefaultProviders(),
        hasValidatedNetwork,
    )

    suspend fun lookupLyrics(
        request: LyricsRequest,
        preferredProvider: Providers,
    ): LyricsLookupOutcome = coordinator.lookup(request, preferredProvider)

    suspend fun getLyricsInLanguage(songId: Long, language: String): String? {
        if (!Providers.MUSIXMATCH.isAvailable) {
            throw ProviderRequestException(
                LyricsFailure.ProviderUnavailable(Providers.MUSIXMATCH)
            )
        }
        return musixmatchAPI.getLyricsInLanguage(songId, language)
    }

    private fun createDefaultProviders(): List<LyricsProvider> {
        val spotifyAPI = SpotifyAPI()
        val spotifyLyricsAPI = SpotifyLyricsAPI()
        val lrcLibAPI = LRCLibAPI()
        val qqMusicAPI = QQMusicAPI()
        val appleAPI = AppleAPI()
        val neteaseAPI = NeteaseAPI()

        return listOf(
            provider(Providers.SPOTIFY) { request ->
                val song = spotifyAPI.getSongInfo(request.toSongInfo(), request.offset)
                val trackUrl = song.songLink ?: return@provider ProviderResult.NoMatch
                val lyrics = spotifyLyricsAPI.getSyncedLyrics(trackUrl)
                    ?: return@provider ProviderResult.NoLyrics
                ProviderResult.Success(song, lyrics, LyricsTiming.SYNCED)
            },
            provider(Providers.LRCLIB) { request ->
                val song = retrySerializationOnce {
                    lrcLibAPI.getSongInfo(request.toSongInfo(), request.offset)
                }
                    ?: return@provider ProviderResult.NoMatch
                val id = song.lrcLibID ?: return@provider ProviderResult.NoMatch
                val lyrics = retrySerializationOnce { lrcLibAPI.getLyrics(id) }
                    ?: return@provider ProviderResult.NoLyrics
                when {
                    !lyrics.syncedLyrics.isNullOrBlank() ->
                        ProviderResult.Success(song, lyrics.syncedLyrics, LyricsTiming.SYNCED)
                    request.allowUnsynced && !lyrics.plainLyrics.isNullOrBlank() ->
                        ProviderResult.Success(song, lyrics.plainLyrics, LyricsTiming.UNSYNCED)
                    else -> ProviderResult.NoLyrics
                }
            },
            provider(Providers.QQMUSIC) { request ->
                val song = qqMusicAPI.getSongInfo(request.toSongInfo(), request.offset)
                    ?: return@provider ProviderResult.NoMatch
                val payload = song.qqPayload ?: return@provider ProviderResult.NoMatch
                val lyrics = qqMusicAPI.getSyncedLyrics(payload, request.multiPersonWordByWord)
                    ?.takeIf(String::isNotBlank)
                    ?: return@provider ProviderResult.NoLyrics
                ProviderResult.Success(song, lyrics, LyricsTiming.SYNCED)
            },
            provider(Providers.APPLE) { request ->
                val song = appleAPI.getSongInfo(request.toSongInfo(), request.offset)
                    ?: return@provider ProviderResult.NoMatch
                val id = song.appleID ?: return@provider ProviderResult.NoMatch
                val lyrics = appleAPI.getSyncedLyrics(id, request.multiPersonWordByWord)
                    ?.takeIf(String::isNotBlank)
                    ?: return@provider ProviderResult.NoLyrics
                ProviderResult.Success(song, lyrics, LyricsTiming.SYNCED)
            },
            provider(Providers.NETEASE, enabled = false) { request ->
                val song = neteaseAPI.getSongInfo(request.toSongInfo(), request.offset)
                    ?: return@provider ProviderResult.NoMatch
                val id = song.neteaseID ?: return@provider ProviderResult.NoMatch
                val lyrics = neteaseAPI.getSyncedLyrics(
                    id,
                    request.includeTranslation,
                    request.includeRomanization,
                )?.takeIf(String::isNotBlank) ?: return@provider ProviderResult.NoLyrics
                ProviderResult.Success(song, lyrics, LyricsTiming.SYNCED)
            },
            provider(Providers.MUSIXMATCH, enabled = false) { request ->
                val song = musixmatchAPI.getSongInfo(request.toSongInfo(), request.offset)
                    ?: return@provider ProviderResult.NoMatch
                val synced = song.syncedLyrics?.takeIf(String::isNotBlank)
                if (synced != null) {
                    ProviderResult.Success(song, synced, LyricsTiming.SYNCED)
                } else {
                    val unsynced = song.unsyncedLyrics?.takeIf(String::isNotBlank)
                    if (request.allowUnsynced && unsynced != null) {
                        ProviderResult.Success(song, unsynced, LyricsTiming.UNSYNCED)
                    } else {
                        ProviderResult.NoLyrics
                    }
                }
            },
        )
    }

    private fun provider(
        id: Providers,
        enabled: Boolean = true,
        lookupBlock: suspend (LyricsRequest) -> ProviderResult,
    ) = object : LyricsProvider {
        override val id = id
        override val isEnabled = enabled

        override suspend fun lookup(request: LyricsRequest): ProviderResult = try {
            lookupBlock(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            error.toProviderResult(id)
        }
    }
}

private fun LyricsRequest.toSongInfo() = SongInfo(title, artist)

private suspend fun <T> retrySerializationOnce(block: suspend () -> T): T = try {
    block()
} catch (_: SerializationException) {
    block()
}

private fun Throwable.toProviderResult(provider: Providers): ProviderResult = when (this) {
    is ProviderRequestException -> ProviderResult.Failure(failure)
    is EmptyQueryException -> ProviderResult.Failure(LyricsFailure.InvalidQuery)
    is NoTrackFoundException -> ProviderResult.NoMatch
    is UnknownHostException -> ProviderResult.Failure(LyricsFailure.DnsFailure(provider))
    is HttpRequestTimeoutException,
    is ConnectTimeoutException,
    is SocketTimeoutException,
    is java.net.SocketTimeoutException -> ProviderResult.Failure(LyricsFailure.Timeout(provider))
    is SerializationException,
    is InternalErrorException -> ProviderResult.Failure(LyricsFailure.InvalidResponse(provider))
    else -> ProviderResult.Failure(LyricsFailure.ProviderUnavailable(provider))
}
