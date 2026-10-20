package com.skeksik.current.core.database

import kotlin.random.Random

/**
 * Who this install is to servers (ADR 0017). [deviceId] is random, made once per install and kept in the machine-local store, never in
 * settings that roam or get restored elsewhere: Jellyfin revokes a token when the same device id signs in again, so two machines
 * sharing one id would keep signing each other out. [deviceName] is what server dashboards show.
 */
class DeviceIdentity(val deviceId: String, val deviceName: String) {
    companion object {
        internal const val DEVICE_ID_KEY = "deviceId"

        /** The install's id from [local], made and saved on first use. */
        fun load(local: KeyValueStore, deviceName: String): DeviceIdentity {
            val id = local.get(DEVICE_ID_KEY)?.takeIf { it.isNotBlank() } ?: newDeviceId().also { local.put(DEVICE_ID_KEY, it) }
            return DeviceIdentity(id, deviceName.ifBlank { "Unknown device" })
        }

        private fun newDeviceId(): String =
            Random.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
