package com.skeksik.current.feature.settings

import com.skeksik.current.core.playback.web.BrowserProbes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackSupportViewModelTest {
    @Test
    fun probingUntilTheProbesAnswer() = runBlocking {
        val answers = CompletableDeferred<Map<String, String>>()
        val viewModel = PlaybackSupportViewModel({ BrowserProbes.interpret(answers.await()) }, scope = this)
        assertEquals(PlaybackSupportUiState.Probing, viewModel.state.value)

        answers.complete(mapOf("channels" to "6"))
        val ready = withTimeout(5_000) { viewModel.state.first { it is PlaybackSupportUiState.Ready } }
        assertEquals(6, (ready as PlaybackSupportUiState.Ready).support.maxAudioChannels)
    }
}
