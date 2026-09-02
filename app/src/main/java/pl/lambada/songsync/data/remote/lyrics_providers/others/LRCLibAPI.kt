package pl.lambada.songsync.data.remote.lyrics_providers.others

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.parameter
import pl.lambada.songsync.data.remote.lyrics_providers.requireProviderSuccess
import pl.lambada.songsync.data.remote.lyrics_providers.isLikelyTrackMatch
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.domain.model.lyrics_providers.others.LRCLibResponse
import pl.lambada.songsync.util.EmptyQueryException
import pl.lambada.songsync.util.networking.Ktor.client
import pl.lambada.songsync.util.networking.Ktor.json
import pl.lambada.songsync.util.Providers

class LRCLibAPI {
    private val baseURL = "https://lrclib.net/api/"

    /**
     * Searches for synced lyrics using the song name and artist name.
     * @param query The SongInfo object with songName and artistName fields filled.
     * @return Search result as a SongInfo object.
     */
    suspend fun getSongInfo(query: SongInfo, offset: Int = 0): SongInfo? {
        val search = "${query.songName.orEmpty()} ${query.artistName.orEmpty()}".trim()

        if (search.isBlank())
            throw EmptyQueryException()

        val response = client.get(baseURL + "search") { parameter("q", search) }
        response.requireProviderSuccess(Providers.LRCLIB)
        val responseBody = response.bodyAsText(Charsets.UTF_8)

        if (responseBody == "[]")
            return null

        val json = json.decodeFromString<List<LRCLibResponse>>(responseBody)

        val song = json.drop(offset).firstOrNull { candidate ->
            isLikelyTrackMatch(
                query.songName,
                query.artistName,
                candidate.trackName,
                candidate.artistName,
            )
        } ?: return null

        return SongInfo(
            songName = song.trackName,
            artistName = song.artistName,
            lrcLibID = song.id
        )
    }

    /**
     * Searches for synced lyrics using the song name and artist name.
     * @param id The ID of the song from search results.
     * @return The synced lyrics as a string.
     */
    suspend fun getSyncedLyrics(id: Int): String? {
        return getLyrics(id)?.syncedLyrics
    }

    suspend fun getLyrics(id: Int): LRCLibResponse? {
        val response = client.get(
            baseURL + "get/$id"
        )
        response.requireProviderSuccess(Providers.LRCLIB)
        val responseBody = response.bodyAsText(Charsets.UTF_8)

        if (responseBody == "[]")
            return null

        return json.decodeFromString<LRCLibResponse>(responseBody)
    }
}
