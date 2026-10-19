package com.skeksik.current.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeksik.current.core.playback.web.MediaSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Settings > Playback support: what this device plays without the server, as its probes found (ADR 0007's diagnostics). */
sealed interface PlaybackSupportUiState {
    data object Probing : PlaybackSupportUiState

    data class Ready(val support: MediaSupport) : PlaybackSupportUiState
}

/** [probe] answers what this device plays; on the web, the browser probes run once per page load (ADR 0011). */
class PlaybackSupportViewModel internal constructor(
    private val probe: suspend () -> MediaSupport,
    /** For tests, which have no main dispatcher; otherwise `viewModelScope`. */
    scope: CoroutineScope?,
) : ViewModel() {
    constructor(probe: suspend () -> MediaSupport) : this(probe, scope = null)

    private val scope = scope ?: viewModelScope

    private val _state = MutableStateFlow<PlaybackSupportUiState>(PlaybackSupportUiState.Probing)
    val state: StateFlow<PlaybackSupportUiState> = _state.asStateFlow()

    init {
        this.scope.launch { _state.value = PlaybackSupportUiState.Ready(probe()) }
    }
}
