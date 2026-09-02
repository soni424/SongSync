package pl.lambada.songsync.data.remote.lyrics_providers.apple

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.lambada.songsync.data.remote.PaxMusicHelper
import pl.lambada.songsync.data.remote.lyrics_providers.requireProviderSuccess
import pl.lambada.songsync.data.remote.lyrics_providers.isLikelyTrackMatch
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.domain.model.lyrics_providers.others.AppleMusicSearchResponse
import pl.lambada.songsync.util.EmptyQueryException
import pl.lambada.songsync.util.networking.Ktor.client
import pl.lambada.songsync.util.networking.Ktor.json
import pl.lambada.songsync.util.Providers
import java.net.URLEncoder

class AppleAPI {
    private val lyricsBaseURL = "https://lyrics.paxsenix.org/"
    private val apiBaseURL = "https://amp-api.music.apple.com/v1/catalog/us"
    private val tokenManager = AppleTokenManager()

    /**
     * Searches for song information using the song name and artist name.
     * @param query The SongInfo object with songName and artistName fields filled.
     * @param offset The offset used for trying to find a better match or searching again.
     * @return Search result as a SongInfo object.
     */
    suspend fun getSongInfo(query: SongInfo, offset: Int = 0, retryAuthentication: Boolean = true): SongInfo? {
        val search = withContext(Dispatchers.IO) {
            URLEncoder.encode(
                "${query.songName.orEmpty()} ${query.artistName.orEmpty()}".trim(),
                Charsets.UTF_8.toString()
            )
        }

        if (search.isBlank())
            throw EmptyQueryException()

        val token = tokenManager.getToken()
            
        val response = client.get(
                "$apiBaseURL/search?" +
                "term=$search&" +
                "types=songs&" +
                "limit=25&" +
                "l=en-US&" +
                "platform=web&" +
                "format[resources]=map&" +
                "include[songs]=artists&" +
                "extend=artistUrl"
            ) {
                header("Authorization", "Bearer $token")
                header("Origin", "https://music.apple.com")
                header("Referer", "https://music.apple.com/")
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:95.0) Gecko/20100101 Firefox/95.0")
                header("Accept", "application/json")
                header("Accept-Language", "en-US,en;q=0.5")
                header("x-apple-renewal", "true")
            }

            val responseBody = response.bodyAsText(Charsets.UTF_8)

        if (response.status.value == 401 && retryAuthentication) {
            tokenManager.clearToken()
            return getSongInfo(query, offset, retryAuthentication = false)
        }
        response.requireProviderSuccess(Providers.APPLE)

        val searchResponse = json.decodeFromString<AppleMusicSearchResponse>(responseBody)

        val songs = searchResponse.results.songs?.data ?: return null
        val resources = searchResponse.resources?.songs ?: return null
        val song = songs
            .drop(offset.coerceAtLeast(0))
            .firstOrNull { candidate ->
                resources[candidate.id]?.attributes?.let { attributes ->
                    isLikelyTrackMatch(
                        query.songName,
                        query.artistName,
                        attributes.name,
                        attributes.artistName,
                    )
                } == true
            } ?: return null

        val songId = song.id
        val songDetail = resources[songId] ?: return null
        val attributes = songDetail.attributes

        val artworkUrl = attributes.artwork.url
            .replace("{w}", "100")
            .replace("{h}", "100")
            .replace("{f}", "png")

        return SongInfo(
            songName = attributes.name,
            artistName = attributes.artistName,
            songLink = attributes.url,
            albumCoverLink = artworkUrl,
            appleID = songId.toLongOrNull() ?: return null
        )
    }

    /**
     * Gets synced lyrics using the song ID and returns them as a string formatted as an LRC file.
     * @param id The ID of the song from search results.
     * @param multiPersonWordByWord Flag to format lyrics for multiple persons word by word.
     * @return The synced lyrics as a string.
     */
    suspend fun getSyncedLyrics(id: Long, multiPersonWordByWord: Boolean): String? {
        val response = client.get(
            lyricsBaseURL + "apple-music/lyrics?id=$id"
        )
        response.requireProviderSuccess(Providers.APPLE)
        val responseBody = response.bodyAsText(Charsets.UTF_8)

        if (responseBody.isEmpty())
            return null

        return PaxMusicHelper().formatWordByWordLyrics(responseBody, multiPersonWordByWord)
    }
}
