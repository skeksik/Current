package com.skeksik.current

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.skeksik.current.core.database.databaseModule
import com.skeksik.current.core.network.networkModule
import com.skeksik.current.data.jellyfin.jellyfinModule
import kotlinx.serialization.Serializable
import org.koin.compose.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.koinConfiguration

@Serializable
data object HomeRoute

@Composable
@Preview
fun App() {
    val platformModules = platformModules()
    KoinApplication(configuration = koinConfiguration { modules(platformModules + databaseModule + networkModule + jellyfinModule) }) {
        MaterialTheme {
            NavHost(rememberNavController(), startDestination = HomeRoute) {
                composable<HomeRoute> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Current")
                    }
                }
            }
        }
    }
}

/** Definitions only the platform can make, such as Android's application `Context`. */
@Composable
internal expect fun platformModules(): List<Module>
