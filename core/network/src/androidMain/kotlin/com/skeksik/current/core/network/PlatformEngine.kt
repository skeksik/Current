package com.skeksik.current.core.network

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.android.Android

internal actual val platformEngine: HttpClientEngineFactory<*> = Android
