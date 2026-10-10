package com.app.shadetube.ui.broadcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.app.shadetube.ui.nav.ScrollToTopEffect
import com.app.shadetube.ui.nav.Tab
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.shadetube.R
import com.app.shadetube.internetradio.InternetRadio
import com.app.shadetube.internetradio.RadioCountry
import com.app.shadetube.internetradio.RadioGenres
import com.app.shadetube.internetradio.RadioModel
import com.app.shadetube.ui.components.ErrorState
import com.app.shadetube.ui.components.SearchField
import com.app.shadetube.ui.components.SectionHeader
import com.app.shadetube.ui.components.ShadeFilterChip
import com.app.shadetube.ui.components.ShadeTextButton
import com.app.shadetube.ui.components.TrackRowSkeleton
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.nav.Navigator
import com.app.shadetube.ui.nav.RadioListKind
import com.app.shadetube.ui.nav.Screen

private const val RECENT_SHOWN = 4
private const val POPULAR_SHOWN = 15
private const val COUNTRIES_SHOWN = 8

/** Radio page (SCREENS 16). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RadioHome(contentPadding: PaddingValues) {
    val nav = LocalNavigator.current
    val vm: RadioHomeViewModel = viewModel()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val popular by vm.popular.collectAsStateWithLifecycle()
    val countries by vm.countries.collectAsStateWithLifecycle()
    val now by vm.nowPlaying.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var allCountries by rememberSaveable { mutableStateOf(false) }

    val favTitle = stringResource(R.string.radio_favorites)
    val recentTitle = stringResource(R.string.radio_recent)
    val homeName = InternetRadio.labels.countryName(vm.homeCountry, vm.homeCountry)
    val popularTitle = stringResource(R.string.radio_popular)

    val listState = rememberLazyListState()
    ScrollToTopEffect(Tab.Broadcast, listState)
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
    ) {
        item("search") {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                hint = stringResource(R.string.radio_search_hint),
                onSearch = { q -> if (q.isNotBlank()) nav.navigate(Screen.RadioList(RadioListKind.Search, q.trim(), "")) },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        if (favorites.isNotEmpty()) {
            item("fav-h") {
                SectionGap()
                SectionHeader(
                    favTitle, actionLabel = stringResource(R.string.home_see_all),
                    onAction = { nav.navigate(Screen.RadioList(RadioListKind.Favorites)) },
                )
            }
            item("fav") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(favorites, key = { _, s -> s.uuid }) { i, s ->
                        val current = now.trackId == RadioModel.stationId(s.uuid)
                        StationCircle(
                            s, current = current, playing = current && now.playing,
                            onClick = { vm.play(favorites, i, "iradio:favorites", favTitle) },
                        )
                    }
                }
            }
        }

        if (recent.isNotEmpty()) {
            item("recent-h") {
                SectionGap()
                SectionHeader(
                    recentTitle,
                    actionLabel = if (recent.size > RECENT_SHOWN) stringResource(R.string.home_see_all) else null,
                    onAction = { nav.navigate(Screen.RadioList(RadioListKind.Recent)) },
                )
            }
            val shown = recent.take(RECENT_SHOWN)
            itemsIndexed(shown, key = { _, s -> "recent:" + s.uuid }) { i, s ->
                StationRow(
                    s, favorite = s.uuid in favoriteIds, now = now,
                    onClick = { vm.play(recent, i, "iradio:recent", recentTitle) },
                    onToggleFavorite = { vm.toggleFavorite(s) },
                )
            }
        }

        item("genres") {
            SectionGap()
            SectionHeader(stringResource(R.string.radio_genres))
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (tag in RadioGenres.featured) {
                    val label = RadioModel.genreLabel(tag, InternetRadio.labels)
                    ShadeFilterChip(label, selected = false, onClick = { nav.navigate(Screen.RadioList(RadioListKind.Tag, tag, label)) })
                }
            }
        }

        countriesSection(countries, allCountries, onToggleAll = { allCountries = !allCountries }, nav = nav, onRetry = vm::load)

        item("popular-h") {
            SectionGap()
            SectionHeader(
                popularTitle, subtitle = homeName,
                actionLabel = stringResource(R.string.home_see_all),
                onAction = { nav.navigate(Screen.RadioList(RadioListKind.Country, vm.homeCountry, "$popularTitle · $homeName")) },
            )
        }
        when (val p = popular) {
            Load.Loading -> skeletonItems("pop", 6) { TrackRowSkeleton() }
            is Load.Failed -> item("popular-err") { ErrorState(onRetry = vm::load) }
            is Load.Ready -> {
                val shown = p.value.take(POPULAR_SHOWN)
                itemsIndexed(shown, key = { _, s -> "pop:" + s.uuid }) { i, s ->
                    StationRow(
                        s, favorite = s.uuid in favoriteIds, now = now,
                        onClick = { vm.play(p.value, i, "iradio:country:${vm.homeCountry}", popularTitle) },
                        onToggleFavorite = { vm.toggleFavorite(s) },
                    )
                }
            }
        }
        item("world") {
            Spacer(Modifier.height(8.dp))
            LinkRow(R.drawable.ic_radio, stringResource(R.string.broadcast_radio_world_popular), { nav.navigate(Screen.RadioList(RadioListKind.Top)) })
        }
    }
}

internal fun LazyListScope.skeletonItems(prefix: String, count: Int, content: @Composable () -> Unit) {
    items(count, key = { "$prefix-sk$it" }) { content() }
}

private fun LazyListScope.countriesSection(
    countries: Load<List<RadioCountry>>,
    all: Boolean,
    onToggleAll: () -> Unit,
    nav: Navigator,
    onRetry: () -> Unit,
) {
    item("countries-h") {
        SectionGap()
        val ready = countries as? Load.Ready
        SectionHeader(
            stringResource(R.string.radio_countries),
            actionLabel = if (ready != null && ready.value.size > COUNTRIES_SHOWN) {
                stringResource(if (all) R.string.broadcast_show_less else R.string.home_see_all)
            } else null,
            onAction = onToggleAll,
        )
    }
    when (countries) {
        Load.Loading -> item("countries-sk") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CountryTileSkeleton(Modifier.weight(1f))
                        CountryTileSkeleton(Modifier.weight(1f))
                    }
                }
            }
        }
        is Load.Failed -> item("countries-err") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                ShadeTextButton(stringResource(R.string.action_retry), onRetry)
            }
        }
        is Load.Ready -> {
            val shown = if (all) countries.value else countries.value.take(COUNTRIES_SHOWN)
            val rows = shown.chunked(2)
            itemsIndexed(rows, key = { _, r -> "c:" + r.joinToString(",") { it.code } }) { _, row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (c in row) {
                        CountryTile(
                            c,
                            onClick = { nav.navigate(Screen.RadioList(RadioListKind.Country, c.code, InternetRadio.labels.countryName(c.code, c.name))) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SectionGap() {
    Spacer(Modifier.height(20.dp))
}
