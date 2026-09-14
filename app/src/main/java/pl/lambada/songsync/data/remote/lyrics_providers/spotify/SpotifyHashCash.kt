package pl.lambada.songsync.data.remote.lyrics_providers.spotify

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale

private const val HASH_CASH_TIMEOUT_NANOS = 5_000_000_000L

/** Solves the proof-of-work challenge returned by Spotify's client-token service. */
internal fun solveSpotifyHashCash(
    prefixHex: String,
    length: Int,
    nanoTime: () -> Long = System::nanoTime,
): String {
    require(length in 0..63) { "Unsupported hash-cash length" }
    require(prefixHex.length % 2 == 0 && prefixHex.matches(Regex("[0-9A-Fa-f]+"))) {
        "Invalid hash-cash prefix"
    }

    val prefix = prefixHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val sha1 = MessageDigest.getInstance("SHA-1")
    val initialDigest = sha1.digest(byteArrayOf())
    val target = readLong(initialDigest, 12)
    val startedAt = nanoTime()
    var counter = 0L

    while (nanoTime() - startedAt < HASH_CASH_TIMEOUT_NANOS) {
        val suffix = ByteBuffer.allocate(16)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(target + counter)
            .putLong(counter)
            .array()
        sha1.reset()
        sha1.update(prefix)
        val digest = sha1.digest(suffix)
        if (java.lang.Long.numberOfTrailingZeros(readLong(digest, 12)) >= length) {
            return suffix.joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xff) }
        }
        counter++
    }

    throw IllegalStateException("Spotify hash-cash challenge timed out")
}

private fun readLong(bytes: ByteArray, offset: Int): Long =
    ByteBuffer.wrap(bytes, offset, Long.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).long
