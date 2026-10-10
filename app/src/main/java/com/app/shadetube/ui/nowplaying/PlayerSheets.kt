@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.app.shadetube.ui.nowplaying

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.shadetube.AppGraph
import com.app.shadetube.R
import com.app.shadetube.ui.components.ShadeBottomSheet
import com.app.shadetube.ui.components.ShadeFilterChip
import com.app.shadetube.ui.components.ShadeSlider
import com.app.shadetube.ui.components.SheetHeader
import com.app.shadetube.ui.components.SheetMenuRow
import com.app.shadetube.ui.theme.ShadeMono
import com.app.shadetube.ui.theme.ShadeTheme
import kotlin.math.abs
import kotlin.math.roundToInt

private val SPEED_CHIPS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.25f, 2.5f, 2.75f, 3f)

/** Speed (SCREENS #11): 0.5–3× slider (0.05 steps) + 0.25-step chips; songs and episodes keep their own speed. */
@Composable
internal fun SpeedSheet(onDismiss: () -> Unit) {
    val player = AppGraph.player
    val speed by player.speed.collectAsStateWithLifecycle()
    var drag by remember { mutableFloatStateOf(Float.NaN) }
    val shown = if (drag.isNaN()) speed else drag
    ShadeBottomSheet(onDismissRequest = onDismiss) {
        SheetHeader(stringResource(R.string.np_speed_title))
        Text(
            stringResource(R.string.player_speed_value, NowPlayingTexts.speed(shown)),
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            style = ShadeMono.large.copy(fontSize = 28.sp), color = ShadeTheme.accent.accent,
        )
        Spacer(Modifier.height(8.dp))
        ShadeSlider(
            value = shown,
            onValueChange = { drag = ((it * 20).roundToInt() / 20f) },
            onValueChangeFinished = {
                if (!drag.isNaN()) player.setSpeed(drag)
                drag = Float.NaN
            },
            valueRange = 0.5f..3f,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (v in SPEED_CHIPS) {
                ShadeFilterChip(
                    label = stringResource(R.string.player_speed_value, NowPlayingTexts.speed(v)),
                    selected = abs(v - shown) < 0.01f,
                    onClick = { player.setSpeed(v) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Sleep timer (SCREENS #11): 5/15/30/45/60 min / end of track; shows what is left and offers "turn off". */
@Composable
internal fun SleepSheet(minutesLeft: Long, onDismiss: () -> Unit) {
    val timer = AppGraph.sleepTimer
    val subtitle = when {
        minutesLeft < 0 -> stringResource(R.string.np_sleep_at_end_active)
        minutesLeft > 0 -> stringResource(R.string.np_sleep_left, minutesLeft.toInt())
        else -> null
    }
    ShadeBottomSheet(onDismissRequest = onDismiss) {
        SheetHeader(stringResource(R.string.player_sleep_timer), subtitle = subtitle)
        for (m in listOf(5, 15, 30, 45, 60)) {
            SheetMenuRow(R.drawable.ic_clock, stringResource(R.string.np_sleep_minutes, m), onClick = { timer.set(m); onDismiss() })
        }
        SheetMenuRow(R.drawable.ic_music_note, stringResource(R.string.player_sleep_end_of_track), onClick = { timer.setAtTrackEnd(); onDismiss() })
        if (minutesLeft != 0L) {
            SheetMenuRow(R.drawable.ic_close, stringResource(R.string.np_sleep_off), onClick = { timer.cancel(); onDismiss() }, destructive = true)
        }
    }
}

/** Output device: the system output switcher (Android 14+: MediaRouter2, else the Settings media-output panel). */
internal object OutputSwitcher {
    fun open(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            val ok = runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)
            if (ok) return true
        }
        val panel = Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
            .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(panel) }.isSuccess) return true
        return runCatching {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    /** "Kulaklık" / a Bluetooth device's name when one is connected, else "Telefon". */
    fun label(context: Context, phone: String, headphones: String): Pair<String, Boolean> {
        val am = context.getSystemService(AudioManager::class.java) ?: return phone to false
        val outs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }.getOrDefault(emptyList())
        val bt = outs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
        if (bt != null) return (bt.productName?.toString()?.takeIf { it.isNotBlank() } ?: headphones) to true
        val wired = outs.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        return if (wired) headphones to true else phone to false
    }
}
