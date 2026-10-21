package com.skeksik.current.core.network

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.skeksik.current.core.database.KeyValueStore

/**
 * `ACCESS_LOCAL_NETWORK` (ADR 0020): a runtime permission from API 37, required when the app targets 37. Below API 37 the app has
 * local access with `INTERNET` alone and must not ask, so the state is [LocalNetworkPermission.NotNeeded] there. The dialog itself
 * goes through [requester], which the shell implements with an activity.
 */
class AndroidLocalNetworkAccess(
    context: Context,
    store: KeyValueStore,
    private val requester: PermissionRequester,
) : LocalNetworkAccess {
    private val context = context.applicationContext
    private val state = LocalNetworkPermissionState(store)

    override fun status(): LocalNetworkPermission {
        if (Build.VERSION.SDK_INT < API_LEVEL) return LocalNetworkPermission.NotNeeded
        return state.status(context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED)
    }

    // Asks even when Blocked: Android then answers at once without a dialog, unless it has reset the denials since.
    override suspend fun request(): LocalNetworkPermission = when (val current = status()) {
        LocalNetworkPermission.NotNeeded, LocalNetworkPermission.Granted -> current
        LocalNetworkPermission.CanAsk, LocalNetworkPermission.Blocked -> state.record(requester.request(PERMISSION))
    }

    override fun openSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

        /** Android 17, where `ACCESS_LOCAL_NETWORK` was added. */
        private const val API_LEVEL = 37
    }
}
