package pl.lambada.songsync.util

import io.ktor.client.plugins.ResponseException
import kotlinx.serialization.SerializationException
import java.io.IOException

enum class ProviderOperation { SEARCH, LYRICS }
enum class ProviderFailureKind { NETWORK, RATE_LIMITED, UNAVAILABLE, INVALID_RESPONSE }

/** Contains only fixed labels and an HTTP status, never request or response data. */
data class ProviderDiagnostic(
    val provider: Providers,
    val operation: ProviderOperation,
    val kind: ProviderFailureKind,
    val httpStatus: Int? = null,
) {
    val code: String = "${provider.name}-${operation.name}-${httpStatus ?: kind.name}"

    fun toReport(appVersion: String): String = listOf(
        "SongSync lyrics diagnostic",
        "App version: $appVersion",
        "Code: $code",
        "Provider: ${provider.name}",
        "Operation: ${operation.name}",
        "Category: ${kind.name}",
        "HTTP status: ${httpStatus ?: "none"}",
        "No request URLs, credentials, headers, response bodies, or device identifiers are included.",
    ).joinToString("\n")
}

class ProviderServiceException(val diagnostic: ProviderDiagnostic) : Exception(
    when (diagnostic.kind) {
        ProviderFailureKind.NETWORK -> "${diagnostic.provider.displayName} could not be reached. Check your connection or try another provider."
        ProviderFailureKind.RATE_LIMITED -> "${diagnostic.provider.displayName} is rate limiting requests. Try again later."
        ProviderFailureKind.UNAVAILABLE -> "${diagnostic.provider.displayName} is temporarily unavailable. Try again later or choose another provider."
        ProviderFailureKind.INVALID_RESPONSE -> "${diagnostic.provider.displayName} returned an unexpected response."
    } + "\nDiagnostic: ${diagnostic.code}"
)

/** Used when a provider returns an HTTP error without throwing a Ktor exception. */
class ProviderHttpException(val status: Int) : Exception()

fun providerFailure(provider: Providers, operation: ProviderOperation, error: Exception): ProviderServiceException {
    val status = when (error) {
        is ProviderHttpException -> error.status
        is ResponseException -> error.response.status.value
        else -> null
    }
    val kind = when {
        status == 429 -> ProviderFailureKind.RATE_LIMITED
        status != null -> ProviderFailureKind.UNAVAILABLE
        error is IOException -> ProviderFailureKind.NETWORK
        error is SerializationException -> ProviderFailureKind.INVALID_RESPONSE
        else -> ProviderFailureKind.UNAVAILABLE
    }
    return ProviderServiceException(ProviderDiagnostic(provider, operation, kind, status))
}
