package pl.lambada.songsync.util

class NoTrackFoundException : Exception()
class InternalErrorException(msg: String) : Exception(msg)
class EmptyQueryException : Exception()

enum class SpotifyOperation(val code: String) {
    BOOTSTRAP("BOOTSTRAP"),
    SERVER_TIME("TIME"),
    ACCESS_TOKEN("ACCESS"),
    CLIENT_TOKEN("CLIENT"),
    TRACK_SEARCH("SEARCH"),
    LYRICS_REQUEST("LYRICS"),
}

enum class SpotifyFailureKind(val code: String) {
    NETWORK("NETWORK"),
    HTTP("HTTP"),
    PARSE("PARSE"),
    AUTHENTICATION("AUTH"),
    RATE_LIMITED("429"),
    NO_LYRICS("NO-LYRICS"),
    UNSYNCHRONIZED("UNSYNCED"),
    INVALID_CLIENT_TOKEN("INVALID-CLIENT"),
    UNEXPECTED("UNEXPECTED"),
}

/** Privacy-safe failure metadata. No server-provided text or request data is retained. */
data class SpotifyDiagnostic(
    val operation: SpotifyOperation,
    val kind: SpotifyFailureKind,
    val httpStatus: Int? = null,
) {
    val stage: String
        get() = if (kind == SpotifyFailureKind.PARSE) "RESPONSE_PARSE" else operation.name

    val code: String
        get() = buildString {
            append("SPOTIFY-")
            append(operation.code)
            append('-')
            append(httpStatus ?: kind.code)
            if (kind == SpotifyFailureKind.INVALID_CLIENT_TOKEN) append("-INVALID-CLIENT")
        }

    fun toReport(appVersion: String): String = listOf(
        "SongSync Spotify diagnostic",
        "App version: $appVersion",
        "Code: $code",
        "Stage: $stage",
        "Operation: ${operation.name}",
        "Category: ${kind.name}",
        "HTTP status: ${httpStatus ?: "none"}",
        "No credentials, tokens, headers, response bodies, or device identifiers are included.",
    ).joinToString("\n")
}

data class SpotifyConnectionTestReport(
    val passed: List<SpotifyOperation>,
    val failure: SpotifyProviderException? = null,
) {
    fun toReport(appVersion: String): String = buildString {
        appendLine("SongSync Spotify connection test")
        appendLine("App version: $appVersion")
        appendLine("Passed: ${passed.joinToString(", ") { it.name }}")
        if (failure == null) {
            appendLine("Code: SPOTIFY-CONNECTION-OK")
        } else {
            append(failure.diagnostic.toReport(appVersion))
        }
    }.trimEnd()
}

sealed class SpotifyProviderException(
    private val userMessage: String,
    val diagnostic: SpotifyDiagnostic,
) : Exception("$userMessage\nDiagnostic: ${diagnostic.code}")

class SpotifyAuthenticationRequiredException(
    diagnostic: SpotifyDiagnostic = SpotifyDiagnostic(
        SpotifyOperation.ACCESS_TOKEN,
        SpotifyFailureKind.AUTHENTICATION,
    ),
) : SpotifyProviderException("Connect Spotify Lyrics in Settings.", diagnostic)

class SpotifySessionExpiredException(
    diagnostic: SpotifyDiagnostic = SpotifyDiagnostic(
        SpotifyOperation.ACCESS_TOKEN,
        SpotifyFailureKind.AUTHENTICATION,
    ),
) : SpotifyProviderException(
    "Your Spotify session has expired. Connect it again in Settings.",
    diagnostic,
)

class SpotifyLyricsNotFoundException(
    diagnostic: SpotifyDiagnostic = SpotifyDiagnostic(
        SpotifyOperation.LYRICS_REQUEST,
        SpotifyFailureKind.NO_LYRICS,
        404,
    ),
) : SpotifyProviderException("This track has no lyrics.", diagnostic)

class SpotifyUnsyncedLyricsException(
    diagnostic: SpotifyDiagnostic = SpotifyDiagnostic(
        SpotifyOperation.LYRICS_REQUEST,
        SpotifyFailureKind.UNSYNCHRONIZED,
    ),
) : SpotifyProviderException("Spotify lyrics are not synchronized.", diagnostic)

class SpotifyRateLimitException(
    diagnostic: SpotifyDiagnostic = SpotifyDiagnostic(
        SpotifyOperation.ACCESS_TOKEN,
        SpotifyFailureKind.RATE_LIMITED,
        429,
    ),
) : SpotifyProviderException(
    "Spotify is rate limiting requests. Please try again later.",
    diagnostic,
)

class SpotifyServiceException(
    diagnostic: SpotifyDiagnostic = SpotifyDiagnostic(
        SpotifyOperation.ACCESS_TOKEN,
        SpotifyFailureKind.UNEXPECTED,
    ),
) : SpotifyProviderException("Spotify Lyrics is temporarily unavailable.", diagnostic)
