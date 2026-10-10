package com.app.shadetube.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.app.shadetube.core.Log

/**
 * PackageInstaller's status for the update session ([Updater.install]). A confirmation the system asks for
 * (STATUS_PENDING_USER_ACTION) is started right away when possible (the user just tapped "İndir ve kur", so the app is
 * normally in the foreground) and also published as [UpdateState.NeedsUserAction] for the UI to start it.
 *
 * Manifest (central): `<receiver android:name=".update.InstallStatusReceiver" android:exported="false" />`.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val confirm: Intent? = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
        } else {
            null
        }
        Log.i("update", "install status $status ${message.orEmpty()}")
        UpdateRuntime.updater?.onInstallStatus(status, message, confirm)
        if (confirm != null) {
            runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Log.w("update", "could not show the install confirmation: ${it.message}") }
        }
    }
}
