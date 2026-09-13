package pl.lambada.songsync.util

class NoTrackFoundException : Exception()
class InternalErrorException(msg: String) : Exception(msg)
class EmptyQueryException : Exception()

sealed class SpotifyProviderException(message: String) : Exception(message)
class SpotifyAuthenticationRequiredException :
    SpotifyProviderException("Connect Spotify Lyrics in Settings.")
class SpotifySessionExpiredException :
    SpotifyProviderException("Your Spotify session has expired. Connect it again in Settings.")
class SpotifyLyricsNotFoundException :
    SpotifyProviderException("This track has no lyrics.")
class SpotifyUnsyncedLyricsException :
    SpotifyProviderException("Spotify lyrics are not synchronized.")
class SpotifyRateLimitException :
    SpotifyProviderException("Spotify is rate limiting requests. Please try again later.")
class SpotifyServiceException :
    SpotifyProviderException("Spotify Lyrics is temporarily unavailable.")
