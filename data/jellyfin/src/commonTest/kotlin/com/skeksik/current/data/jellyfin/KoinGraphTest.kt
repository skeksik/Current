package com.skeksik.current.data.jellyfin

import com.skeksik.current.core.database.DeviceIdentity
import com.skeksik.current.core.database.InMemoryKeyValueStore
import com.skeksik.current.core.database.InMemorySecretStore
import com.skeksik.current.core.database.KeyValueStore
import com.skeksik.current.core.database.SecretStore
import com.skeksik.current.core.database.databaseModule
import com.skeksik.current.core.network.AppScope
import com.skeksik.current.core.network.ServerProbe
import com.skeksik.current.core.network.ServerRepository
import com.skeksik.current.core.network.networkModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.module.Module
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/** The modules `App()` starts resolve together, with the platform stores replaced by in-memory ones. */
class KoinGraphTest {
    @Test
    fun theAppModulesResolve() {
        val settings = InMemoryKeyValueStore()
        val local = InMemoryKeyValueStore()
        val stores = module {
            single<KeyValueStore> { settings }
            single<KeyValueStore>(named("local")) { local }
            single<SecretStore> { InMemorySecretStore() }
        }
        val app = koinApplication { modules(databaseModule, networkModule, jellyfinModule, stores) }
        try {
            val koin = app.koin
            assertEquals(settings, koin.get<KeyValueStore>())
            assertNotSame(koin.get<KeyValueStore>(), koin.get<KeyValueStore>(named("local")))
            val device = koin.get<DeviceIdentity>()
            assertEquals(device.deviceId, local.get("deviceId"))
            koin.get<CoroutineScope>(AppScope)
            koin.get<ServerRepository>()
            koin.get<ServerProbe>()
            koin.get<JellyfinAccounts>()
        } finally {
            app.close()
        }
    }

    /**
     * Qualifiers must be set when Koin builds the module. One declared below its module is still null then, and the definition silently
     * loses it (the machine-local store once replaced the settings store that way).
     */
    @Test
    fun qualifiedDefinitionsKeepTheirQualifiers() {
        assertTrue(databaseModule.defines(KeyValueStore::class, named("local")))
        assertTrue(databaseModule.defines(KeyValueStore::class, null))
        assertTrue(networkModule.defines(CoroutineScope::class, AppScope))
    }

    @OptIn(KoinInternalApi::class)
    private fun Module.defines(type: kotlin.reflect.KClass<*>, qualifier: Qualifier?): Boolean =
        mappings.values.any { it.beanDefinition.primaryType == type && it.beanDefinition.qualifier == qualifier }
}
