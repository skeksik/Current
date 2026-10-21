package com.skeksik.current.feature.settings

import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.core.model.User
import com.skeksik.current.core.network.ServerClient
import com.skeksik.current.data.jellyfin.JellyfinAccounts
import com.skeksik.current.data.jellyfin.QuickConnectRequest
import com.skeksik.current.data.jellyfin.SignInException
import com.skeksik.current.data.jellyfin.SignInFailure
import kotlinx.coroutines.CompletableDeferred

internal val home = ServerRef(ServiceType.jellyfin, "home")

/** Answers as told; records sign-ins, sign-outs and whether a Quick Connect wait was cancelled. */
internal class FakeAccounts : JellyfinAccounts {
    var quickConnect = true
    var signInResult: () -> User = { User("u1", home, "Alex") }
    val signIns = mutableListOf<String>()

    /** Completed when the server hands out a code; already complete unless a test wants to hold it. */
    var codeReady = CompletableDeferred(Unit)
    val approval = CompletableDeferred<Unit>()
    var finishFailure: SignInFailure? = null
    var finishCancelled = false
    val signedOut = mutableListOf<ServerRef>()

    override suspend fun signIn(server: ServerRef, username: String, password: String): User {
        signIns += username
        return signInResult()
    }

    override suspend fun quickConnectEnabled(server: ServerRef) = quickConnect

    override suspend fun startQuickConnect(server: ServerRef): QuickConnectRequest {
        codeReady.await()
        return QuickConnectRequest("123456", "secret")
    }

    override suspend fun finishQuickConnect(server: ServerRef, request: QuickConnectRequest): User {
        var approved = false
        try {
            approval.await()
            approved = true
        } finally {
            if (!approved) finishCancelled = true
        }
        finishFailure?.let { throw SignInException(it) }
        return User("u1", home, "Alex")
    }

    override suspend fun signOut(server: ServerRef) {
        signedOut += server
    }

    override suspend fun authorizedClient(server: ServerRef): ServerClient? = null
}
