package com.skeksik.current.web

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

internal val mainScope = MainScope()

/**
 * A `StateFlow` for React's `useSyncExternalStore`: [getSnapshot] returns the same object until the flow emits a new value (React
 * needs a stable snapshot), and [subscribe] returns the unsubscribe function. The snapshot is the flow's value mapped to an exported
 * view class, since `StateFlow` and most Kotlin types cannot be exported.
 */
@JsExport
class Observable<T> internal constructor(private val flow: StateFlow<Any?>, private val map: (Any?) -> T) {
    private var source: Any? = Unset
    private var snapshot: T? = null

    fun getSnapshot(): T {
        val value = flow.value
        if (value !== source) {
            source = value
            snapshot = map(value)
        }
        @Suppress("UNCHECKED_CAST")
        return snapshot as T
    }

    fun subscribe(listener: () -> Unit): () -> Unit {
        // Undispatched, so the collector holds the current value before this returns: React reads the snapshot again right after
        // subscribing, and a value set in between is then reported rather than dropped.
        val job = mainScope.launch(start = CoroutineStart.UNDISPATCHED) { flow.drop(1).collect { listener() } }
        return { job.cancel() }
    }

    private object Unset
}

@Suppress("UNCHECKED_CAST")
internal fun <S, T> StateFlow<S>.observe(map: (S) -> T): Observable<T> = Observable(this as StateFlow<Any?>) { map(it as S) }
