package pl.lambada.songsync.data.remote.lyrics_providers

import java.text.Normalizer
import java.util.Locale

internal fun isLikelyTrackMatch(
    requestedTitle: String?,
    requestedArtist: String?,
    candidateTitle: String?,
    candidateArtist: String?,
): Boolean {
    val expectedTitle = normalizeTitle(requestedTitle)
    val actualTitle = normalizeTitle(candidateTitle)
    if (expectedTitle.isBlank() || actualTitle.isBlank() || expectedTitle != actualTitle) return false

    val expectedArtists = normalizeArtists(requestedArtist)
    val actualArtists = normalizeArtists(candidateArtist)
    if (expectedArtists.isEmpty() || actualArtists.isEmpty()) return false
    return expectedArtists.any { expected -> actualArtists.any { actual -> expected == actual } }
}

private fun normalizeTitle(value: String?): String = normalize(value)
    .replace(FEATURING_SUFFIX, "")
    .replace(VERSION_SUFFIX, "")
    .trim()

private fun normalizeArtists(value: String?): Set<String> = normalize(value)
    .replace(FEATURING_SUFFIX, "")
    .replace(" featuring ", ",")
    .replace(" feat ", ",")
    .replace(" ft ", ",")
    .split(',', '&', '/', ';')
    .map(String::trim)
    .filter(String::isNotBlank)
    .toSet()

private fun normalize(value: String?): String = Normalizer
    .normalize(value.orEmpty(), Normalizer.Form.NFKD)
    .replace(COMBINING_MARKS, "")
    .lowercase(Locale.ROOT)
    .replace("feat.", "feat")
    .replace("ft.", "ft")
    .replace(NON_WORD, " ")
    .replace(WHITESPACE, " ")
    .trim()

private val COMBINING_MARKS = Regex("\\p{M}+")
private val NON_WORD = Regex("[^\\p{L}\\p{N},&/;()\\[\\] ]+")
private val WHITESPACE = Regex("\\s+")
private val FEATURING_SUFFIX = Regex("\\s*[\\[(]\\s*(feat|ft|featuring)\\s+.*[\\])]\\s*$")
private val VERSION_SUFFIX = Regex("\\s*[\\[(]\\s*(remaster(ed)?|live|radio edit|version).*?[\\])]\\s*$")
