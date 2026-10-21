package com.skeksik.current.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import org.koin.compose.getKoin
import org.koin.core.Koin

/**
 * The page's ViewModel, made once per back stack entry from Koin's bindings ([create] calls `get()` for each constructor argument) and
 * cleared when the page leaves the back stack. Pass a [key] when the ViewModel depends on the route's arguments, so an entry reused for
 * other arguments never keeps the old ViewModel.
 */
@Composable
inline fun <reified VM : ViewModel> pageViewModel(key: String? = null, noinline create: Koin.() -> VM): VM {
    val koin = getKoin()
    return viewModel(key = key) { koin.create() }
}
