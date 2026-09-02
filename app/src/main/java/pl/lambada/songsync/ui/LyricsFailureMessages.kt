package pl.lambada.songsync.ui

import android.content.Context
import pl.lambada.songsync.R
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsFailure

fun LyricsFailure.userMessage(context: Context): String = when (this) {
    LyricsFailure.Offline -> context.getString(R.string.offline_local_library_available)
    is LyricsFailure.DnsFailure -> context.getString(R.string.provider_unreachable, provider.displayName)
    is LyricsFailure.Timeout -> context.getString(R.string.provider_timed_out, provider.displayName)
    is LyricsFailure.Authentication -> context.getString(R.string.provider_authentication_failed, provider.displayName)
    is LyricsFailure.RateLimited -> context.getString(R.string.provider_rate_limited, provider.displayName)
    is LyricsFailure.ProviderUnavailable -> provider?.let {
        context.getString(R.string.provider_temporarily_unavailable, it.displayName)
    } ?: context.getString(R.string.some_providers_could_not_be_checked)
    is LyricsFailure.InvalidResponse -> context.getString(R.string.provider_invalid_response, provider.displayName)
    is LyricsFailure.NoMatch -> context.getString(R.string.no_results)
    is LyricsFailure.NoLyrics -> context.getString(R.string.no_lyrics_after_providers, providersChecked)
    LyricsFailure.StorageFailure -> context.getString(R.string.storage_operation_failed)
    LyricsFailure.Cancelled -> context.getString(R.string.cancelled)
    LyricsFailure.InvalidQuery -> context.getString(R.string.invalid_query)
}
