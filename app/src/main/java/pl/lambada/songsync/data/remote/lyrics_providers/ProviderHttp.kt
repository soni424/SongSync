package pl.lambada.songsync.data.remote.lyrics_providers

import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import pl.lambada.songsync.util.Providers
import java.text.SimpleDateFormat
import java.util.Locale

class ProviderRequestException(
    val failure: LyricsFailure,
) : Exception()

fun HttpResponse.requireProviderSuccess(provider: Providers) {
    providerFailureForStatus(
        status = status.value,
        retryAfter = headers[HttpHeaders.RetryAfter],
        provider = provider,
    )?.let { throw ProviderRequestException(it) }
}

internal fun providerFailureForStatus(
    status: Int,
    retryAfter: String?,
    provider: Providers,
    nowMillis: Long = System.currentTimeMillis(),
): LyricsFailure? = when (status) {
    in 200..299 -> null
    401, 403 -> LyricsFailure.Authentication(provider)
    429 -> LyricsFailure.RateLimited(provider, retryAfterSeconds(retryAfter, nowMillis))
    else -> LyricsFailure.ProviderUnavailable(provider)
}

private fun retryAfterSeconds(value: String?, nowMillis: Long): Long? {
    val raw = value?.trim() ?: return null
    raw.toLongOrNull()?.let { return it.coerceAtLeast(0) }
    val dateMillis = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).parse(raw)?.time
    }.getOrNull() ?: return null
    return ((dateMillis - nowMillis) / 1000).coerceAtLeast(0)
}
