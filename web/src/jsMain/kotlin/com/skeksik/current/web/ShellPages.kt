package com.skeksik.current.web

import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.feature.home.HomeUiState
import com.skeksik.current.feature.home.HomeViewModel
import com.skeksik.current.feature.settings.AddressRow
import com.skeksik.current.feature.settings.ServerAddressesUiState
import com.skeksik.current.feature.settings.ServerAddressesViewModel
import com.skeksik.current.feature.settings.ServerRow
import com.skeksik.current.feature.settings.ServerSetupUiState
import com.skeksik.current.feature.settings.ServerSetupViewModel
import com.skeksik.current.feature.settings.ServersUiState
import com.skeksik.current.feature.settings.ServersViewModel
import com.skeksik.current.feature.settings.SignInUiState
import com.skeksik.current.feature.settings.SignInViewModel

// The shell's pages (home, settings, add server, sign in) as the React app sees them: each UiState becomes one flat view with a `kind`,
// enums become their names (the app maps them to text), and paths come from the router, so the app never builds a URL itself.

/** [kind] is "noServer", "signedOut" or "signedIn". [signInPath] is null when the server has no sign-in page (open Settings instead). */
@JsExport
class HomeView(val kind: String, val serverName: String?, val userName: String?, val signInPath: String?)

@JsExport
class HomePage internal constructor(holder: ViewModelHolder<HomeViewModel>) : Page(holder) {
    val state: Observable<HomeView> = holder.viewModel.state.observe { it.toView() }
}

internal fun HomeUiState.toView() = when (this) {
    HomeUiState.NoServer -> HomeView("noServer", serverName = null, userName = null, signInPath = null)
    is HomeUiState.SignedOut -> HomeView("signedOut", serverName, userName = null, signInPath = server.signInPath())
    is HomeUiState.SignedIn -> HomeView("signedIn", serverName, userName, signInPath = null)
}

/**
 * One saved server. [id] names it in [SettingsPage]'s actions. [signedInAs] is null when signed out; [offline] once a check failed.
 * [signInPath] is null for services without a sign-in page. [addressesPath] is its Addresses page.
 */
@JsExport
class ServerRowView(
    val id: String,
    val name: String,
    val address: String,
    val signedInAs: String?,
    val offline: Boolean,
    val signInPath: String?,
    val addressesPath: String,
)

/** [secretStorage] is a `SecretStorage` name ("Browser" on the web); [fileOffered] never holds on the web. */
@JsExport
class SettingsView(val servers: Array<ServerRowView>, val secretStorage: String, val fileOffered: Boolean)

@JsExport
class SettingsPage internal constructor(private val holder: ViewModelHolder<ServersViewModel>) : Page(holder) {
    val state: Observable<SettingsView> = holder.viewModel.state.observe { it.toView() }

    fun signOut(id: String) {
        ref(id)?.let(holder.viewModel::signOut)
    }

    /** Signs out, then forgets the server. */
    fun remove(id: String) {
        ref(id)?.let(holder.viewModel::remove)
    }

    fun keepSecretsInFile() = holder.viewModel.keepSecretsInFile()

    private fun ref(id: String): ServerRef? = holder.viewModel.state.value.servers.firstOrNull { it.ref.id == id }?.ref
}

internal fun ServersUiState.toView() = SettingsView(
    servers = servers.map { it.toView() }.toTypedArray(),
    secretStorage = secretStorage.name,
    fileOffered = fileOffered,
)

private fun ServerRow.toView() = ServerRowView(
    id = ref.id,
    name = name,
    address = address,
    signedInAs = signedInAs,
    offline = reachable == false,
    signInPath = ref.signInPath(),
    addressesPath = Route.ServerAddresses(ref).path,
)

/**
 * [kind] is "editing", "checking", "found" or "saved". [problem] is a `ServerSetupProblem` name while editing. The found server's
 * fields are set while [kind] is "found". Once "saved", [nextPath] is the new server's sign-in page, to show in this page's place, or
 * null to go back (always, when adding an address).
 */
@JsExport
class AddServerView(
    val kind: String,
    val input: String,
    val problem: String?,
    val name: String?,
    val version: String?,
    val address: String?,
    val local: Boolean,
    val plainHttpOnInternet: Boolean,
    val nextPath: String?,
)

/**
 * Add server, or with [addingAddress], add an address to the saved server named [serverName] (null when it is no longer saved). The
 * local network prompt is left out: browsers ask themselves, and its state never leaves Hidden here (ADR 0020).
 */
@JsExport
class AddServerPage internal constructor(
    private val holder: ViewModelHolder<ServerSetupViewModel>,
    val addingAddress: Boolean = false,
    val serverName: String? = null,
) : Page(holder) {
    val state: Observable<AddServerView> = holder.viewModel.state.observe { it.toView(addingAddress) }

    fun onInput(text: String) = holder.viewModel.onInput(text)

    fun check() = holder.viewModel.check()

    fun confirm() = holder.viewModel.confirm()
}

internal fun ServerSetupUiState.toView(addingAddress: Boolean = false): AddServerView {
    fun view(kind: String, problem: String? = null, nextPath: String? = null) =
        AddServerView(kind, input, problem, null, null, null, local = false, plainHttpOnInternet = false, nextPath = nextPath)
    return when (this) {
        is ServerSetupUiState.Editing -> view("editing", problem = problem?.name)
        is ServerSetupUiState.Checking -> view("checking")
        is ServerSetupUiState.Found -> AddServerView(
            kind = "found",
            input = input,
            problem = null,
            name = name,
            version = version,
            address = address,
            local = scope == AddressScope.Local,
            plainHttpOnInternet = plainHttpOnInternet,
            nextPath = null,
        )
        is ServerSetupUiState.Saved -> view("saved", nextPath = if (addingAddress) null else server.ref.signInPath())
    }
}

/** [kind] is "form", "quickConnect" or "signedIn". [problem] is a `SignInProblem` name; [code] is set while [kind] is "quickConnect". */
@JsExport
class SignInView(
    val kind: String,
    val username: String,
    val password: String,
    val quickConnect: Boolean,
    val working: Boolean,
    val problem: String?,
    val code: String?,
)

/** Sign in to a saved Jellyfin server. [serverName] is null when the path names no saved server (it was removed in another tab). */
@JsExport
class SignInPage internal constructor(private val holder: ViewModelHolder<SignInViewModel>, val serverName: String?) : Page(holder) {
    val state: Observable<SignInView> = holder.viewModel.state.observe { it.toView() }

    fun onUsername(text: String) = holder.viewModel.onUsername(text)

    fun onPassword(text: String) = holder.viewModel.onPassword(text)

    fun signIn() = holder.viewModel.signIn()

    fun startQuickConnect() = holder.viewModel.startQuickConnect()

    fun cancelQuickConnect() = holder.viewModel.cancelQuickConnect()
}

internal fun SignInUiState.toView() = when (this) {
    is SignInUiState.Form -> SignInView("form", username, password, quickConnect, working, problem?.name, code = null)
    is SignInUiState.QuickConnect -> SignInView("quickConnect", "", "", quickConnect = true, working = false, problem = null, code = code)
    is SignInUiState.SignedIn -> SignInView("signedIn", "", "", quickConnect = false, working = false, problem = null, code = null)
}

/**
 * One address. [scope] is an `AddressScope` name ("Local" or "Internet"). [inUse] marks the address requests go to now; [canRemove] is
 * false for a server's last address.
 */
@JsExport
class AddressRowView(val url: String, val scope: String, val plainHttpOnInternet: Boolean, val inUse: Boolean, val canRemove: Boolean)

/**
 * A server's addresses, highest priority first. [saved] turns false once the server is removed (the app leaves the page);
 * [unreachable] while it answers at none of them. [addPath] is the Add address page.
 */
@JsExport
class ServerAddressesView(
    val saved: Boolean,
    val name: String,
    val unreachable: Boolean,
    val addresses: Array<AddressRowView>,
    val addPath: String,
)

/** Settings > Servers > a server's addresses (ADR 0019): reorder and remove them; adding one is [AddServerPage] on [addPath]'s route. */
@JsExport
class ServerAddressesPage internal constructor(private val holder: ViewModelHolder<ServerAddressesViewModel>, server: ServerRef) :
    Page(holder) {
    private val addPath = Route.AddServerAddress(server).path

    val state: Observable<ServerAddressesView> = holder.viewModel.state.observe { it.toView(addPath) }

    /** Does nothing for the last address. */
    fun remove(url: String) = holder.viewModel.remove(url)

    fun moveUp(url: String) = holder.viewModel.moveUp(url)

    fun moveDown(url: String) = holder.viewModel.moveDown(url)
}

internal fun ServerAddressesUiState.toView(addPath: String) = ServerAddressesView(
    saved = saved,
    name = name,
    unreachable = reachable == false,
    addresses = addresses.map { it.toView() }.toTypedArray(),
    addPath = addPath,
)

private fun AddressRow.toView() = AddressRowView(url, scope.name, plainHttpOnInternet, inUse, canRemove)

internal val ServerRef.id: String get() = "${service.value}/$key"

/** Only Jellyfin has a sign-in page yet. */
internal fun ServerRef.signInPath(): String? = Route.SignIn(this).path.takeIf { service == ServiceType.jellyfin }
