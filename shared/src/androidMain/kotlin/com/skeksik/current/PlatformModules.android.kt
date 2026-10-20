package com.skeksik.current

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.koin.core.module.Module
import org.koin.dsl.module

/** The application `Context`, which `core:database` needs for its `SharedPreferences` store. */
@Composable
internal actual fun platformModules(): List<Module> {
    val context = LocalContext.current.applicationContext
    return remember(context) { listOf(module { single<Context> { context } }) }
}
