package com.app.shadetube.playback

import androidx.annotation.OptIn
import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.app.shadetube.core.Paths
import com.app.shadetube.core.SettingsStore
import com.app.shadetube.internetradio.InternetRadio
import com.app.shadetube.playback.engine.ExoEngine
import com.app.shadetube.playback.features.Blacklist
import com.app.shadetube.playback.features.CollectionWriter
import com.app.shadetube.playback.features.Radio
import com.app.shadetube.playback.features.RadioSource
import com.app.shadetube.playback.features.Recommender
import com.app.shadetube.playback.features.SleepTimer
import com.app.shadetube.playback.features.SmartShuffle
import com.app.shadetube.playback.features.SponsorBlockController
import com.app.shadetube.playback.service.BrowseLabels
import com.app.shadetube.playback.service.BrowseSource
import com.app.shadetube.playback.service.BrowseTree
import com.app.shadetube.playback.service.LikeHandler
import com.app.shadetube.playback.service.PlaybackServiceConnector
import com.app.shadetube.youtube.MatchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What AppGraph hands to [PlaybackModule.create] (implementations of the playback package's interfaces). */
class PlaybackDeps(
    val matchService: MatchService,
    /** Downloads, then local-library files (asked in this order; downloaded podcast episodes are added after them). */
    val localSources: List<LocalSourceResolver> = emptyList(),
    /** localfiles.ReplayGain (normalisation of local files and downloads). */
    val replayGain: ReplayGainReader? = null,
    /** Spotify radios (song radio + endless playback). */
    val radioSource: RadioSource? = null,
    /** Smart shuffle / "Geliştir" recommendations (stats.SmartMixRecommender). */
    val recommender: Recommender? = null,
    /** "+" on a recommendation: Liked Songs / a playlist (library). */
    val collectionWriter: CollectionWriter? = null,
    /** Android Auto browse tree (library / history). */
    val browse: BrowseSource? = null,
    /** The notification's heart (library). */
    val likes: LikeHandler? = null,
)

/**
 * The playback composition (the C++ initPlaybackFeatures + Player wiring of App): the [Player] singleton on an
 * [ExoEngine], with the kara liste, Spotify radio / endless playback, SponsorBlock, smart shuffle and the sleep timer
 * wired to it, plus what the media session service needs ([browseTree], [likes]).
 *
 * AppGraph calls [create] once from Application.onCreate (so a process started for a media button / Android Auto has
 * the player before PlaybackService.onCreate). Main thread.
 */
@OptIn(UnstableApi::class)
class PlaybackModule private constructor(
    val player: Player,
    val engine: ExoEngine,
    val blacklist: Blacklist,
    val radio: Radio,
    val sponsorBlock: SponsorBlockController,
    val smartShuffle: SmartShuffle,
    val sleepTimer: SleepTimer,
    val browseTree: BrowseTree,
    val likes: LikeHandler?,
    val scope: CoroutineScope,
    val serviceConnector: PlaybackServiceConnector,
) {
    companion object {
        /** The running module (PlaybackService finds the player here). */
        @Volatile
        var instance: PlaybackModule? = null
            private set

        fun create(context: Context, deps: PlaybackDeps): PlaybackModule {
            instance?.let { return it }
            val app = context.applicationContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val engine = ExoEngine(app, deps.replayGain, InternetRadio.userAgent)
            val player = Player(
                engine = engine,
                resolver = MatchServiceResolver(deps.matchService),
                scope = scope,
                sessionFile = { Paths.file("session.json") },
            )
            player.localSources.addAll(deps.localSources)
            player.localSources.add(PodcastPlayback)
            player.directStreams = PodcastPlayback
            player.startPositions = PodcastPlayback
            player.liveStreams = RadioPlayback
            player.qualityProvider = NetworkQuality(app)
            val connector = PlaybackServiceConnector(app)
            player.serviceLink = connector

            val blacklist = Blacklist({ Paths.file("blacklist.json") })
            blacklist.load()
            player.shouldSkip = { blacklist.isBlocked(it) }
            val radio = Radio(player, deps.radioSource, scope, blacklist::isBlocked, isStation = InternetRadio::isStation)
            player.onQueueLow = { radio.endlessCheck(fromPlayer = true) }
            // A block / unblock: the player re-picks its prepared next track, and blocking the rest of the queue may
            // leave it low.
            blacklist.onChanged {
                player.skipRulesChanged()
                radio.endlessCheck()
            }
            val sponsor = SponsorBlockController(player, scope, onSkipped = player::emitNotice)
            val smart = SmartShuffle(player, scope, deps.recommender, deps.collectionWriter, blacklist::isBlocked, InternetRadio::isStation)
            val sleep = SleepTimer(player, scope)
            val module = PlaybackModule(
                player, engine, blacklist, radio, sponsor, smart, sleep, BrowseTree(deps.browse), deps.likes, scope, connector,
            )
            instance = module

            player.observeSettings()
            sponsor.start()
            smart.start()
            sleep.start()
            PodcastPlayback.follow(player, scope)
            RadioPlayback.follow(player, scope)
            // Endless playback turned on: the queue may need more right away.
            scope.launch {
                SettingsStore.flow.map { it.endlessPlayback }.distinctUntilChanged().drop(1).collect { on -> if (on) radio.endlessCheck() }
            }
            player.restoreSession()
            return module
        }
    }

    /**
     * Called when [com.app.shadetube.playback.service.PlaybackService] is destroyed (after the player saved its session):
     * AppGraph flushes the stores there, since the process may be killed right after.
     */
    @Volatile
    var onServiceDestroyed: (() -> Unit)? = null

    /** Labels of the Android Auto folders (strings.xml). */
    fun setBrowseLabels(labels: BrowseLabels) {
        browseTree.labels = labels
    }

    /** Something the media buttons show changed outside the player (e.g. a like from the app): refresh them. */
    val mediaButtonsChanged = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun invalidateMediaButtons() {
        mediaButtonsChanged.tryEmit(Unit)
    }
}
