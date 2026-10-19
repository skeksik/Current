package com.skeksik.current.core.network

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.java.Java

internal actual val platformEngine: HttpClientEngineFactory<*> = Java
