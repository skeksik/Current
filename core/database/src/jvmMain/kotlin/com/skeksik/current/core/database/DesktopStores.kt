package com.skeksik.current.core.database

import java.nio.file.Files
import java.nio.file.Path
import org.koin.core.scope.Scope

internal actual fun Scope.platformKeyValueStore(): KeyValueStore = JsonFileKeyValueStore(AppFolders.config().resolve("settings.json"))

internal actual fun Scope.platformLocalStore(): KeyValueStore = JsonFileKeyValueStore(AppFolders.local().resolve("local.json"))

internal actual fun Scope.platformSecretStore(): SecretStore = DesktopSecretStore.create(get(MachineLocal))

/** The computer's name: `COMPUTERNAME` on Windows, the host name on Linux. Read locally, never through DNS. */
internal actual fun platformDeviceName(): String {
    val name = if (AppFolders.isWindows) System.getenv("COMPUTERNAME") else linuxHostName()
    return name?.trim()?.takeIf { it.isNotEmpty() } ?: if (AppFolders.isWindows) "Windows PC" else "Linux PC"
}

private fun linuxHostName(): String? = System.getenv("HOSTNAME")
    ?: Path.of("/etc/hostname").takeIf { Files.isReadable(it) }?.let { Files.readString(it).lineSequence().firstOrNull() }
