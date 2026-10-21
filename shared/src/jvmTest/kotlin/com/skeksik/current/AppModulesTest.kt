package com.skeksik.current

import com.skeksik.current.core.database.SecretStore
import com.skeksik.current.core.network.CapabilitiesState
import com.skeksik.current.core.network.LocalNetworkAccess
import com.skeksik.current.core.network.MutableCapabilitiesState
import com.skeksik.current.core.network.ServerAddressSources
import com.skeksik.current.core.network.ServerProbe
import com.skeksik.current.core.network.ServerRepository
import com.skeksik.current.data.jellyfin.JellyfinAccounts
import com.skeksik.current.ui.navigation.ShellDestination
import com.skeksik.current.ui.navigation.ShellGraph
import org.koin.core.Koin
import org.koin.core.annotation.KoinInternalApi
import org.koin.dsl.koinApplication
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app's Koin graph, as desktop starts it (no platform modules): a missing binding fails here rather than when a page opens. Bindings
 * are looked up, not created, so no store, keyring or network is touched.
 */
class AppModulesTest {
    // No eager instances (false): the server repository is created at start, and would read the settings store.
    private val koin = koinApplication(false) { modules(appModules) }.koin

    /** What each page's ViewModel takes from Koin (`pageViewModel { ... get() ... }` in composeUi). Keep in step with the pages. */
    private val pageNeeds: Map<String, List<KClass<*>>> = mapOf(
        "Home" to listOf(ServerRepository::class, CapabilitiesState::class, LocalNetworkAccess::class),
        "Settings" to listOf(ServerRepository::class, CapabilitiesState::class, JellyfinAccounts::class, SecretStore::class),
        "Add server" to listOf(ServerProbe::class, ServerRepository::class, MutableCapabilitiesState::class, LocalNetworkAccess::class),
        "Sign in" to listOf(JellyfinAccounts::class),
        "Addresses" to listOf(ServerRepository::class, ServerAddressSources::class, CapabilitiesState::class),
    )

    @Test
    fun everyTypeAPageTakesIsBound() {
        val missing = pageNeeds.flatMap { (page, types) -> types.filterNot(koin::binds).map { "$page: ${it.simpleName}" } }
        assertTrue(missing.isEmpty(), "Not bound in appModules: $missing")
    }

    @Test
    fun theShellGetsEveryDestination() {
        val graph = ShellGraph(koin.getAll<ShellDestination>())
        assertEquals(2, graph.sidebar.size)
    }
}

@OptIn(KoinInternalApi::class)
private fun Koin.binds(type: KClass<*>): Boolean =
    instanceRegistry.instances.values.any { it.beanDefinition.qualifier == null && it.beanDefinition.hasType(type) }
