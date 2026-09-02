package pl.lambada.songsync.data.remote.lyrics_providers

import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.util.Providers

data class LyricsRequest(
    val title: String?,
    val artist: String?,
    val offset: Int = 0,
    val includeTranslation: Boolean = false,
    val includeRomanization: Boolean = false,
    val multiPersonWordByWord: Boolean = false,
    val allowUnsynced: Boolean = true,
)

enum class LyricsTiming { SYNCED, UNSYNCED }

data class LyricsDocument(
    val song: SongInfo,
    val content: String,
    val timing: LyricsTiming,
    val provider: Providers,
)

sealed interface LyricsFailure {
    data object Offline : LyricsFailure
    data class DnsFailure(val provider: Providers) : LyricsFailure
    data class Timeout(val provider: Providers) : LyricsFailure
    data class Authentication(val provider: Providers) : LyricsFailure
    data class RateLimited(val provider: Providers, val retryAfterSeconds: Long? = null) : LyricsFailure
    data class ProviderUnavailable(val provider: Providers? = null) : LyricsFailure
    data class InvalidResponse(val provider: Providers) : LyricsFailure
    data class NoMatch(val provider: Providers) : LyricsFailure
    data class NoLyrics(val providersChecked: Int) : LyricsFailure
    data object StorageFailure : LyricsFailure
    data object Cancelled : LyricsFailure
    data object InvalidQuery : LyricsFailure
}

sealed interface ProviderResult {
    data class Success(
        val song: SongInfo,
        val content: String,
        val timing: LyricsTiming,
    ) : ProviderResult

    data object NoMatch : ProviderResult
    data object NoLyrics : ProviderResult
    data class Failure(val reason: LyricsFailure) : ProviderResult
}

interface LyricsProvider {
    val id: Providers
    val isEnabled: Boolean
    suspend fun lookup(request: LyricsRequest): ProviderResult
}

data class ProviderAttempt(
    val provider: Providers,
    val result: ProviderResult,
)

sealed interface LyricsLookupOutcome {
    data class Success(
        val document: LyricsDocument,
        val attempts: List<ProviderAttempt>,
    ) : LyricsLookupOutcome

    data class Failed(
        val failure: LyricsFailure,
        val attempts: List<ProviderAttempt>,
    ) : LyricsLookupOutcome
}

class LyricsLookupException(
    val failure: LyricsFailure,
    val attempts: List<ProviderAttempt> = emptyList(),
) : Exception()

class LyricsCoordinator(
    providers: List<LyricsProvider>,
    private val hasValidatedNetwork: () -> Boolean = { true },
) {
    private val providersById = providers.associateBy(LyricsProvider::id)

    suspend fun lookup(
        request: LyricsRequest,
        preferredProvider: Providers,
    ): LyricsLookupOutcome {
        if (request.title.isNullOrBlank() || request.artist.isNullOrBlank()) {
            return LyricsLookupOutcome.Failed(LyricsFailure.InvalidQuery, emptyList())
        }

        val order = buildList {
            add(preferredProvider)
            addAll(DEFAULT_FALLBACK_ORDER)
        }.distinct()

        val attempts = mutableListOf<ProviderAttempt>()
        var unsyncedCandidate: LyricsDocument? = null

        for (providerId in order) {
            val provider = providersById[providerId] ?: continue
            if (!provider.isEnabled) continue

            val result = provider.lookup(request)
            attempts += ProviderAttempt(providerId, result)
            when (result) {
                is ProviderResult.Success -> {
                    val document = LyricsDocument(
                        song = result.song,
                        content = result.content,
                        timing = result.timing,
                        provider = providerId,
                    )
                    if (result.timing == LyricsTiming.SYNCED) {
                        return LyricsLookupOutcome.Success(document, attempts)
                    }
                    if (request.allowUnsynced && unsyncedCandidate == null) {
                        unsyncedCandidate = document
                    }
                }

                ProviderResult.NoMatch -> Unit
                ProviderResult.NoLyrics -> Unit
                is ProviderResult.Failure -> Unit
            }
        }

        unsyncedCandidate?.let { return LyricsLookupOutcome.Success(it, attempts) }

        val failures = attempts.mapNotNull { (it.result as? ProviderResult.Failure)?.reason }
        val finalFailure = when {
            attempts.isEmpty() -> LyricsFailure.ProviderUnavailable()
            failures.isEmpty() -> LyricsFailure.NoLyrics(attempts.size)
            !hasValidatedNetwork() && failures.all {
                it is LyricsFailure.DnsFailure ||
                    it is LyricsFailure.Timeout ||
                    it is LyricsFailure.Offline
            } -> LyricsFailure.Offline
            failures.all { it is LyricsFailure.Offline } -> LyricsFailure.Offline
            failures.size == 1 -> failures.first()
            else -> LyricsFailure.ProviderUnavailable()
        }
        return LyricsLookupOutcome.Failed(finalFailure, attempts)
    }

    companion object {
        val DEFAULT_FALLBACK_ORDER = listOf(
            Providers.LRCLIB,
            Providers.QQMUSIC,
            Providers.SPOTIFY,
            Providers.APPLE,
            Providers.NETEASE,
        )
    }
}
