package pl.lambada.songsync.data.remote.lyrics_providers.spotify

interface SpotifyCredentialStore {
    fun read(): String?
    fun save(value: String)
    fun clear()
}
