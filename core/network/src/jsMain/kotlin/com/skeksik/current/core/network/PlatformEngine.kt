package com.skeksik.current.core.network

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.js.Js

internal actual val platformEngine: HttpClientEngineFactory<*> = Js
