package com.skeksik.current.spike.webhtml

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import com.skeksik.current.spike.web.ItemDetails
import com.skeksik.current.spike.web.Library
import com.skeksik.current.spike.web.LoadState
import com.skeksik.current.spike.web.MediaItem
import com.skeksik.current.spike.web.Route
import com.skeksik.current.spike.web.ViewModelHolder
import com.skeksik.current.spike.web.WebApp
import com.skeksik.current.spike.web.holdViewModel
import com.skeksik.current.spike.web.isPlainClick
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.autoComplete
import org.jetbrains.compose.web.attributes.AutoComplete
import org.jetbrains.compose.web.attributes.name
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.AttrBuilderContext
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.ContentBuilder
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement

fun main() {
    val app = WebApp.create()
    renderComposable(rootElementId = "root") { App(app) }
}

/** One ViewModel per page instance, cleared when the page leaves the composition. */
@Composable
fun <VM : ViewModel> rememberViewModel(key: Any, create: () -> ViewModelHolder<VM>): VM {
    val holder = remember(key) { create() }
    DisposableEffect(holder) { onDispose { holder.close() } }
    return holder.viewModel
}

@Composable
fun App(app: WebApp) {
    val screen by app.screen.collectAsState()
    when (val route = screen) {
        Route.SignIn -> SignInPage(app)
        Route.Home -> Shell(app) { HomePage(app) }
        is Route.Library -> Shell(app) { LibraryPage(app, route.id) }
        is Route.Item -> Shell(app) { ItemPage(app, route.id) }
        is Route.Player -> PlayerPage(app, route.id)
        is Route.NotFound -> Shell(app) { P({ classes("message") }) { Text("Nothing here.") } }
    }
}

/** A real link: middle-click, new tab and copy link work; a plain click navigates inside the app. */
@Composable
fun AppLink(app: WebApp, route: Route, attrs: AttrBuilderContext<HTMLAnchorElement> = {}, content: ContentBuilder<HTMLAnchorElement>) {
    A(href = route.path, attrs = {
        attrs()
        onClick { e ->
            if (isPlainClick(e.button.toInt(), e.ctrlKey, e.metaKey, e.shiftKey, e.altKey)) {
                e.preventDefault()
                app.router.navigate(route)
            }
        }
    }, content = content)
}

@Composable
fun Shell(app: WebApp, content: @Composable () -> Unit) {
    val session by app.graph.session.collectAsState()
    Div({ classes("header") }) {
        AppLink(app, Route.Home, { classes("brand") }) { Text("Current") }
        Span({ classes("spacer") })
        Span({ classes("user") }) { Text(session?.userName.orEmpty()) }
        Button({ onClick { app.graph.signOut() } }) { Text("Sign out") }
    }
    Main({ classes("page") }) { content() }
}

@Composable
fun SignInPage(app: WebApp) {
    val vm = rememberViewModel("sign-in") { holdViewModel { app.graph.signInViewModel() } }
    val state by vm.state.collectAsState()
    fun field(id: String) = (document.getElementById(id) as HTMLInputElement).value
    Form(attrs = {
        classes("sign-in")
        attr("method", "post")
        onSubmit { e ->
            e.preventDefault()
            vm.signIn(field("server"), field("username"), field("password"))
        }
    }) {
        H1 { Text("Sign in to Jellyfin") }
        Label("server") {
            Text("Server")
            Input(InputType.Url) {
                id("server"); name("server"); autoComplete(AutoComplete.url); attr("placeholder", "https://jellyfin.example.org")
                defaultValue(state.serverUrl); attr("required", "")
            }
        }
        Label("username") {
            Text("Username")
            Input(InputType.Text) { id("username"); name("username"); autoComplete(AutoComplete.username); attr("required", "") }
        }
        Label("password") {
            Text("Password")
            Input(InputType.Password) { id("password"); name("password"); autoComplete(AutoComplete.currentPassword) }
        }
        state.error?.let { P({ classes("error"); attr("role", "alert") }) { Text(it) } }
        Button({ type(ButtonType.Submit); classes("primary"); if (state.busy) attr("disabled", "") }) {
            Text(if (state.busy) "Signing in…" else "Sign in")
        }
    }
}

@Composable
fun <T> Loaded(state: LoadState<T>, content: @Composable (T) -> Unit) {
    when (state) {
        LoadState.Loading -> P({ classes("message") }) { Text("Loading…") }
        is LoadState.Failed -> P({ classes("error"); attr("role", "alert") }) { Text(state.message) }
        is LoadState.Ready -> content(state.value)
    }
}

@Composable
fun HomePage(app: WebApp) {
    val vm = rememberViewModel("home") { holdViewModel { app.graph.homeViewModel() } }
    val state by vm.state.collectAsState()
    H1 { Text("Libraries") }
    Loaded(state) { libraries ->
        Div({ classes("grid") }) {
            libraries.forEach { LibraryCard(app, it) }
        }
    }
}

@Composable
fun LibraryCard(app: WebApp, library: Library) {
    AppLink(app, Route.Library(library.id), { classes("card") }) {
        Img(src = library.imageUrl ?: "", alt = "", attrs = { classes("poster", "wide"); attr("loading", "lazy") })
        Span({ classes("title") }) { Text(library.name) }
    }
}

@Composable
fun LibraryPage(app: WebApp, id: String) {
    val vm = rememberViewModel(id) { holdViewModel { app.graph.libraryViewModel(id) } }
    val state by vm.state.collectAsState()
    H1 { Text(state.title) }
    Loaded(state.items) { items -> PosterGrid(app, items) }
}

@Composable
fun PosterGrid(app: WebApp, items: List<MediaItem>) {
    Div({ classes("grid") }) {
        items.forEach { item ->
            AppLink(app, Route.Item(item.id), { classes("card") }) {
                Img(src = item.imageUrl ?: "", alt = "", attrs = {
                    classes("poster")
                    if (item.imageAspectRatio > 1) classes("wide")
                    attr("loading", "lazy")
                })
                Span({ classes("title") }) { Text(item.name) }
                item.year?.let { Span({ classes("sub") }) { Text(it.toString()) } }
            }
        }
    }
}

@Composable
fun ItemPage(app: WebApp, id: String) {
    val vm = rememberViewModel(id) { holdViewModel { app.graph.itemViewModel(id) } }
    val state by vm.state.collectAsState()
    Loaded(state) { details: ItemDetails ->
        val item = details.item
        Div({ classes("item") }) {
            item.imageUrl?.let { Img(src = it, alt = "", attrs = { classes("poster") }) }
            Div {
                H1 { Text(item.name) }
                P({ classes("meta") }) { Text(listOfNotNull(item.seriesName, item.year?.toString(), item.type).joinToString(" · ")) }
                if (item.isPlayable) {
                    P({ classes("actions") }) { AppLink(app, Route.Player(item.id), { classes("button", "primary") }) { Text("▶ Play") } }
                }
                item.overview?.let { P({ classes("overview") }) { Text(it) } }
            }
        }
        if (details.children.isNotEmpty()) PosterGrid(app, details.children)
    }
}
