package com.skeksik.current.spike.webwasm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.HtmlElementView
import androidx.compose.ui.window.ComposeViewport
import androidx.lifecycle.ViewModel
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.skeksik.current.spike.web.ItemDetails
import com.skeksik.current.spike.web.LoadState
import com.skeksik.current.spike.web.MediaItem
import com.skeksik.current.spike.web.Route
import com.skeksik.current.spike.web.ViewModelHolder
import com.skeksik.current.spike.web.WebApp
import com.skeksik.current.spike.web.holdViewModel

val Bg = Color(0xFF111318)
val SurfaceColor = Color(0xFF1C1F26)
val TextColor = Color(0xFFE8E9EE)
val Muted = Color(0xFF9AA0AD)
val Accent = Color(0xFF5B8DEF)
val ErrorColor = Color(0xFFFF8A80)

fun main() {
    val app = WebApp.create()
    ComposeViewport("compose-root") { App(app) }
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
    setSingletonImageLoaderFactory { context -> ImageLoader.Builder(context).components { add(KtorNetworkFetcherFactory()) }.build() }
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = SurfaceColor, onBackground = TextColor, onSurface = TextColor)) {
        CompositionLocalProvider(LocalContentColor provides TextColor) {
            val screen by app.screen.collectAsState()
            when (val route = screen) {
                Route.SignIn -> SignInPage(app)
                Route.Home -> Shell(app) { HomePage(app) }
                is Route.Library -> Shell(app) { LibraryPage(app, route.id) }
                is Route.Item -> Shell(app) { ItemPage(app, route.id) }
                // No background here: the canvas stays transparent and the <video> behind it shows through.
                is Route.Player -> PlayerPage(app, route.id)
                is Route.NotFound -> Shell(app) { Text("Nothing here.", color = Muted, modifier = Modifier.padding(24.dp)) }
            }
        }
    }
}

/** A Compose-drawn [content] with a real `<a href>` over it. */
@Composable
fun HtmlLink(app: WebApp, route: Route, label: String, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier) {
        content()
        HtmlElementView(
            factory = { createLinkOverlay { href -> app.router.navigate(Route.parse(href)) } },
            modifier = Modifier.matchParentSize(),
            update = { setLink(it, route.path, label) },
        )
    }
}

@Composable
fun Shell(app: WebApp, content: @Composable () -> Unit) {
    val session by app.graph.session.collectAsState()
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().background(SurfaceColor).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HtmlLink(app, Route.Home, "Current") { Text("Current", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            Spacer(Modifier.weight(1f))
            Text(session?.userName.orEmpty(), color = Muted)
            Button(onClick = { app.graph.signOut() }) { Text("Sign out") }
        }
        content()
    }
}

@Composable
fun SignInPage(app: WebApp) {
    val vm = rememberViewModel("sign-in") { holdViewModel { app.graph.signInViewModel() } }
    val state by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().background(Bg).padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Sign in to Jellyfin", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(16.dp))
        HtmlElementView(
            factory = { createSignInForm(state.serverUrl) { server, user, password -> vm.signIn(server, user, password) } },
            // Compose sets the element's size; the form cannot size itself, so this must fit its content.
            modifier = Modifier.width(360.dp).height(300.dp),
            update = { setFormBusy(it, state.busy) },
        )
        state.error?.let {
            Text(it, color = ErrorColor, modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Composable
fun <T> Loaded(state: LoadState<T>, content: @Composable (T) -> Unit) {
    when (state) {
        LoadState.Loading -> Text("Loading…", color = Muted, modifier = Modifier.padding(24.dp))
        is LoadState.Failed -> Text(state.message, color = ErrorColor, modifier = Modifier.padding(24.dp))
        is LoadState.Ready -> content(state.value)
    }
}

@Composable
fun HomePage(app: WebApp) {
    val vm = rememberViewModel("home") { holdViewModel { app.graph.homeViewModel() } }
    val state by vm.state.collectAsState()
    Loaded(state) { libraries ->
        val cards = libraries.map { Card(it.id, it.name, null, it.imageUrl, wide = true, route = Route.Library(it.id)) }
        CardGrid(app, cards) { PageTitle("Libraries") }
    }
}

@Composable
fun LibraryPage(app: WebApp, id: String) {
    val vm = rememberViewModel(id) { holdViewModel { app.graph.libraryViewModel(id) } }
    val state by vm.state.collectAsState()
    Loaded(state.items) { items -> CardGrid(app, items.map { it.toCard() }) { PageTitle(state.title) } }
}

@Composable
fun PageTitle(text: String) = Text(text, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })

data class Card(val id: String, val title: String, val sub: String?, val imageUrl: String?, val wide: Boolean, val route: Route)

fun MediaItem.toCard() = Card(id, name, year?.toString(), imageUrl, imageAspectRatio > 1, Route.Item(id))

@Composable
fun CardGrid(app: WebApp, cards: List<Card>, header: @Composable () -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        contentPadding = PaddingValues(24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { header() }
        items(cards, key = { it.id }) { card ->
            HtmlLink(app, card.route, listOfNotNull(card.title, card.sub).joinToString(" "), Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AsyncImage(
                        model = card.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(if (card.wide) 16f / 9f else 2f / 3f)
                            .clip(RoundedCornerShape(8.dp)).background(SurfaceColor),
                    )
                    Text(card.title, fontSize = 14.sp, maxLines = 2)
                    card.sub?.let { Text(it, fontSize = 12.sp, color = Muted) }
                }
            }
        }
    }
}

@Composable
fun ItemPage(app: WebApp, id: String) {
    val vm = rememberViewModel(id) { holdViewModel { app.graph.itemViewModel(id) } }
    val state by vm.state.collectAsState()
    Loaded(state) { details: ItemDetails ->
        CardGrid(app, details.children.map { it.toCard() }) { ItemHeader(app, details.item) }
    }
}

@Composable
fun ItemHeader(app: WebApp, item: MediaItem) {
    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        item.imageUrl?.let {
            AsyncImage(model = it, contentDescription = null, modifier = Modifier.width(260.dp).clip(RoundedCornerShape(8.dp)))
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(item.name, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text(listOfNotNull(item.seriesName, item.year?.toString(), item.type).joinToString(" · "), color = Muted)
                }
            }
            if (item.isPlayable) {
                HtmlLink(app, Route.Player(item.id), "Play") {
                    Text("▶ Play", color = Color.White, modifier = Modifier.background(Accent, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            item.overview?.let { SelectionContainer { Text(it, lineHeight = 22.sp, modifier = Modifier.width(640.dp)) } }
        }
    }
}
