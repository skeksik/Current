package com.skeksik.current.core.network

import com.skeksik.current.core.database.KeyValueStore
import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer

/**
 * Whether the app may reach servers on the local network (ADR 0020). Only Android 17 asks today (`ACCESS_LOCAL_NETWORK`); Apple's
 * local network prompt will be another implementation.
 *
 * While it is not granted, local addresses cannot be reached and internet addresses still work. A denied HTTP request to a local
 * address does not fail fast, it only times out, so read [status] before probing a local address rather than reading the error.
 */
interface LocalNetworkAccess {
    /** The current state, without asking the user. Cheap; call it again whenever the answer matters, it can change in the settings. */
    fun status(): LocalNetworkPermission

    /**
     * Shows the system dialog when the platform still will, and returns the state after the user answered. Returns at once when it is
     * [LocalNetworkPermission.Granted] or [LocalNetworkPermission.NotNeeded]. Explain why before calling it: Android's dialog speaks
     * of "nearby devices", not servers.
     */
    suspend fun request(): LocalNetworkPermission

    /** Opens the app's page in the system settings, the only way back from [LocalNetworkPermission.Blocked]. */
    fun openSettings()

    /** For platforms that do not ask: desktop, web, and Apple until its prompt is handled. */
    object NotNeeded : LocalNetworkAccess {
        override fun status() = LocalNetworkPermission.NotNeeded

        override suspend fun request() = LocalNetworkPermission.NotNeeded

        override fun openSettings() = Unit
    }
}

enum class LocalNetworkPermission {
    /** The platform does not ask (desktop, web, Android before 17). Local addresses are reachable. */
    NotNeeded,

    Granted,

    /** Not granted, and [LocalNetworkAccess.request] would show the system dialog. */
    CanAsk,

    /** Denied, and the platform will not ask again: only the app's settings page can grant it. */
    Blocked,
}

/** What the user is doing when the shell considers asking. */
enum class LocalNetworkAskReason {
    /** The user opened "Add server": any address they type may be local. */
    AddServer,

    /** The saved servers are about to be used, for instance at start. */
    SavedServers,
}

/**
 * Whether to ask for local network access now (ADR 0020): when the user opens "Add server", or when a saved server has a local
 * address, and only while the dialog can still be shown. Never otherwise, so a user with internet addresses only is never asked.
 */
fun shouldAskForLocalNetwork(reason: LocalNetworkAskReason, servers: List<SavedServer>, status: LocalNetworkPermission): Boolean {
    if (status != LocalNetworkPermission.CanAsk) return false
    return when (reason) {
        LocalNetworkAskReason.AddServer -> true
        LocalNetworkAskReason.SavedServers -> servers.any { server -> server.addresses.any { it.scope == AddressScope.Local } }
    }
}

/**
 * The user's answer to one system dialog, from the shell. [canAskAgain] is the platform's word on whether the dialog would show again
 * (Android: `shouldShowRequestPermissionRationale` after the denial), or null when the shell cannot tell.
 */
data class PermissionAnswer(val granted: Boolean, val canAskAgain: Boolean? = null)

/** Shows the system dialog for one permission. The shell implements it, because only an activity can show the dialog. */
fun interface PermissionRequester {
    suspend fun request(permission: String): PermissionAnswer
}

/**
 * The bookkeeping around the platform's own check, kept in [store] because the platform only reports "granted or not" without an
 * activity, so whether a denial was final has to be remembered.
 *
 * A denial is final ([LocalNetworkPermission.Blocked]) by the platform's signal, [PermissionAnswer.canAskAgain], when the shell gives
 * one: `true` always means the dialog can show again. `false` is believed from the second denial on, because Android makes a denial
 * permanent only when the user denies twice; a `false` after the first can come from a dialog dismissed without an answer. Without a
 * signal, a counter decides: the second denial is final. A grant clears both.
 */
internal class LocalNetworkPermissionState(private val store: KeyValueStore) {
    fun status(granted: Boolean): LocalNetworkPermission = when {
        granted -> granted()
        store.get(BLOCKED) == "true" -> LocalNetworkPermission.Blocked
        else -> LocalNetworkPermission.CanAsk
    }

    fun record(answer: PermissionAnswer): LocalNetworkPermission {
        if (answer.granted) return granted()
        val denials = (store.get(DENIALS)?.toIntOrNull() ?: 0) + 1
        store.put(DENIALS, denials.toString())
        val blocked = answer.canAskAgain?.let { !it && denials >= 2 } ?: (denials >= 2)
        return if (blocked) {
            store.put(BLOCKED, "true")
            LocalNetworkPermission.Blocked
        } else {
            if (store.get(BLOCKED) != null) store.remove(BLOCKED)
            LocalNetworkPermission.CanAsk
        }
    }

    // Only writes when something is stored: status() runs often, and some stores rewrite a file on every write.
    private fun granted(): LocalNetworkPermission {
        listOf(DENIALS, BLOCKED).filter { store.get(it) != null }.forEach(store::remove)
        return LocalNetworkPermission.Granted
    }

    private companion object {
        const val DENIALS = "localNetwork.denials"
        const val BLOCKED = "localNetwork.blocked"
    }
}
