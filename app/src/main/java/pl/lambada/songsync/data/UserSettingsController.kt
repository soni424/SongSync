package pl.lambada.songsync.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import pl.lambada.songsync.domain.model.SortOrders
import pl.lambada.songsync.domain.model.SortValues
import pl.lambada.songsync.util.Providers

class UserSettingsController private constructor(
    private val dataStore: DataStore<Preferences>,
    initial: Preferences,
) {
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storedProvider = Providers.entries
        .find { it.displayName == initial[selectedProviderKey] }
        ?: Providers.SPOTIFY

    var embedLyricsIntoFiles by mutableStateOf(initial[embedKey] ?: false)
        private set
    var passedInit by mutableStateOf(initial[passedInitKey] ?: false)
        private set
    var selectedProvider by mutableStateOf(
        storedProvider.takeIf(Providers::isAvailable) ?: Providers.LRCLIB
    )
        private set
    var blacklistedFolders by mutableStateOf(
        initial[blacklistedFoldersKey].orEmpty().split(',').filter(String::isNotBlank)
    )
        private set
    var hideLyrics by mutableStateOf(initial[hideLyricsKey] ?: false)
        private set
    var includeTranslation by mutableStateOf(initial[includeTranslationKey] ?: false)
        private set
    var includeRomanization by mutableStateOf(initial[includeRomanizationKey] ?: false)
        private set
    var multiPersonWordByWord by mutableStateOf(initial[multiPersonWordByWordKey] ?: true)
        private set
    var unsyncedFallbackMusixmatch by mutableStateOf(initial[unsyncedFallbackMusixmatchKey] ?: true)
        private set
    var pureBlack by mutableStateOf(initial[pureBlackKey] ?: false)
        private set
    var disableMarquee by mutableStateOf(initial[disableMarqueeKey] ?: false)
        private set
    var sdCardPath by mutableStateOf(initial[sdCardPathKey])
        private set
    var showPath by mutableStateOf(initial[showPathKey] ?: false)
        private set
    var directlyModifyTimestamps by mutableStateOf(initial[directlyModifyTimestampsKey] ?: false)
        private set
    var sortOrder by mutableStateOf(
        SortOrders.entries.find { it.queryName == initial[sortOrderKey] } ?: SortOrders.ASCENDING
    )
        private set
    var sortBy by mutableStateOf(
        SortValues.entries.find { it.name == initial[sortByKey] } ?: SortValues.TITLE
    )
        private set

    init {
        if (storedProvider != selectedProvider) persist(selectedProviderKey, selectedProvider.displayName)
    }

    fun updateEmbedLyrics(to: Boolean) {
        embedLyricsIntoFiles = to
        persist(embedKey, to)
    }

    fun updatePassedInit(to: Boolean) {
        passedInit = to
        persist(passedInitKey, to)
    }

    fun updateSelectedProviders(to: Providers) {
        if (!to.isAvailable) return
        selectedProvider = to
        persist(selectedProviderKey, to.displayName)
    }

    fun updateBlacklistedFolders(to: List<String>) {
        blacklistedFolders = to
        persist(blacklistedFoldersKey, to.joinToString(","))
    }

    fun updateHideLyrics(to: Boolean) {
        hideLyrics = to
        persist(hideLyricsKey, to)
    }

    fun updateIncludeTranslation(to: Boolean) {
        includeTranslation = to
        persist(includeTranslationKey, to)
    }

    fun updateIncludeRomanization(to: Boolean) {
        includeRomanization = to
        persist(includeRomanizationKey, to)
    }

    fun updateMultiPersonWordByWord(to: Boolean) {
        multiPersonWordByWord = to
        persist(multiPersonWordByWordKey, to)
    }

    fun updateUnsyncedFallbackMusixmatch(to: Boolean) {
        unsyncedFallbackMusixmatch = to
        persist(unsyncedFallbackMusixmatchKey, to)
    }

    fun updateDisableMarquee(to: Boolean) {
        disableMarquee = to
        persist(disableMarqueeKey, to)
    }

    fun updatePureBlack(to: Boolean) {
        pureBlack = to
        persist(pureBlackKey, to)
    }

    fun updateSdCardPath(to: String) {
        sdCardPath = to
        persist(sdCardPathKey, to)
    }

    fun updateShowPath(to: Boolean) {
        showPath = to
        persist(showPathKey, to)
    }

    fun updateDirectlyModifyTimestamps(to: Boolean) {
        directlyModifyTimestamps = to
        persist(directlyModifyTimestampsKey, to)
    }

    fun updateSortOrder(to: SortOrders) {
        sortOrder = to
        persist(sortOrderKey, to.queryName)
    }

    fun updateSortBy(to: SortValues) {
        sortBy = to
        persist(sortByKey, to.name)
    }

    private fun <T> persist(key: Preferences.Key<T>, value: T) {
        persistenceScope.launch { dataStore.edit { it[key] = value } }
    }

    companion object {
        suspend fun create(dataStore: DataStore<Preferences>): UserSettingsController =
            UserSettingsController(dataStore, dataStore.data.first())
    }
}

private val embedKey = booleanPreferencesKey("embed_lyrics")
private val passedInitKey = booleanPreferencesKey("passed_init")
private val selectedProviderKey = stringPreferencesKey("provider")
private val blacklistedFoldersKey = stringPreferencesKey("blacklist")
private val hideLyricsKey = booleanPreferencesKey("hide_lyrics")
private val includeTranslationKey = booleanPreferencesKey("include_translation")
private val includeRomanizationKey = booleanPreferencesKey("include_romanization")
private val multiPersonWordByWordKey = booleanPreferencesKey("multi_person_word_by_word")
private val unsyncedFallbackMusixmatchKey = booleanPreferencesKey("unsynced_lyrics_fallback")
private val disableMarqueeKey = booleanPreferencesKey("marquee_disable")
private val pureBlackKey = booleanPreferencesKey("pure_black")
private val sdCardPathKey = stringPreferencesKey("sd_card_path")
private val showPathKey = booleanPreferencesKey("show_path")
private val sortOrderKey = stringPreferencesKey("sort_order")
private val sortByKey = stringPreferencesKey("sort_by")
private val directlyModifyTimestampsKey = booleanPreferencesKey("directly_modify_timestamps")
