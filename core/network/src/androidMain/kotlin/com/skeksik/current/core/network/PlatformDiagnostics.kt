package com.skeksik.current.core.network

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import org.koin.core.scope.Scope

/** The logcat tag of the request log: `adb logcat -s CurrentNet`. */
internal const val LOG_TAG = "CurrentNet"

internal actual fun platformInfo() = PlatformInfo { "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" }

// A library cannot see the app's BuildConfig; a debug build is debuggable and a release build is not.
internal actual fun platformLogEcho(scope: Scope): ((String) -> Unit)? {
    val context = scope.getOrNull<Context>() ?: return null
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
    return { Log.d(LOG_TAG, it) }
}
