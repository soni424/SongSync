package pl.lambada.songsync.data.remote.lyrics_providers

import android.util.Log
import pl.lambada.songsync.data.remote.lyrics_providers.apple.AppleAPI
import pl.lambada.songsync.data.remote.lyrics_providers.others.LRCLibAPI
import pl.lambada.songsync.data.remote.lyrics_providers.others.MusixmatchAPI
import pl.lambada.songsync.data.remote.lyrics_providers.others.NeteaseAPI
import pl.lambada.songsync.data.remote.lyrics_providers.others.QQMusicAPI
import pl.lambada.songsync.data.remote.lyrics_providers.spotify.SpotifyAPI
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.util.EmptyQueryException
import pl.lambada.songsync.util.InternalErrorException
import pl.lambada.songsync.util.NoTrackFoundException
import pl.lambada.songsync.util.Providers
import pl.lambada.songsync.util.SpotifyProviderException
import pl.lambada.songsync.util.SpotifyConnectionTestReport
import pl.lambada.songsync.util.SpotifyDiagnostic
import pl.lambada.songsync.util.SpotifyFailureKind
import pl.lambada.songsync.util.SpotifyOperation
import pl.lambada.songsync.util.SpotifyServiceException
import java.io.FileNotFoundException
import java.net.UnknownHostException

/**
 * Service class for interacting with different lyrics providers.
 */
class LyricsProviderService(
    private val spotifyAPI: SpotifyAPI,
) {
    private var spotifyTrackID = ""

    // LRCLib Track ID
    private var lrcLibID = 0

    // QQMusic request payload
    private var qqPayload = ""

    // Netease Track ID and stuff
    private var neteaseID = 0L
    
    // Apple API
    private val appleAPI = AppleAPI()
    
    // Apple Track ID
    private var appleID = 0L

    // Musixmatch Song Info
    private var musixmatchSongInfo: SongInfo? = null
    // TODO: Use values from SongInfo object returned by search instead of storing them here

    fun hasSpotifyCookie(): Boolean = spotifyAPI.hasCookie()
    suspend fun verifyAndSaveSpotifyCookie(value: String) = spotifyAPI.verifyAndSaveCookie(value)
    fun clearSpotifyCookie() = spotifyAPI.clearCookie()
    suspend fun runSpotifyConnectionTest(): SpotifyConnectionTestReport = spotifyAPI.runConnectionTest()
    suspend fun preflightSpotifyAuthentication() = try {
        spotifyAPI.ensureAuthenticated()
    } catch (error: SpotifyProviderException) {
        throw error
    } catch (_: Exception) {
        throw SpotifyServiceException(
            SpotifyDiagnostic(SpotifyOperation.ACCESS_TOKEN, SpotifyFailureKind.UNEXPECTED)
        )
    }

    /**
     * Gets song information from the Spotify API.
     * @param query The SongInfo object with songName and artistName fields filled.
     * @param offset (optional) The offset used for trying to find a better match or searching again.
     * @return The SongInfo object containing the song information.
     */
    @Throws(
        UnknownHostException::class,
        FileNotFoundException::class,
        NoTrackFoundException::class,
        EmptyQueryException::class,
        InternalErrorException::class
    )
    suspend fun getSongInfo(query: SongInfo, offset: Int = 0, provider: Providers): SongInfo? {
        return try {
            when (provider) {
                Providers.SPOTIFY -> spotifyAPI.getSongInfo(query, offset).also {
                    spotifyTrackID = it.spotifyID.orEmpty()
                }
                
                Providers.LRCLIB -> LRCLibAPI().getSongInfo(query, offset).also {
                    lrcLibID = it?.lrcLibID ?: 0
                } ?: throw NoTrackFoundException()

                Providers.NETEASE -> NeteaseAPI().getSongInfo(query, offset).also {
                    neteaseID = it?.neteaseID ?: 0
                } ?: throw NoTrackFoundException()

                Providers.QQMUSIC -> QQMusicAPI().getSongInfo(query, offset).also {
                    qqPayload = it?.qqPayload ?: ""
                } ?: throw NoTrackFoundException()

                Providers.APPLE -> appleAPI.getSongInfo(query, offset).also {
                    appleID = it?.appleID ?: 0
                } ?: throw NoTrackFoundException()

                Providers.MUSIXMATCH -> MusixmatchAPI().getSongInfo(query, offset).also {
                    musixmatchSongInfo = it
                } ?: throw NoTrackFoundException()
            }
        } catch (e: Exception) {
            when (e) {
                is InternalErrorException,
                is NoTrackFoundException,
                is EmptyQueryException,
                is SpotifyProviderException -> throw e
                else -> if (provider == Providers.SPOTIFY) {
                    throw SpotifyServiceException(
                        SpotifyDiagnostic(SpotifyOperation.TRACK_SEARCH, SpotifyFailureKind.UNEXPECTED)
                    )
                } else {
                    throw InternalErrorException(Log.getStackTraceString(e))
                }
            }
        }
    }

    /**
     * Gets synced lyrics using the song link and returns them as a string formatted as an LRC file.
     * @param songLink The link to the song.
     * @return The synced lyrics as a string.
     */
    suspend fun getSyncedLyrics(
        songTitle: String,
        artistName: String,
        provider: Providers,
        // TODO providers could be a sealed interface to include such parameters
        includeTranslationNetEase: Boolean = false,
        includeRomanizationNetEase: Boolean = false,
        multiPersonWordByWord: Boolean = false,
        unsyncedFallbackMusixmatch: Boolean = true
    ): String? {
        return when (provider) {
            Providers.SPOTIFY -> try {
                spotifyAPI.getSyncedLyrics(spotifyTrackID)
            } catch (error: SpotifyProviderException) {
                throw error
            } catch (_: Exception) {
                throw SpotifyServiceException(
                    SpotifyDiagnostic(SpotifyOperation.LYRICS_REQUEST, SpotifyFailureKind.UNEXPECTED)
                )
            }
            Providers.LRCLIB -> LRCLibAPI().getSyncedLyrics(lrcLibID)
            Providers.NETEASE -> NeteaseAPI().getSyncedLyrics(
                neteaseID, includeTranslationNetEase, includeRomanizationNetEase
            )

            Providers.QQMUSIC -> QQMusicAPI().getSyncedLyrics(qqPayload, multiPersonWordByWord)

            Providers.APPLE -> appleAPI.getSyncedLyrics(
                appleID, multiPersonWordByWord
            )

            Providers.MUSIXMATCH -> MusixmatchAPI().getLyrics(
                musixmatchSongInfo,
                unsyncedFallbackMusixmatch
            )
        }
    }

    suspend fun getLyricsInLanguage(songId: Long, language: String): String? {
        return MusixmatchAPI().getLyricsInLanguage(songId, language)
    }
}
