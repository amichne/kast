package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.contract.InstallationResetRequest
import io.github.amichne.kast.distribution.contract.InstallationResetStorage
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Only the lifecycle owner's held exclusive lock authorizes startup during a persisted activation. */
internal object ResetActivationAdmission {
    private val json = Json { ignoreUnknownKeys = false }
    private const val MAXIMUM_BYTES = 4096L

    fun observe(marker: Path, installation: Path): ResetActivationLease =
        try {
            if (!Files.isRegularFile(marker, NOFOLLOW_LINKS) || Files.size(marker) > MAXIMUM_BYTES)
                return ResetActivationLease.REJECTED
            val bytes =
                Files.newInputStream(marker, READ, NOFOLLOW_LINKS).use { it.readNBytes(MAXIMUM_BYTES.toInt() + 1) }
            if (bytes.size > MAXIMUM_BYTES) return ResetActivationLease.REJECTED
            val record = json.decodeFromString<InstallationResetRequest>(bytes.toString(Charsets.UTF_8))
            if (record.schemaVersion != 1 || record.installationRoot != installation.toString())
                return ResetActivationLease.REJECTED
            when (record.storage) {
                InstallationResetStorage.Activating -> observeLock(marker)
                InstallationResetStorage.Erased,
                InstallationResetStorage.Fenced,
                is InstallationResetStorage.Retained -> ResetActivationLease.ABSENT
            }
        } catch (_: SerializationException) {
            ResetActivationLease.REJECTED
        } catch (_: IOException) {
            ResetActivationLease.REJECTED
        } catch (_: SecurityException) {
            ResetActivationLease.REJECTED
        }

    private fun observeLock(marker: Path): ResetActivationLease {
        val path = marker.resolveSibling(marker.fileName.toString() + ".lock")
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            val lock =
                try {
                    channel.tryLock(0, Long.MAX_VALUE, true)
                } catch (_: OverlappingFileLockException) {
                    return ResetActivationLease.HELD
                }
            if (lock == null) return ResetActivationLease.HELD
            lock.release()
            return ResetActivationLease.ABSENT
        }
    }
}

internal enum class ResetActivationLease {
    HELD,
    ABSENT,
    REJECTED,
}
