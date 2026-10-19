package com.skeksik.current.spike.desktopvideo

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.WString
import java.io.File

/**
 * The part of libmpv's client API (include/mpv/client.h) the spike needs. Strings are UTF-8, as libmpv expects.
 * Handles are opaque pointers; events are read straight from memory (see [MpvEvent]).
 */
@Suppress("FunctionName")
interface LibMpv : Library {
    fun mpv_client_api_version(): NativeLong
    fun mpv_error_string(error: Int): String
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_observe_property(ctx: Pointer, replyUserdata: Long, name: String, format: Int): Int
    fun mpv_request_log_messages(ctx: Pointer, minLevel: String): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(ctx: Pointer)
    fun mpv_free(data: Pointer)
}

@Suppress("FunctionName")
private interface Kernel32 : Library {
    fun LoadLibraryExW(path: WString, file: Pointer?, flags: Int): Pointer?
}

// LoadLibraryExW flags: resolve the DLL's dependencies from its own folder first, then the default system folders.
private const val LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR = 0x100
private const val LOAD_LIBRARY_SEARCH_DEFAULT_DIRS = 0x1000

object Mpv {
    const val FORMAT_NONE = 0
    const val FORMAT_STRING = 1
    const val FORMAT_FLAG = 3
    const val FORMAT_INT64 = 4
    const val FORMAT_DOUBLE = 5

    const val EVENT_SHUTDOWN = 1
    const val EVENT_LOG_MESSAGE = 2
    const val EVENT_START_FILE = 6
    const val EVENT_END_FILE = 7
    const val EVENT_FILE_LOADED = 8
    const val EVENT_VIDEO_RECONFIG = 17
    const val EVENT_AUDIO_RECONFIG = 18
    const val EVENT_SEEK = 20
    const val EVENT_PLAYBACK_RESTART = 21
    const val EVENT_PROPERTY_CHANGE = 22

    const val END_FILE_REASON_ERROR = 4

    private val isWindows = System.getProperty("os.name").startsWith("Windows")

    /**
     * Loads libmpv once. On Windows it loads `libmpv-2.dll` from [SpikePaths.mpvDir], resolving the DLLs next to it (FFmpeg,
     * libass, libplacebo...) for this load only: a process-wide `SetDllDirectory` would also let later DLL loads elsewhere
     * in the JVM (GPU drivers, Skia) pick up mpv's copies of common DLLs. Elsewhere (only used for a headless smoke test)
     * it loads the system libmpv.
     */
    val lib: LibMpv by lazy {
        val options = mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")
        if (isWindows) {
            val dir = SpikePaths.mpvDir
            val dll = File(dir, "libmpv-2.dll")
            require(dll.isFile) { "libmpv-2.dll not found in ${dir.absolutePath} (run with .\\gradlew.bat, or pass -PmpvDir=...)" }
            val module = Native.load("kernel32", Kernel32::class.java)
                .LoadLibraryExW(WString(dll.absolutePath), null, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR or LOAD_LIBRARY_SEARCH_DEFAULT_DIRS)
            checkNotNull(module) { "LoadLibraryExW failed for ${dll.absolutePath} (error ${Native.getLastError()})" }
            Diagnostics.event("loaded ${dll.absolutePath}")
            // Already loaded above, so this only binds the functions.
            Native.load(dll.absolutePath, LibMpv::class.java, options)
        } else {
            Native.load(System.getProperty("spike.mpvLib") ?: "mpv", LibMpv::class.java, options)
        }
    }
}

/** A view of `mpv_event` (x64: int32 event_id, int32 error, uint64 reply_userdata, void* data). */
class MpvEvent(private val p: Pointer) {
    val id: Int get() = p.getInt(0)
    val error: Int get() = p.getInt(4)
    val replyUserdata: Long get() = p.getLong(8)
    private val data: Pointer? get() = p.getPointer(16)

    /** `mpv_event_property`: const char* name, int32 format, void* data. */
    val propertyName: String get() = data!!.getPointer(0).getString(0, "UTF-8")
    val propertyFormat: Int get() = data!!.getInt(8)
    private val propertyData: Pointer? get() = data!!.getPointer(16)
    val propertyFlag: Boolean? get() = propertyData?.takeIf { propertyFormat == Mpv.FORMAT_FLAG }?.getInt(0)?.let { it != 0 }
    val propertyDouble: Double? get() = propertyData?.takeIf { propertyFormat == Mpv.FORMAT_DOUBLE }?.getDouble(0)
    val propertyLong: Long? get() = propertyData?.takeIf { propertyFormat == Mpv.FORMAT_INT64 }?.getLong(0)
    val propertyString: String? get() = propertyData?.takeIf { propertyFormat == Mpv.FORMAT_STRING }?.getPointer(0)?.getString(0, "UTF-8")

    /** `mpv_event_log_message`: const char* prefix, level, text; int32 log_level. */
    val logPrefix: String get() = data!!.getPointer(0).getString(0, "UTF-8")
    val logLevel: String get() = data!!.getPointer(8).getString(0, "UTF-8")
    val logText: String get() = data!!.getPointer(16).getString(0, "UTF-8")

    /** `mpv_event_end_file`: int32 reason, int32 error, ... */
    val endFileReason: Int get() = data!!.getInt(0)
    val endFileError: Int get() = data!!.getInt(4)
}
