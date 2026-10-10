package com.app.shadetube.ui.broadcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.shadetube.internetradio.InternetRadio
import com.app.shadetube.internetradio.RadioCountry
import com.app.shadetube.internetradio.Station
import com.app.shadetube.internetradio.StationPage
import com.app.shadetube.ui.nav.RadioListKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Radio tab (SCREENS 16): favorites, recently played, genres, countries, popular stations of the home country. */
class RadioHomeViewModel : ViewModel() {
    private val store = InternetRadio.store

    val favorites: StateFlow<List<Station>> = store.favorites
    val recent: StateFlow<List<Station>> = store.recent
    val favoriteIds: StateFlow<Set<String>> = store.favorites.map { l -> l.mapTo(HashSet()) { it.uuid } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, store.favoritesNow().mapTo(HashSet()) { it.uuid })
    val nowPlaying: StateFlow<NowPlayingInfo> =
        BroadcastPlayback.nowPlaying().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingInfo())

    val homeCountry: String = InternetRadio.homeCountry()

    private val _popular = MutableStateFlow<Load<List<Station>>>(Load.Loading)
    val popular: StateFlow<Load<List<Station>>> = _popular.asStateFlow()

    private val _countries = MutableStateFlow<Load<List<RadioCountry>>>(Load.Loading)
    val countries: StateFlow<Load<List<RadioCountry>>> = _countries.asStateFlow()

    init {
        load()
        viewModelScope.launch { InternetRadio.refreshFavorites() }
    }

    fun load() {
        if (_popular.value !is Load.Ready) {
            _popular.value = Load.Loading
            viewModelScope.launch {
                _popular.value = try {
                    Load.Ready(InternetRadio.byCountry(homeCountry, limit = 30).stations)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Load.Failed(e)
                }
            }
        }
        if (_countries.value !is Load.Ready) {
            _countries.value = Load.Loading
            viewModelScope.launch {
                _countries.value = try {
                    val all = InternetRadio.countries().filter { it.stationCount > 0 }.sortedByDescending { it.stationCount }
                    val home = all.firstOrNull { it.code == homeCountry }
                    Load.Ready(if (home == null) all else listOf(home) + (all - home))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Load.Failed(e)
                }
            }
        }
    }

    fun toggleFavorite(s: Station) = store.toggleFavorite(s)

    fun play(list: List<Station>, index: Int, contextUri: String, contextName: String) =
        BroadcastPlayback.playStations(list, index, contextUri, contextName)
}

/** A station list: top / genre / country / search (paged, 50 per page) or favorites / recently played (the store). */
class RadioListViewModel(val kind: RadioListKind, val value: String) : ViewModel() {
    data class State(
        val stations: List<Station> = emptyList(),
        val loading: Boolean = true,
        val more: Boolean = false,
        val nextOffset: Int = 0,
        val error: Throwable? = null,
    )

    private val store = InternetRadio.store
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val favoriteIds: StateFlow<Set<String>> = store.favorites.map { l -> l.mapTo(HashSet()) { it.uuid } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, store.favoritesNow().mapTo(HashSet()) { it.uuid })
    val nowPlaying: StateFlow<NowPlayingInfo> =
        BroadcastPlayback.nowPlaying().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingInfo())

    private var job: Job? = null

    val contextUri: String get() = "iradio:${kind.name.lowercase()}:$value"

    init {
        when (kind) {
            RadioListKind.Favorites -> viewModelScope.launch {
                store.favorites.collect { l -> _state.value = State(l, loading = false) }
            }
            RadioListKind.Recent -> viewModelScope.launch {
                store.recent.collect { l -> _state.value = State(l, loading = false) }
            }
            else -> loadMore()
        }
    }

    private suspend fun page(offset: Int): StationPage = when (kind) {
        RadioListKind.Top -> InternetRadio.topClick(PAGE, offset)
        RadioListKind.Tag -> InternetRadio.byTag(value, PAGE, offset)
        RadioListKind.Country -> InternetRadio.byCountry(value, PAGE, offset)
        RadioListKind.Search -> InternetRadio.byName(value, PAGE, offset)
        RadioListKind.Favorites, RadioListKind.Recent -> StationPage()
    }

    /** The first page, or the next one when the list says there is more. */
    fun loadMore() {
        if (kind == RadioListKind.Favorites || kind == RadioListKind.Recent) return
        if (job?.isActive == true) return
        val s = _state.value
        if (s.stations.isNotEmpty() && !s.more) return
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                val p = page(s.nextOffset)
                _state.update { cur ->
                    val seen = cur.stations.mapTo(HashSet()) { it.uuid }
                    cur.copy(
                        stations = cur.stations + p.stations.filter { seen.add(it.uuid) },
                        loading = false, more = p.more, nextOffset = p.nextOffset,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e) }
            }
        }
    }

    fun toggleFavorite(s: Station) = store.toggleFavorite(s)

    fun removeRecent(s: Station) = store.removeRecent(s.uuid)

    fun play(index: Int, contextName: String) =
        BroadcastPlayback.playStations(_state.value.stations, index, contextUri, contextName)

    companion object {
        const val PAGE = 50
    }
}
