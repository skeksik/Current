package com.skeksik.current.feature.settings

import com.skeksik.current.core.playback.web.BrowserProbes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackSupportViewModelTest {
    @Test
    fun probingUntilTheProbesAnswer() = runTest {
        val answers = CompletableDeferred<Map<String, String>>()
        val viewModel = PlaybackSupportViewModel({ BrowserProbes.interpret(answers.await()) }, scope = backgroundScope)
        runCurrent()
        assertEquals(PlaybackSupportUiState.Probing, viewModel.state.value)

        answers.complete(mapOf("channels" to "6"))
        runCurrent()
        val ready = viewModel.state.value
        assertEquals(6, (ready as PlaybackSupportUiState.Ready).support.maxAudioChannels)
    }
}
