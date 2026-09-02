package pl.lambada.songsync.data.remote.lyrics_providers.others

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsFailure
import pl.lambada.songsync.data.remote.lyrics_providers.ProviderRequestException
import pl.lambada.songsync.data.remote.lyrics_providers.isLikelyTrackMatch
import pl.lambada.songsync.data.remote.lyrics_providers.requireProviderSuccess
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.domain.model.lyrics_providers.others.NeteaseLyricsResponse
import pl.lambada.songsync.domain.model.lyrics_providers.others.NeteaseResponse
import pl.lambada.songsync.util.EmptyQueryException
import pl.lambada.songsync.util.Providers
import pl.lambada.songsync.util.networking.Ktor.client
import pl.lambada.songsync.util.networking.Ktor.json

class NeteaseAPI {
    private val baseURL = "https://music.163.com/api/"

    suspend fun getSongInfo(query: SongInfo, offset: Int? = 0): SongInfo? {
        val search = "${query.songName.orEmpty()} ${query.artistName.orEmpty()}".trim()
        if (search.isBlank()) throw EmptyQueryException()

        val response = client.get(baseURL + "search/pc") {
            commonHeaders()
            parameter("limit", 10)
            parameter("type", 1)
            parameter("offset", offset ?: 0)
            parameter("s", search)
        }
        response.requireProviderSuccess(Providers.NETEASE)
        val body = response.bodyAsText(Charsets.UTF_8)
        if (NETEASE_ANTI_ABUSE_CODE.containsMatchIn(body)) {
            throw ProviderRequestException(LyricsFailure.ProviderUnavailable(Providers.NETEASE))
        }
        if (body == "[]" || body.contains("\"songCount\":0")) return null

        val decoded = json.decodeFromString<NeteaseResponse>(body)
        val song = decoded.result.songs.firstOrNull { candidate ->
            isLikelyTrackMatch(
                query.songName,
                query.artistName,
                candidate.name,
                candidate.artists.joinToString(", ") { it.name },
            )
        } ?: return null

        return SongInfo(
            songName = song.name,
            artistName = song.artists.joinToString(", ") { it.name },
            neteaseID = song.id,
        )
    }

    suspend fun getSyncedLyrics(
        id: Long,
        includeTranslation: Boolean = false,
        includeRomanization: Boolean = false,
    ): String? {
        val response = client.get(baseURL + "song/lyric") {
            commonHeaders()
            parameter("id", id)
            parameter("lv", 1)
            parameter("tv", 1)
            parameter("rv", 1)
        }
        response.requireProviderSuccess(Providers.NETEASE)
        val body = response.bodyAsText(Charsets.UTF_8)
        if (NETEASE_ANTI_ABUSE_CODE.containsMatchIn(body)) {
            throw ProviderRequestException(LyricsFailure.ProviderUnavailable(Providers.NETEASE))
        }
        if (body == "[]") return null

        val decoded = json.decodeFromString<NeteaseLyricsResponse>(body)
        if (decoded.lrc.lyric.isBlank()) return null
        return buildString {
            append(decoded.lrc.lyric)
            if (includeTranslation && !decoded.tlyric?.lyric.isNullOrBlank()) {
                append("\n\n").append(decoded.tlyric?.lyric)
            }
            if (includeRomanization && !decoded.romalrc?.lyric.isNullOrBlank()) {
                append("\n\n").append(decoded.romalrc?.lyric)
            }
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.commonHeaders() {
        header("Accept", "application/json")
        header("Accept-Language", "en-US,en;q=0.9")
        header("Referer", "https://music.163.com/")
        header("User-Agent", "SongSync/4.3.3 (Android)")
    }

    private companion object {
        val NETEASE_ANTI_ABUSE_CODE = Regex("\\\"code\\\"\\s*:\\s*-462")
    }
}
