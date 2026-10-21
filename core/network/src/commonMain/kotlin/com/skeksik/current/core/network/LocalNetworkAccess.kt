package com.skeksik.current.core.network

import com.skeksik.current.core.database.KeyValueStore
import com.skeksik.current.core.model.SavedServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.scope.Scope

/**
 * Whether the app may reach servers on the local network (ADR 0020). Only Android 17 asks today (`ACCESS_LOCAL_NETWORK`); Apple's
 * local network prompt will be another implementation.
 *
 * While it is not granted, local addresses cannot be reached, nor can an internet name that resolves to one (split DNS at home); other
 * internet addresses still work. A denied HTTP request to a local address does not fail fast, it only times out, so read [status]
 * before probing a local address rather than reading the error.
 */
interface LocalNetworkAccess {
    /** The current state, without asking the user. Cheap; call it again whenever the answer matters, it can change in the settings. */
    fun status(): LocalNetworkPermission

    /**
     * Whether the user has said no since the last grant, on any screen (kept in the machine-local store, ADR 0017). Then no screen shows
     * the dialog on its own: each says what the denial means and asks again only when the user presses "Ask again" (ADR 0020). False
     * while granted, and where the platform does not ask.
     */
    fun deniedBefore(): Boolean

    /**
     * Emits when [status] may have changed: a dialog was answered, or the app came back to the foreground (a grant in the settings
     * comes no other way). It may emit with nothing changed, so read [status] then. Platforms that never ask emit nothing.
     */
    val changes: Flow<Unit>

    /**
     * Shows the system dialog when the platform still will, and returns the state after the user answered. Returns at once when it is
     * [LocalNetworkPermission.Granted] or [LocalNetworkPermission.NotNeeded], or when no screen can show the dialog. Explain why
     * before calling it: Android's dialog speaks of "nearby devices", not servers.
     */
    suspend fun request(): LocalNetworkPermission

    /** Opens the app's page in the system settings, the only sure way back from [LocalNetworkPermission.Blocked]. */
    fun openSettings()

    /** For platforms that do not ask: desktop, web, and Apple until its prompt is handled. */
    object NotNeeded : LocalNetworkAccess {
        override fun status() = LocalNetworkPermission.NotNeeded

        override fun deniedBefore() = false

        override val changes: Flow<Unit> = emptyFlow()

        override suspend fun request() = LocalNetworkPermission.NotNeeded

        override fun openSettings() = Unit
    }
}

/** [LocalNetworkAccess.NotNeeded] everywhere but Android. */
internal expect fun Scope.platformLocalNetworkAccess(): LocalNetworkAccess

enum class LocalNetworkPermission {
    /** The platform does not ask (desktop, web, Android before 17). Local addresses are reachable. */
    NotNeeded,

    Granted,

    /** Not granted, and [LocalNetworkAccess.request] would show the system dialog. */
    CanAsk,

    /** Denied, and the platform will most likely not ask again: the app's settings page is the way back. */
    Blocked,
}

/**
 * Whether local addresses can be reached. [LocalNetworkPermission.CanAsk] cannot: on Android 17 nothing shows the dialog by itself,
 * so until the app asks and the user allows, local traffic is blocked as after a denial.
 */
val LocalNetworkPermission.allowsLocal: Boolean
    get() = this == LocalNetworkPermission.NotNeeded || this == LocalNetworkPermission.Granted

/** What the user is doing when the shell considers asking. */
enum class LocalNetworkAskReason {
    /** The user opened "Add server": any address they type may be local. */
    AddServer,

    /** The saved servers are about to be used, for instance at start. */
    SavedServers,
}

/**
 * Whether to ask for local network access now (ADR 0020): when the user opens "Add server", or when a saved server has an address that
 * needs it ([SavedServer.needsLocalNetworkAccess], so not one reached only through an overlay VPN), and only while the dialog can still
 * be shown. Never otherwise, so a user with internet addresses only is never asked. Once [LocalNetworkPermission.Blocked], this stays
 * false: the screen offers the settings page, and may also offer "Try again", which calls [LocalNetworkAccess.request] and notices if
 * the platform has reset its denials.
 */
fun shouldAskForLocalNetwork(reason: LocalNetworkAskReason, servers: List<SavedServer>, status: LocalNetworkPermission): Boolean {
    if (status != LocalNetworkPermission.CanAsk) return false
    return when (reason) {
        LocalNetworkAskReason.AddServer -> true
        LocalNetworkAskReason.SavedServers -> servers.any { it.needsLocalNetworkAccess }
    }
}

/**
 * The user's answer to one system dialog. [canAskAgain] is the platform's word on whether the dialog would show again (Android:
 * `shouldShowRequestPermissionRationale` after the denial), or null when the shell cannot tell.
 */
data class PermissionAnswer(val granted: Boolean, val canAskAgain: Boolean? = null)

/** Shows the platform's local network dialog. Returns null when no screen could show it, so nothing was answered. */
fun interface LocalNetworkRequester {
    suspend fun request(): PermissionAnswer?
}

/**
 * The app-scoped link between [LocalNetworkAccess] and the activity that can show the dialog (ADR 0020). An Activity Result launcher
 * belongs to one activity, so it must not be captured by an app-wide object: the activity [attach]es its launch function when created
 * and [detach]es it when destroyed, and its result callback hands every answer to [onResult]. When the activity is recreated while the
 * dialog is up (rotation), the pending request stays open and the new activity's callback completes it. With no activity attached,
 * or when the attached one finishes, a request returns null at once instead of hanging. Call it from the main thread.
 */
class LocalNetworkRequestBridge : LocalNetworkRequester {
    private var launch: (() -> Unit)? = null

    /** The request in progress, shared by every caller; it ends with an answer, a finishing detach, or when its last caller leaves. */
    private var pending: CompletableDeferred<PermissionAnswer?>? = null
    private var waiting = 0
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** [LocalNetworkAccess.changes]: after each answer and each [resumed]. */
    val changes: SharedFlow<Unit> = changed.asSharedFlow()

    /** The activity came to the foreground: the user may have granted or revoked access in the settings meanwhile. */
    fun resumed() {
        changed.tryEmit(Unit)
    }

    fun attach(launch: () -> Unit) {
        this.launch = launch
    }

    /** [finishing] is false when the activity is only being recreated, so the dialog's answer will still come. */
    fun detach(launch: () -> Unit, finishing: Boolean) {
        if (this.launch === launch) this.launch = null
        if (finishing) end(null)
    }

    /** An answer nobody waits for (every caller was cancelled, or the process restarted) is dropped: [LocalNetworkAccess.status] reads the grant anyway. */
    fun onResult(answer: PermissionAnswer) {
        end(answer)
        changed.tryEmit(Unit)
    }

    override suspend fun request(): PermissionAnswer? {
        val joined = pending
        val launch = if (joined == null) launch ?: return null else null
        val answer = joined ?: CompletableDeferred<PermissionAnswer?>().also { pending = it }
        waiting++
        try {
            launch?.invoke()
            return answer.await()
        } finally {
            // A caller cancelled while others still wait leaves the request open for them.
            if (--waiting == 0 && pending === answer) pending = null
        }
    }

    private fun end(answer: PermissionAnswer?) {
        pending?.complete(answer)
        pending = null
    }
}

/**
 * The platform-independent part of [LocalNetworkAccess]: the routing ([apiSupported] false means the platform does not ask) and the
 * bookkeeping, kept in the machine-local store (ADR 0017) because it describes this device. Without an activity Android only reports
 * "granted or not", so whether a denial was final has to be remembered.
 *
 * Android makes a denial permanent when the user denies twice (docs/platform-notes.md). So [LocalNetworkPermission.Blocked] comes from:
 * - the platform's signal, [PermissionAnswer.canAskAgain]: `true` means the dialog can show again, and marks that one denial was seen;
 *   a `false` after such a denial is the second, so it is final;
 * - a counter otherwise, when the shell has no signal or a `false` came with no earlier `true`: the second such denial is final.
 *   A `false` with no earlier `true` may be a dialog closed without an answer (not verified) or a denial made in the settings, so two
 *   closed dialogs read as Blocked; the screen then still offers "Try again", which shows the dialog if Android would.
 * A grant clears everything.
 */
internal class LocalNetworkPermissionState(private val store: KeyValueStore) {
    fun status(apiSupported: Boolean, granted: Boolean): LocalNetworkPermission = when {
        !apiSupported -> LocalNetworkPermission.NotNeeded
        granted -> granted()
        store.get(BLOCKED) != null -> LocalNetworkPermission.Blocked
        else -> LocalNetworkPermission.CanAsk
    }

    /** A denial recorded since the last grant: once, counted, or final. */
    fun deniedBefore(apiSupported: Boolean, granted: Boolean): Boolean =
        status(apiSupported, granted).let { it == LocalNetworkPermission.CanAsk || it == LocalNetworkPermission.Blocked } &&
            listOf(DENIED_ONCE, DENIALS, BLOCKED).any { store.get(it) != null }

    /** Asks even when Blocked: Android then answers at once without a dialog, unless it has reset its denials since. */
    suspend fun request(apiSupported: Boolean, granted: Boolean, requester: LocalNetworkRequester): LocalNetworkPermission {
        val current = status(apiSupported, granted)
        if (current == LocalNetworkPermission.NotNeeded || current == LocalNetworkPermission.Granted) return current
        val answer = requester.request() ?: return current
        return record(answer)
    }

    fun record(answer: PermissionAnswer): LocalNetworkPermission {
        if (answer.granted) return granted()
        return when (answer.canAskAgain) {
            true -> {
                store.put(DENIED_ONCE, "true")
                clear(DENIALS, BLOCKED)
                LocalNetworkPermission.CanAsk
            }
            false -> if (store.get(DENIED_ONCE) != null) blocked() else countDenial()
            null -> countDenial()
        }
    }

    private fun countDenial(): LocalNetworkPermission {
        val denials = (store.get(DENIALS)?.toIntOrNull() ?: 0) + 1
        store.put(DENIALS, denials.toString())
        return if (denials >= 2) blocked() else LocalNetworkPermission.CanAsk
    }

    private fun blocked(): LocalNetworkPermission {
        store.put(BLOCKED, "true")
        return LocalNetworkPermission.Blocked
    }

    private fun granted(): LocalNetworkPermission {
        clear(DENIED_ONCE, DENIALS, BLOCKED)
        return LocalNetworkPermission.Granted
    }

    // Only writes when something is stored: status() runs often, and some stores rewrite a file on every write.
    private fun clear(vararg keys: String) = keys.filter { store.get(it) != null }.forEach(store::remove)

    private companion object {
        const val DENIED_ONCE = "localNetwork.deniedOnce"
        const val DENIALS = "localNetwork.denials"
        const val BLOCKED = "localNetwork.blocked"
    }
}
