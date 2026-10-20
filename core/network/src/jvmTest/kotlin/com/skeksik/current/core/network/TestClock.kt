package com.skeksik.current.core.network

import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.yield

/** Time that moves only when a test says so. Pass [sleep] where code waits; [advance] wakes every sleeper that is due. */
class TestClock {
    private class Sleeper(val until: Duration, val done: CompletableDeferred<Unit>)

    var now: Duration = Duration.ZERO
        private set

    private val sleepers = mutableListOf<Sleeper>()

    val sleeping: Int get() = sleepers.size

    suspend fun sleep(duration: Duration) {
        val sleeper = Sleeper(now + duration, CompletableDeferred())
        sleepers += sleeper
        try {
            sleeper.done.await()
        } finally {
            sleepers -= sleeper
        }
    }

    /** Moves time on and lets whatever woke up run until it waits again. */
    suspend fun advance(by: Duration) {
        now += by
        sleepers.filter { it.until <= now }.forEach { it.done.complete(Unit) }
        settle()
    }
}

/** Lets every coroutine that can run on this single-threaded test dispatcher run until it waits. */
suspend fun settle() = repeat(100) { yield() }
