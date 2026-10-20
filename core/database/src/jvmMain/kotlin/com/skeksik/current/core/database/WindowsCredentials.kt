package com.skeksik.current.core.database

import com.sun.jna.LastErrorException
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.ptr.PointerByReference

/**
 * Windows Credential Manager (`CredWriteW`, `CredReadW`, `CredDeleteW` in advapi32): generic credentials named `Current/<key>`, kept on
 * this machine only (`CRED_PERSIST_LOCAL_MACHINE`), visible to the user under Control Panel > Credential Manager > Windows Credentials.
 * Values are UTF-8, at most 2560 bytes (`CRED_MAX_CREDENTIAL_BLOB_SIZE`).
 */
internal class WindowsCredentials private constructor(
    private val api: Advapi32Credentials,
    private val prefix: String,
) : SecretBackend {
    override fun get(key: String): String? {
        val result = PointerByReference()
        if (!call { api.CredReadW(WString(prefix + key), CRED_TYPE_GENERIC, 0, result) }) return null
        val pointer = result.value
        try {
            val credential = Credential(pointer).apply { read() }
            val size = credential.CredentialBlobSize
            return if (size == 0) "" else credential.CredentialBlob!!.getByteArray(0, size).decodeToString()
        } finally {
            api.CredFree(pointer)
        }
    }

    override fun put(key: String, value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= MAX_BLOB_SIZE) { "A credential holds at most $MAX_BLOB_SIZE bytes" }
        val blob = Memory(maxOf(bytes.size, 1).toLong()).apply { write(0, bytes, 0, bytes.size) }
        val credential = Credential().apply {
            Type = CRED_TYPE_GENERIC
            TargetName = WString(prefix + key)
            Comment = WString("Current")
            CredentialBlobSize = bytes.size
            CredentialBlob = blob
            Persist = CRED_PERSIST_LOCAL_MACHINE
            UserName = WString(key)
        }
        if (!call { api.CredWriteW(credential, 0) }) throw SecretBackendException("Credential Manager did not save the value")
    }

    override fun remove(key: String) {
        call { api.CredDeleteW(WString(prefix + key), CRED_TYPE_GENERIC, 0) }
    }

    /** Runs a call that sets the last error; false for `ERROR_NOT_FOUND`, an exception for any other failure. */
    private fun call(action: () -> Boolean): Boolean = try {
        action()
    } catch (e: LastErrorException) {
        if (e.errorCode == ERROR_NOT_FOUND) false else throw SecretBackendException("Credential Manager failed with error ${e.errorCode}")
    }

    @Suppress("FunctionName")
    internal interface Advapi32Credentials : Library {
        @Throws(LastErrorException::class)
        fun CredWriteW(credential: Credential, flags: Int): Boolean

        @Throws(LastErrorException::class)
        fun CredReadW(targetName: WString, type: Int, flags: Int, credential: PointerByReference): Boolean

        @Throws(LastErrorException::class)
        fun CredDeleteW(targetName: WString, type: Int, flags: Int): Boolean

        fun CredFree(buffer: Pointer)
    }

    /** `CREDENTIALW`. */
    @Suppress("PropertyName")
    @Structure.FieldOrder(
        "Flags", "Type", "TargetName", "Comment", "LastWritten", "CredentialBlobSize", "CredentialBlob", "Persist",
        "AttributeCount", "Attributes", "TargetAlias", "UserName",
    )
    internal class Credential(pointer: Pointer? = null) : Structure(pointer) {
        @JvmField var Flags: Int = 0
        @JvmField var Type: Int = 0
        @JvmField var TargetName: WString? = null
        @JvmField var Comment: WString? = null
        @JvmField var LastWritten: WinBase.FILETIME = WinBase.FILETIME()
        @JvmField var CredentialBlobSize: Int = 0
        @JvmField var CredentialBlob: Pointer? = null
        @JvmField var Persist: Int = 0
        @JvmField var AttributeCount: Int = 0
        @JvmField var Attributes: Pointer? = null
        @JvmField var TargetAlias: WString? = null
        @JvmField var UserName: WString? = null
    }

    companion object {
        private const val CRED_TYPE_GENERIC = 1
        private const val CRED_PERSIST_LOCAL_MACHINE = 2
        private const val ERROR_NOT_FOUND = 1168
        private const val MAX_BLOB_SIZE = 5 * 512

        /** Null when not on Windows or advapi32 cannot be loaded. [prefix] is for tests. */
        fun openOrNull(prefix: String = "Current/"): WindowsCredentials? = try {
            WindowsCredentials(Native.load("Advapi32", Advapi32Credentials::class.java), prefix)
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }
}
