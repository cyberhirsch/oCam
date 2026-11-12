package com.ocam.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** How an install ended, unless it ended by replacing the process that was waiting to hear. */
sealed interface InstallResult {
    data object Installed : InstallResult
    data object Cancelled : InstallResult
    data class Refused(val message: String) : InstallResult
}

/** Hears what the system made of an install session, and shows its prompt if it needs one. */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        Log.i(TAG, "Install status $status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmation(intent)
                if (confirm == null) {
                    publish(InstallResult.Refused("Android asked for a confirmation it did not supply."))
                } else {
                    // The answer comes back as another broadcast with the final status.
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> publish(InstallResult.Installed)
            PackageInstaller.STATUS_FAILURE_ABORTED -> publish(InstallResult.Cancelled)
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                publish(InstallResult.Refused("Android refused the update: it conflicts with this install."))
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                publish(InstallResult.Refused("There is not enough storage to install the update."))
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                publish(InstallResult.Refused("This phone blocks installing the update."))
            else -> publish(InstallResult.Refused("Android could not install the update ($status)."))
        }
    }

    private fun confirmation(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        private const val TAG = "oCamUpdate"
        private val _results = MutableSharedFlow<InstallResult>(extraBufferCapacity = 1)
        val results: SharedFlow<InstallResult> = _results.asSharedFlow()

        private fun publish(result: InstallResult) {
            _results.tryEmit(result)
        }
    }
}
