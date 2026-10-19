package com.skeksik.current.spike.web

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/** Owns one ViewModel for as long as a page shows it; [close] clears it (cancels its scope, calls `onCleared`). */
class ViewModelHolder<VM : ViewModel>(kClass: KClass<VM>, create: () -> VM) : AutoCloseable {
    private val store = ViewModelStore()

    val viewModel: VM = ViewModelProvider.create(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = create() as T
        },
    )[kClass]

    override fun close() = store.clear()
}

inline fun <reified VM : ViewModel> holdViewModel(noinline create: () -> VM) = ViewModelHolder(VM::class, create)

private fun <T> notSignedIn(): LoadState<T> = LoadState.Failed("Not signed in.")

data class SignInUiState(val serverUrl: String, val busy: Boolean = false, val error: String? = null)

class SignInViewModel internal constructor(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(SignInUiState(serverUrl = graph.lastServerUrl))
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    /** Field values come from the page's own inputs, so password managers fill and save them. */
    fun signIn(serverUrl: String, username: String, password: String) {
        if (_state.value.busy) return
        when {
            serverUrl.isBlank() -> _state.update { it.copy(error = "Enter the server address.") }
            username.isBlank() -> _state.update { it.copy(error = "Enter your username.") }
            else -> {
                val server = normalizeServerUrl(serverUrl)
                _state.update { it.copy(serverUrl = server, busy = true, error = null) }
                viewModelScope.launch {
                    attempt { graph.api(server, null).authenticateByName(username.trim(), password) }
                        .onSuccess { graph.signedIn(it) }
                        .onFailure { e -> _state.update { it.copy(error = e.userMessage()) } }
                    _state.update { it.copy(busy = false) }
                }
            }
        }
    }
}

class HomeViewModel internal constructor(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow<LoadState<List<Library>>>(LoadState.Loading)
    val state: StateFlow<LoadState<List<Library>>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        val session = graph.session.value ?: return run { _state.value = notSignedIn() }
        _state.value = LoadState.Loading
        viewModelScope.launch {
            _state.value = attempt { graph.api(session).userViews(session.userId) }
                .fold({ LoadState.Ready(it) }, { LoadState.Failed(it.userMessage()) })
        }
    }
}

data class LibraryUiState(val title: String = "", val items: LoadState<List<MediaItem>> = LoadState.Loading)

class LibraryViewModel internal constructor(private val graph: AppGraph, private val libraryId: String) : ViewModel() {
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        val session = graph.session.value ?: return run { _state.value = LibraryUiState(items = notSignedIn()) }
        val api = graph.api(session)
        _state.update { it.copy(items = LoadState.Loading) }
        viewModelScope.launch {
            val title = async { attempt { api.item(session.userId, libraryId).name }.getOrNull() }
            val items = attempt { api.children(session.userId, libraryId) }
                .fold({ LoadState.Ready(it) }, { LoadState.Failed(it.userMessage()) })
            _state.value = LibraryUiState(title.await().orEmpty(), items)
        }
    }
}

data class ItemDetails(val item: MediaItem, val children: List<MediaItem>)

class ItemViewModel internal constructor(private val graph: AppGraph, private val itemId: String) : ViewModel() {
    private val _state = MutableStateFlow<LoadState<ItemDetails>>(LoadState.Loading)
    val state: StateFlow<LoadState<ItemDetails>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        val session = graph.session.value ?: return run { _state.value = notSignedIn() }
        val api = graph.api(session)
        _state.value = LoadState.Loading
        viewModelScope.launch {
            _state.value = attempt {
                val item = api.item(session.userId, itemId)
                val children = if (item.isFolder) api.children(session.userId, itemId) else emptyList()
                ItemDetails(item, children)
            }.fold({ LoadState.Ready(it) }, { LoadState.Failed(it.userMessage()) })
        }
    }
}
