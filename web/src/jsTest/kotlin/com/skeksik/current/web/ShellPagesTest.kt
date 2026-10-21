package com.skeksik.current.web

import com.skeksik.current.core.database.SecretStorage
import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.feature.home.HomeUiState
import com.skeksik.current.feature.settings.ServerRow
import com.skeksik.current.feature.settings.ServerSetupProblem
import com.skeksik.current.feature.settings.ServerSetupUiState
import com.skeksik.current.feature.settings.ServersUiState
import com.skeksik.current.feature.settings.SignInProblem
import com.skeksik.current.feature.settings.SignInUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShellPagesTest {
    private val jellyfin = ServerRef(ServiceType.jellyfin, "0a1b2c")
    private val other = ServerRef(ServiceType("stash"), "3d4e5f")

    @Test
    fun homeOffersTheSignInPageOnlyForJellyfin() {
        val signedOut = HomeUiState.SignedOut(jellyfin, "Living room").toView()
        assertEquals("signedOut", signedOut.kind)
        assertEquals("Living room", signedOut.serverName)
        assertEquals("/settings/servers/jellyfin/0a1b2c/sign-in", signedOut.signInPath)

        assertNull(HomeUiState.SignedOut(other, "Scenes").toView().signInPath)
        assertEquals("noServer", HomeUiState.NoServer.toView().kind)
        val signedIn = HomeUiState.SignedIn("alex", "Living room").toView()
        assertEquals("signedIn", signedIn.kind)
        assertEquals("alex", signedIn.userName)
    }

    @Test
    fun settingsRowsKeepWhoIsSignedInAndWhetherTheServerIsOffline() {
        val view = ServersUiState(
            servers = listOf(
                ServerRow(jellyfin, "Living room", "https://media.example.com", signedInAs = "alex", reachable = true),
                ServerRow(other, "Scenes", "http://192.168.1.20:9999", signedInAs = null, reachable = false),
            ),
            secretStorage = SecretStorage.Browser,
        ).toView()

        assertEquals("Browser", view.secretStorage)
        val (first, second) = view.servers.toList()
        assertEquals("jellyfin/0a1b2c", first.id)
        assertEquals("alex", first.signedInAs)
        assertEquals(false, first.offline)
        assertEquals("/settings/servers/jellyfin/0a1b2c/sign-in", first.signInPath)
        assertNull(second.signedInAs)
        assertEquals(true, second.offline)
        assertNull(second.signInPath)
    }

    @Test
    fun aServerNotCheckedYetIsNotShownOffline() {
        val row = ServersUiState(listOf(ServerRow(jellyfin, "Living room", "https://media.example.com", null, reachable = null))).toView()
        assertEquals(false, row.servers.single().offline)
    }

    @Test
    fun addServerStatesKeepTheInputAndNameTheirProblem() {
        val editing = ServerSetupUiState.Editing("media", ServerSetupProblem.TimedOut).toView()
        assertEquals("editing", editing.kind)
        assertEquals("media", editing.input)
        assertEquals("TimedOut", editing.problem)
        assertEquals("checking", ServerSetupUiState.Checking("media").toView().kind)

        val found = ServerSetupUiState.Found(
            input = "media",
            address = "http://192.168.1.10:8096",
            name = "Living room",
            version = "12.2.0",
            scope = AddressScope.Local,
            plainHttpOnInternet = false,
        ).toView()
        assertEquals("found", found.kind)
        assertEquals("Living room", found.name)
        assertEquals(true, found.local)
        assertEquals(false, found.plainHttpOnInternet)
    }

    @Test
    fun aSavedJellyfinServerLeadsToItsSignInPageAndAnyOtherServerBack() {
        val address = listOf(ServerAddress("https://media.example.com", AddressScope.Internet))
        val saved = ServerSetupUiState.Saved("media", SavedServer(jellyfin, "id", "Living room", address)).toView()
        assertEquals("saved", saved.kind)
        assertEquals("/settings/servers/jellyfin/0a1b2c/sign-in", saved.nextPath)

        assertNull(ServerSetupUiState.Saved("scenes", SavedServer(other, "id", "Scenes", address)).toView().nextPath)
    }

    @Test
    fun signInStatesCarryTheFormTheCodeOrTheEnd() {
        val form = SignInUiState.Form("alex", "secret", quickConnect = true, working = false, problem = SignInProblem.WrongCredentials)
            .toView()
        assertEquals("form", form.kind)
        assertEquals("alex", form.username)
        assertEquals(true, form.quickConnect)
        assertEquals("WrongCredentials", form.problem)

        val code = SignInUiState.QuickConnect("123456").toView()
        assertEquals("quickConnect", code.kind)
        assertEquals("123456", code.code)
        assertEquals("signedIn", SignInUiState.SignedIn("alex").toView().kind)
    }

    /**
     * These enums cross the facade by name, and webApp/src/strings.ts gives each name a text through a hand-written union type. A new
     * value fails here: add it to the union in strings.ts (the TypeScript build then asks for its text) and to this list.
     */
    @Test
    fun enumNamesMatchTheUnionsInTheWebStrings() {
        assertEquals(
            listOf("CredentialManager", "SecretService", "Browser", "File", "Memory"),
            SecretStorage.entries.map { it.name },
            "SecretStorageName in webApp/src/strings.ts",
        )
        assertEquals(
            listOf(
                "InvalidAddress", "Unreachable", "TimedOut", "Redirected", "NotThisService", "Refused", "AlreadyAdded", "DifferentServer",
                "ServerNotSaved",
            ),
            ServerSetupProblem.entries.map { it.name },
            "ServerSetupProblemName in webApp/src/strings.ts",
        )
        assertEquals(
            listOf(
                "WrongCredentials", "Unreachable", "TimedOut", "Refused", "DifferentServer", "QuickConnectDisabled", "QuickConnectExpired",
                "ServerNotSaved",
            ),
            SignInProblem.entries.map { it.name },
            "SignInProblemName in webApp/src/strings.ts",
        )
    }
}
