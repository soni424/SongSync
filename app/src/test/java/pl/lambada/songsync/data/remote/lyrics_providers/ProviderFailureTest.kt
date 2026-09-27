package pl.lambada.songsync.data.remote.lyrics_providers

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lambada.songsync.data.remote.lyrics_providers.others.MusixmatchAPI
import pl.lambada.songsync.data.remote.lyrics_providers.spotify.SpotifyAPI
import pl.lambada.songsync.data.remote.lyrics_providers.spotify.SpotifyCredentialStore
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.util.Providers
import pl.lambada.songsync.util.NoTrackFoundException
import pl.lambada.songsync.util.ProviderFailureKind
import pl.lambada.songsync.util.ProviderServiceException

class ProviderFailureTest {
    @Test fun timeoutDoesNotExposeRawStackTraceOrRequestDetails() = runTest {
        val client = HttpClient(MockEngine { throw ConnectTimeoutException("secret-url?token=private", null) })
        val service = service(client)

        val failure = runCatching {
            service.getSongInfo(SongInfo("SALTARE", "Billyrrom"), provider = Providers.MUSIXMATCH)
        }.exceptionOrNull()!!

        assertTrue(failure.message.orEmpty().contains("Musixmatch"))
        assertFalse(failure.message.orEmpty().contains("secret-url"))
        assertFalse(failure.message.orEmpty().contains("ConnectTimeoutException"))
        assertEquals(ProviderFailureKind.NETWORK, (failure as ProviderServiceException).diagnostic.kind)
        assertFalse(failure.diagnostic.toReport("test").contains("secret-url"))
    }

    @Test fun rateLimitIsNotReportedAsMissingLyrics() = runTest {
        val client = HttpClient(MockEngine { respond("private-response", HttpStatusCode.TooManyRequests) })
        val failure = searchFailure(client) as ProviderServiceException
        assertEquals(429, failure.diagnostic.httpStatus)
        assertEquals(ProviderFailureKind.RATE_LIMITED, failure.diagnostic.kind)
        assertFalse(failure.diagnostic.toReport("test").contains("private-response"))
    }

    @Test fun serverErrorIsProviderUnavailable() = runTest {
        val client = HttpClient(MockEngine { respond("private-response", HttpStatusCode.InternalServerError) })
        val failure = searchFailure(client) as ProviderServiceException
        assertEquals(500, failure.diagnostic.httpStatus)
        assertEquals(ProviderFailureKind.UNAVAILABLE, failure.diagnostic.kind)
    }

    @Test fun invalidResponseIsNotMissingLyrics() = runTest {
        val client = HttpClient(MockEngine { respond("private-response", HttpStatusCode.OK) })
        val failure = searchFailure(client) as ProviderServiceException
        assertEquals(ProviderFailureKind.INVALID_RESPONSE, failure.diagnostic.kind)
        assertFalse(failure.message.orEmpty().contains("private-response"))
    }

    @Test fun actualNotFoundRemainsNoResults() = runTest {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })
        assertTrue(searchFailure(client) is NoTrackFoundException)
    }

    private suspend fun searchFailure(client: HttpClient): Throwable = runCatching {
        service(client).getSongInfo(SongInfo("SALTARE", "Billyrrom"), provider = Providers.MUSIXMATCH)
    }.exceptionOrNull()!!

    private fun service(client: HttpClient): LyricsProviderService {
        val store = object : SpotifyCredentialStore {
            override fun read(): String? = null
            override fun save(value: String) = Unit
            override fun clear() = Unit
        }
        return LyricsProviderService(SpotifyAPI(store, client), MusixmatchAPI(client))
    }
}
