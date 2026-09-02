package pl.lambada.songsync.data.remote.lyrics_providers

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.util.Providers

class LyricsCoordinatorTest {
    private val request = LyricsRequest("Clock Hands", "Billyrrom")
    private val song = SongInfo("Clock Hands", "Billyrrom")

    @Test
    fun `falls back to LRCLib when preferred provider fails`() = runTest {
        val coordinator = LyricsCoordinator(
            listOf(
                fakeProvider(Providers.SPOTIFY, ProviderResult.Failure(LyricsFailure.InvalidResponse(Providers.SPOTIFY))),
                fakeProvider(
                    Providers.LRCLIB,
                    ProviderResult.Success(song, "[00:01.000]Clock hands", LyricsTiming.SYNCED)
                )
            )
        )

        val outcome = coordinator.lookup(request, Providers.SPOTIFY)

        assertTrue(outcome is LyricsLookupOutcome.Success)
        assertEquals(Providers.LRCLIB, (outcome as LyricsLookupOutcome.Success).document.provider)
    }

    @Test
    fun `continues past unsynced lyrics to find synced lyrics`() = runTest {
        val coordinator = LyricsCoordinator(
            listOf(
                fakeProvider(
                    Providers.QQMUSIC,
                    ProviderResult.Success(song, "Plain lyrics", LyricsTiming.UNSYNCED)
                ),
                fakeProvider(
                    Providers.LRCLIB,
                    ProviderResult.Success(song, "[00:01.000]Synced", LyricsTiming.SYNCED)
                )
            )
        )

        val outcome = coordinator.lookup(request, Providers.QQMUSIC)

        assertTrue(outcome is LyricsLookupOutcome.Success)
        outcome as LyricsLookupOutcome.Success
        assertEquals(Providers.LRCLIB, outcome.document.provider)
        assertEquals(LyricsTiming.SYNCED, outcome.document.timing)
    }

    @Test
    fun `uses unsynced lyrics only after synced sources are exhausted`() = runTest {
        val coordinator = LyricsCoordinator(
            listOf(
                fakeProvider(
                    Providers.QQMUSIC,
                    ProviderResult.Success(song, "Plain lyrics", LyricsTiming.UNSYNCED)
                ),
                fakeProvider(Providers.LRCLIB, ProviderResult.NoMatch)
            )
        )

        val outcome = coordinator.lookup(request, Providers.QQMUSIC)

        assertTrue(outcome is LyricsLookupOutcome.Success)
        assertEquals(LyricsTiming.UNSYNCED, (outcome as LyricsLookupOutcome.Success).document.timing)
    }

    @Test
    fun `reports no lyrics only when every reachable provider has no match`() = runTest {
        val coordinator = LyricsCoordinator(
            listOf(
                fakeProvider(Providers.SPOTIFY, ProviderResult.NoMatch),
                fakeProvider(Providers.LRCLIB, ProviderResult.NoMatch)
            )
        )

        val outcome = coordinator.lookup(request, Providers.SPOTIFY)

        assertTrue(outcome is LyricsLookupOutcome.Failed)
        assertTrue((outcome as LyricsLookupOutcome.Failed).failure is LyricsFailure.NoLyrics)
    }

    @Test
    fun `does not call disabled providers`() = runTest {
        val coordinator = LyricsCoordinator(
            listOf(
                fakeProvider(
                    Providers.MUSIXMATCH,
                    ProviderResult.Success(song, "Wrong source", LyricsTiming.SYNCED),
                    enabled = false
                ),
                fakeProvider(
                    Providers.LRCLIB,
                    ProviderResult.Success(song, "[00:01.000]Right source", LyricsTiming.SYNCED)
                )
            )
        )

        val outcome = coordinator.lookup(request, Providers.MUSIXMATCH)

        assertTrue(outcome is LyricsLookupOutcome.Success)
        assertEquals(Providers.LRCLIB, (outcome as LyricsLookupOutcome.Success).document.provider)
    }

    @Test
    fun `cancellation stops fallback processing`() = runTest {
        val waitingProvider = object : LyricsProvider {
            override val id = Providers.SPOTIFY
            override val isEnabled = true
            override suspend fun lookup(request: LyricsRequest): ProviderResult = awaitCancellation()
        }
        val coordinator = LyricsCoordinator(
            listOf(
                waitingProvider,
                fakeProvider(
                    Providers.LRCLIB,
                    ProviderResult.Success(song, "[00:01.000]Should not be reached", LyricsTiming.SYNCED)
                )
            )
        )

        val lookup = async { coordinator.lookup(request, Providers.SPOTIFY) }
        testScheduler.runCurrent()
        lookup.cancelAndJoin()

        assertTrue(lookup.isCancelled)
    }

    @Test
    fun `reports offline only when the device network is not validated`() = runTest {
        val coordinator = LyricsCoordinator(
            providers = listOf(
                fakeProvider(
                    Providers.LRCLIB,
                    ProviderResult.Failure(LyricsFailure.DnsFailure(Providers.LRCLIB))
                )
            ),
            hasValidatedNetwork = { false },
        )

        val outcome = coordinator.lookup(request, Providers.LRCLIB)

        assertTrue(outcome is LyricsLookupOutcome.Failed)
        assertEquals(LyricsFailure.Offline, (outcome as LyricsLookupOutcome.Failed).failure)
    }

    @Test
    fun `preserves a single provider failure category`() = runTest {
        val failure = LyricsFailure.Authentication(Providers.SPOTIFY)
        val coordinator = LyricsCoordinator(
            providers = listOf(fakeProvider(Providers.SPOTIFY, ProviderResult.Failure(failure))),
            hasValidatedNetwork = { true },
        )

        val outcome = coordinator.lookup(request, Providers.SPOTIFY)

        assertEquals(failure, (outcome as LyricsLookupOutcome.Failed).failure)
    }

    @Test
    fun `does not label authentication failure as offline`() = runTest {
        val failure = LyricsFailure.Authentication(Providers.SPOTIFY)
        val coordinator = LyricsCoordinator(
            providers = listOf(fakeProvider(Providers.SPOTIFY, ProviderResult.Failure(failure))),
            hasValidatedNetwork = { false },
        )

        val outcome = coordinator.lookup(request, Providers.SPOTIFY)

        assertEquals(failure, (outcome as LyricsLookupOutcome.Failed).failure)
    }

    @Test
    fun `rejects an empty query without contacting providers`() = runTest {
        var contacted = false
        val provider = object : LyricsProvider {
            override val id = Providers.LRCLIB
            override val isEnabled = true
            override suspend fun lookup(request: LyricsRequest): ProviderResult {
                contacted = true
                return ProviderResult.NoMatch
            }
        }

        val outcome = LyricsCoordinator(listOf(provider)).lookup(
            LyricsRequest("", "Billyrrom"),
            Providers.LRCLIB,
        )

        assertEquals(LyricsFailure.InvalidQuery, (outcome as LyricsLookupOutcome.Failed).failure)
        assertTrue(!contacted)
    }

    private fun fakeProvider(
        provider: Providers,
        result: ProviderResult,
        enabled: Boolean = true
    ) = object : LyricsProvider {
        override val id = provider
        override val isEnabled = enabled
        override suspend fun lookup(request: LyricsRequest): ProviderResult = result
    }
}
