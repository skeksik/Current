package com.skeksik.current.web

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import com.skeksik.current.core.playback.web.MediaSupport
import com.skeksik.current.feature.settings.PlaybackSupportUiState
import com.skeksik.current.feature.settings.PlaybackSupportViewModel
import kotlin.reflect.KClass

/** Owns one ViewModel for as long as a page shows it; [close] clears it (cancels its scope, calls `onCleared`). */
internal class ViewModelHolder<VM : ViewModel>(kClass: KClass<VM>, create: () -> VM) : AutoCloseable {
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

internal inline fun <reified VM : ViewModel> holdViewModel(noinline create: () -> VM) = ViewModelHolder(VM::class, create)

/**
 * A page's ViewModel. React creates it in an effect and calls [dispose] in the effect's cleanup, so StrictMode's double mount
 * disposes the first one and creates another rather than reusing a disposed one.
 */
@JsExport
abstract class Page internal constructor(private val holder: ViewModelHolder<*>) {
    fun dispose() = holder.close()
}

/** A container played as a file, with the codecs played in it (Jellyfin names). */
@JsExport
class ContainerView(val container: String, val video: Array<String>, val audio: Array<String>)

/** The dynamic ranges accepted for one video codec. */
@JsExport
class RangesView(val codec: String, val ranges: Array<String>)

/** What this browser plays, from its probes; [probing] until they have answered. */
@JsExport
class PlaybackSupportView(
    val probing: Boolean,
    val direct: Array<ContainerView>,
    /** Codecs the server may copy into HLS; the first of each is what it encodes to otherwise. */
    val hlsVideo: Array<String>,
    val hlsAudio: Array<String>,
    val tenBit: Array<String>,
    val uhd: Array<String>,
    val ranges: Array<RangesView>,
    val hdrDisplay: Boolean,
    val maxAudioChannels: Int,
    val mse: Boolean,
    val nativeHls: Boolean,
    /** The raw answers in the format of `docs/web-probes/`, to record a browser as a test fixture. */
    val answersJson: String,
)

@JsExport
class PlaybackSupportPage internal constructor(holder: ViewModelHolder<PlaybackSupportViewModel>) : Page(holder) {
    val state: Observable<PlaybackSupportView> = holder.viewModel.state.observe { it.toView() }
}

private fun PlaybackSupportUiState.toView() = when (this) {
    PlaybackSupportUiState.Probing -> PlaybackSupportView(
        probing = true,
        direct = emptyArray(),
        hlsVideo = emptyArray(),
        hlsAudio = emptyArray(),
        tenBit = emptyArray(),
        uhd = emptyArray(),
        ranges = emptyArray(),
        hdrDisplay = false,
        maxAudioChannels = 0,
        mse = false,
        nativeHls = false,
        answersJson = "",
    )
    is PlaybackSupportUiState.Ready -> support.toView()
}

private fun MediaSupport.toView() = PlaybackSupportView(
    probing = false,
    direct = direct.map { ContainerView(it.container, it.video.toTypedArray(), it.audio.toTypedArray()) }.toTypedArray(),
    hlsVideo = hlsVideo.toTypedArray(),
    hlsAudio = hlsAudio.toTypedArray(),
    tenBit = tenBit.toTypedArray(),
    uhd = uhd.toTypedArray(),
    ranges = ranges.map { (codec, ranges) -> RangesView(codec, ranges.toTypedArray()) }.toTypedArray(),
    hdrDisplay = hdrDisplay,
    maxAudioChannels = maxAudioChannels,
    mse = mse,
    nativeHls = nativeHls,
    answersJson = answersJson(),
)
