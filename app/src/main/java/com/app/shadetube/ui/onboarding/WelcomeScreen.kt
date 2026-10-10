package com.app.shadetube.ui.onboarding

import com.app.shadetube.ui.components.StepDots
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.app.shadetube.AppGraph
import com.app.shadetube.R
import com.app.shadetube.core.appScope
import com.app.shadetube.localfiles.LocalPlatform
import com.app.shadetube.spotify.SessionState
import com.app.shadetube.ui.components.CrescentMark
import com.app.shadetube.ui.components.PrimaryButton
import com.app.shadetube.ui.components.SecondaryButton
import com.app.shadetube.ui.components.ShadeIcon
import com.app.shadetube.ui.components.ShadeTextButton
import com.app.shadetube.ui.components.SmallPillButton
import com.app.shadetube.ui.components.coverGlow
import com.app.shadetube.ui.nav.LocalNavigator
import com.app.shadetube.ui.theme.ShadeMotion
import com.app.shadetube.ui.theme.ShadeTheme
import com.app.shadetube.ui.theme.ShadeTubeThemeOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A runtime permission the onboarding asks for. */
enum class OnboardingPermission(
    @param:DrawableRes val icon: Int,
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
) {
    Notifications(R.drawable.ic_notification, R.string.perm_notifications_title, R.string.perm_notifications_body),
    Audio(R.drawable.ic_music_note, R.string.perm_audio_title, R.string.perm_audio_body);

    val permission: String
        get() = when (this) {
            Notifications -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else ""
            Audio -> LocalPlatform.audioPermission
        }

    fun granted(context: Context): Boolean =
        permission.isEmpty() || ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** What is still to ask on this device (notifications only on 33+). */
        fun missing(context: Context): List<OnboardingPermission> = buildList {
            if (Build.VERSION.SDK_INT >= 33 && !Notifications.granted(context)) add(Notifications)
            if (!Audio.granted(context)) add(Audio)
        }
    }
}

/** Welcome (SCREENS 2): connect Spotify or explore without it, then the permission steps. */
class WelcomeViewModel : ViewModel() {
    data class State(
        val permissionsStep: Boolean = false,
        /** Spotify's login was started from here: when the session becomes LoggedIn the permissions follow. */
        val loginRequested: Boolean = false,
        val permissions: List<OnboardingPermission> = emptyList(),
        val index: Int = 0,
        /** The current permission was refused. */
        val denied: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val session: StateFlow<SessionState> = AppGraph.session.state

    fun loginStarted() = _state.update { it.copy(loginRequested = true) }

    fun startPermissions(missing: List<OnboardingPermission>) =
        _state.update { it.copy(permissionsStep = true, permissions = missing, index = 0, denied = false) }

    fun result(granted: Boolean) {
        val s = _state.value
        val p = s.permissions.getOrNull(s.index) ?: return
        if (granted) {
            if (p == OnboardingPermission.Audio) appScope.launch { runCatching { AppGraph.localLibrary.rescan() } }
            next()
        } else {
            _state.update { it.copy(denied = true) }
        }
    }

    fun next() = _state.update { it.copy(index = it.index + 1, denied = false) }

    val finished: Boolean get() = _state.value.let { it.permissionsStep && it.index >= it.permissions.size }
}

@Composable
fun WelcomeScreen(contentPadding: PaddingValues, onDone: () -> Unit) {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    val vm: WelcomeViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()

    fun toPermissions() {
        val missing = OnboardingPermission.missing(context)
        if (missing.isEmpty()) onDone() else vm.startPermissions(missing)
    }

    // Logged in through the button: on to the permissions.
    LaunchedEffect(session, state.loginRequested) {
        if (session == SessionState.LoggedIn && state.loginRequested && !state.permissionsStep) toPermissions()
    }
    LaunchedEffect(state.index, state.permissionsStep) {
        if (vm.finished) onDone()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(ShadeTheme.extras.surface0)
            .padding(bottom = contentPadding.calculateBottomPadding())
            .systemBarsPadding(),
    ) {
        val enter = ShadeMotion.fadeEnter()
        val exit = ShadeMotion.fadeExit()
        AnimatedContent(
            targetState = state.permissionsStep,
            transitionSpec = { enter togetherWith exit },
            label = "welcome",
        ) { perms ->
            if (!perms) {
                WelcomePage(
                    connecting = session == SessionState.Connecting,
                    onConnect = {
                        if (session == SessionState.LoggedIn) toPermissions() else {
                            vm.loginStarted()
                            nav.startSpotifyLogin()
                        }
                    },
                    onExplore = { toPermissions() },
                )
            } else {
                val p = state.permissions.getOrNull(state.index)
                if (p != null) {
                    PermissionStep(
                        permission = p,
                        index = state.index,
                        count = state.permissions.size,
                        denied = state.denied,
                        onResult = vm::result,
                        onSkip = vm::next,
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomePage(connecting: Boolean, onConnect: () -> Unit, onExplore: () -> Unit) {
    val x = ShadeTheme.extras
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(0.4f), contentAlignment = Alignment.Center) {
            Box(Modifier.size(200.dp).coverGlow(spread = 1.1f), contentAlignment = Alignment.Center) {
                CrescentMark(size = 96.dp)
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(0.6f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.displayMedium, color = x.text1)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.welcome_subtitle), style = MaterialTheme.typography.bodyLarge, color = x.text2)
            Spacer(Modifier.height(32.dp))
            PrimaryButton(
                stringResource(R.string.welcome_connect_spotify), onConnect, Modifier.fillMaxWidth(), loading = connecting,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ShadeIcon(R.drawable.ic_lock, null, tint = x.text3, size = 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.welcome_spotify_note), style = MaterialTheme.typography.bodySmall, color = x.text3)
            }
            Spacer(Modifier.height(20.dp))
            SecondaryButton(stringResource(R.string.welcome_explore_without), onExplore, Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PermissionStep(
    permission: OnboardingPermission,
    index: Int,
    count: Int,
    denied: Boolean,
    onResult: (Boolean) -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    val x = ShadeTheme.extras
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult(it) }
    // Back from the app settings with the permission granted: on to the next step.
    LifecycleResumeEffect(permission, denied) {
        if (denied && permission.granted(context)) onResult(true)
        onPauseOrDispose { }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(24.dp))
        StepDots(index, count, Modifier.align(Alignment.CenterHorizontally))
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(96.dp).clip(RoundedCornerShape(20.dp)).background(x.surface2),
                contentAlignment = Alignment.Center,
            ) { ShadeIcon(permission.icon, null, tint = ShadeTheme.accent.accent, size = 48.dp) }
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(permission.title), style = MaterialTheme.typography.headlineMedium, color = x.text1,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(permission.body), Modifier.widthIn(max = 320.dp), style = MaterialTheme.typography.bodyLarge,
                color = x.text2, textAlign = TextAlign.Center,
            )
            AnimatedVisibility(denied) {
                DeniedCard(onOpenSettings = { context.startActivity(appSettingsIntent(context)) }, modifier = Modifier.padding(top = 24.dp))
            }
        }
        PrimaryButton(
            stringResource(R.string.perm_allow),
            onClick = {
                if (permission.granted(context)) onResult(true) else launcher.launch(permission.permission)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        ShadeTextButton(
            stringResource(if (denied) R.string.onboarding_continue else R.string.perm_later), onSkip,
            Modifier.align(Alignment.CenterHorizontally), color = x.text2,
        )
        Spacer(Modifier.height(16.dp))
    }
}

/** "İzin verilmedi" card with "Ayarları aç". */
@Composable
private fun DeniedCard(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val x = ShadeTheme.extras
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(x.surface2).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShadeIcon(R.drawable.ic_warning, null, tint = x.warning, size = 20.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.perm_denied_title), style = MaterialTheme.typography.titleSmall, color = x.text1)
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.perm_denied_body), style = MaterialTheme.typography.bodyMedium, color = x.text2)
        Spacer(Modifier.height(12.dp))
        SmallPillButton(stringResource(R.string.perm_open_settings), onOpenSettings, icon = R.drawable.ic_settings)
    }
}

private fun appSettingsIntent(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

@Preview(backgroundColor = 0xFF0B0C10, showBackground = true, widthDp = 412, heightDp = 860)
@Composable
private fun WelcomePagePreview() {
    ShadeTubeThemeOf { WelcomePage(connecting = false, onConnect = {}, onExplore = {}) }
}

@Preview(backgroundColor = 0xFF0B0C10, showBackground = true, widthDp = 412, heightDp = 860)
@Composable
private fun PermissionStepPreview() {
    ShadeTubeThemeOf { PermissionStep(OnboardingPermission.Audio, 1, 2, denied = true, onResult = {}, onSkip = {}) }
}
