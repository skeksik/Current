package com.skeksik.current.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import org.koin.compose.getKoin
import org.koin.core.Koin

/**
 * The page's ViewModel, made once per back stack entry from Koin's bindings ([create] calls `get()` for each constructor argument) and
 * cleared when the page leaves the back stack.
 */
@Composable
inline fun <reified VM : ViewModel> pageViewModel(noinline create: Koin.() -> VM): VM {
    val koin = getKoin()
    return viewModel { koin.create() }
}
