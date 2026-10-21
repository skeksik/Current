package com.skeksik.current.core.network

import kotlinx.coroutines.flow.MutableSharedFlow

/** A platform that asks: [permission] is what the settings page shows, and [set] changes it the way the shell reports it. */
internal class FakeLocalNetworkAccess(var permission: LocalNetworkPermission) : LocalNetworkAccess {
    override val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    override fun status() = permission

    override suspend fun request() = permission

    override fun openSettings() = Unit

    fun set(permission: LocalNetworkPermission) {
        this.permission = permission
        changes.tryEmit(Unit)
    }
}
