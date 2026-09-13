package pl.lambada.songsync.domain.model.lyrics_providers.spotify

import kotlinx.serialization.Serializable

@Serializable
data class TrackSearchResult(
    val data: Data,
)

@Serializable
data class Data(
    val searchV2: SearchV2
)

@Serializable
data class SearchV2(
    val tracksV2: TracksV2,
)

@Serializable
data class TracksV2(
    val items: List<TrackItem> = emptyList(),
)

@Serializable
data class TrackItem(
    val item: Item,
)

@Serializable
data class Item(
    val data: TrackData
)

@Serializable
data class TrackData(
    val id: String,
    val name: String,
    val albumOfTrack: AlbumOfTrack,
    val artists: Artists,
)

@Serializable
data class AlbumOfTrack(
    val coverArt: CoverArt,
)

@Serializable
data class CoverArt(
    val sources: List<ImageSource> = emptyList(),
)

@Serializable
data class ImageSource(
    val url: String,
)

@Serializable
data class Artists(
    val items: List<ArtistItem>
)

@Serializable
data class ArtistItem(
    val profile: Profile,
)

@Serializable
data class Profile(
    val name: String,
)
