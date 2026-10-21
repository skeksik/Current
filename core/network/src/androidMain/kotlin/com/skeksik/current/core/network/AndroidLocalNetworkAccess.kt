package com.skeksik.current.core.network

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.skeksik.current.core.database.KeyValueStore
import com.skeksik.current.core.database.MachineLocal
import org.koin.core.scope.Scope

/**
 * The bridge `MainActivity` attaches its permission launcher to (ADR 0020). App-scoped, so it outlives every activity and never holds
 * one: the activity hands it a launch function and its results.
 */
val localNetworkRequests = LocalNetworkRequestBridge()

internal actual fun Scope.platformLocalNetworkAccess(): LocalNetworkAccess =
    AndroidLocalNetworkAccess(get(), get(MachineLocal), localNetworkRequests)

/**
 * `ACCESS_LOCAL_NETWORK` (ADR 0020): a runtime permission from API 37, required when the app targets 37. Below API 37 the app has
 * local access with `INTERNET` alone and must not ask, so the state is [LocalNetworkPermission.NotNeeded] there.
 */
class AndroidLocalNetworkAccess(
    context: Context,
    store: KeyValueStore,
    private val requester: LocalNetworkRequester,
) : LocalNetworkAccess {
    private val context = context.applicationContext
    private val state = LocalNetworkPermissionState(store)

    override fun status() = state.status(apiSupported(), granted())

    override suspend fun request() = state.request(apiSupported(), granted(), requester)

    override fun openSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun apiSupported() = Build.VERSION.SDK_INT >= API_LEVEL

    private fun granted() = apiSupported() && context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

        /** Android 17, where `ACCESS_LOCAL_NETWORK` was added. */
        private const val API_LEVEL = 37
    }
}
