package com.skeksik.current.core.database

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The Secret Service on Linux (GNOME Keyring, KWallet) through the system's `libsecret`, which the app does not ship. Items use the
 * schema `com.skeksik.current.Secret` with one attribute, `key`, and go to the default collection (usually the login keyring, which
 * the desktop unlocks at sign-in). Users see them as "Current: <key>" in their passwords app.
 *
 * A call can wait on the user: the keyring daemon may ask to unlock or create a keyring. Calls run on one worker thread and give up
 * after [timeout]; the caller then keeps values in memory ([DesktopSecretStore]) and stops calling, since the worker may stay blocked.
 */
internal class LibSecret private constructor(
    private val secret: LibSecretApi,
    private val glib: GLibApi,
    private val timeout: Duration,
) : SecretBackend {
    override val storage = SecretStorage.SecretService

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "current-secret-service").apply { isDaemon = true } }

    override fun get(key: String): String? = timed { lookup(key) }

    override fun put(key: String, value: String) = timed { store(key, value) }

    override fun remove(key: String) = timed { clear(key) }

    private fun <T> timed(action: () -> T): T {
        val call = worker.submit(Callable(action))
        try {
            return call.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            call.cancel(true)
            throw SecretBackendException("The Secret Service did not answer within $timeout")
        } catch (e: ExecutionException) {
            throw e.cause as? SecretBackendException ?: SecretBackendException("The Secret Service failed: ${e.cause}")
        }
    }

    private fun lookup(key: String): String? {
        val error = PointerByReference()
        val value = secret.secret_password_lookup_sync(SCHEMA, null, error, KEY_ATTRIBUTE, key, null)
        check(error)
        if (value == null) return null
        try {
            return value.getString(0, "UTF-8")
        } finally {
            secret.secret_password_free(value)
        }
    }

    private fun store(key: String, value: String) {
        val error = PointerByReference()
        val stored = secret.secret_password_store_sync(SCHEMA, DEFAULT_COLLECTION, "Current: $key", value, null, error, KEY_ATTRIBUTE, key, null)
        check(error)
        if (!stored) throw SecretBackendException("The Secret Service did not save the value")
    }

    private fun clear(key: String) {
        val error = PointerByReference()
        secret.secret_password_clear_sync(SCHEMA, null, error, KEY_ATTRIBUTE, key, null)
        check(error)
    }

    /** Throws for a `GError` the call set, and frees it. */
    private fun check(error: PointerByReference) {
        val pointer = error.value ?: return
        val message = GError(pointer).apply { read() }.message ?: "unknown error"
        glib.g_error_free(pointer)
        throw SecretBackendException("The Secret Service failed: $message")
    }

    @Suppress("FunctionName")
    internal interface LibSecretApi : Library {
        fun secret_password_store_sync(
            schema: SecretSchema, collection: String?, label: String, password: String, cancellable: Pointer?, error: PointerByReference,
            vararg attributes: Any?,
        ): Boolean

        fun secret_password_lookup_sync(schema: SecretSchema, cancellable: Pointer?, error: PointerByReference, vararg attributes: Any?): Pointer?

        fun secret_password_clear_sync(schema: SecretSchema, cancellable: Pointer?, error: PointerByReference, vararg attributes: Any?): Boolean

        fun secret_password_free(password: Pointer)
    }

    @Suppress("FunctionName")
    internal interface GLibApi : Library {
        fun g_error_free(error: Pointer)
    }

    /** `SecretSchemaAttribute`. */
    @Structure.FieldOrder("name", "type")
    internal open class SecretSchemaAttribute : Structure() {
        @JvmField var name: String? = null
        @JvmField var type: Int = 0
    }

    /** `SecretSchema`: a name, flags, 32 attributes (unused ones zeroed) and reserved fields. */
    @Structure.FieldOrder(
        "name", "flags", "attributes", "reserved", "reserved1", "reserved2", "reserved3", "reserved4", "reserved5", "reserved6",
        "reserved7",
    )
    internal class SecretSchema : Structure() {
        @JvmField var name: String? = null
        @JvmField var flags: Int = 0

        @Suppress("UNCHECKED_CAST")
        @JvmField var attributes: Array<SecretSchemaAttribute> = SecretSchemaAttribute().toArray(32) as Array<SecretSchemaAttribute>
        @JvmField var reserved: Int = 0
        @JvmField var reserved1: Pointer? = null
        @JvmField var reserved2: Pointer? = null
        @JvmField var reserved3: Pointer? = null
        @JvmField var reserved4: Pointer? = null
        @JvmField var reserved5: Pointer? = null
        @JvmField var reserved6: Pointer? = null
        @JvmField var reserved7: Pointer? = null
    }

    /** `GError`. */
    @Structure.FieldOrder("domain", "code", "message")
    internal class GError(pointer: Pointer) : Structure(pointer) {
        @JvmField var domain: Int = 0
        @JvmField var code: Int = 0
        @JvmField var message: String? = null
    }

    companion object {
        private const val DEFAULT_COLLECTION = "default"
        private const val KEY_ATTRIBUTE = "key"
        private const val SECRET_SCHEMA_NONE = 0
        private const val SECRET_SCHEMA_ATTRIBUTE_STRING = 0

        /** Kept for the life of the process: libsecret reads the schema on every call. */
        private val SCHEMA = SecretSchema().apply {
            name = "com.skeksik.current.Secret"
            flags = SECRET_SCHEMA_NONE
            attributes[0].name = KEY_ATTRIBUTE
            attributes[0].type = SECRET_SCHEMA_ATTRIBUTE_STRING
            write()
        }

        /**
         * Null without a D-Bus session bus or without `libsecret`. Nothing is called here: a lookup is no test (with no bus it returns
         * nothing and no error), and any call may start a keyring daemon that prompts the user.
         */
        fun openOrNull(timeout: Duration = 2.minutes): LibSecret? {
            if (!sessionBusAvailable()) return null
            return try {
                val secret = Native.load("libsecret-1.so.0", LibSecretApi::class.java)
                val glib = Native.load("libglib-2.0.so.0", GLibApi::class.java)
                LibSecret(secret, glib, timeout)
            } catch (e: UnsatisfiedLinkError) {
                null
            }
        }

        /** The bus GDBus would use: `DBUS_SESSION_BUS_ADDRESS`, or the per-user bus at `$XDG_RUNTIME_DIR/bus`. */
        private fun sessionBusAvailable(): Boolean {
            if (!System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank()) return true
            val runtime = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() } ?: return false
            return Files.exists(Path.of(runtime, "bus"))
        }
    }
}
