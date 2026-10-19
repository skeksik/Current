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
import com.skeksik.current.core.network.networkModule
import kotlinx.serialization.Serializable
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration

@Serializable
data object HomeRoute

@Composable
@Preview
fun App() {
    KoinApplication(configuration = koinConfiguration { modules(networkModule) }) {
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
