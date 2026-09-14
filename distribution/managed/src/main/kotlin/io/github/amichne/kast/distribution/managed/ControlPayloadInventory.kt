package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.distribution.contract.ControlDistributionLimits
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ControlInventoryResource {
    TRAVERSED_ENTRIES,
    PAYLOAD_FILES,
    PAYLOAD_BYTES,
}

@Serializable
enum class ControlInventoryBoundary {
    INSTALLER,
    RUNTIME_IDENTITY,
}

@Serializable
enum class ControlInventoryFailure {
    LIMIT_EXCEEDED,
    UNSUPPORTED_ENTRY,
    IO_REJECTED,
}

@Serializable
data class ControlLimitExceeded(val resource: ControlInventoryResource, val maximum: Long, val observedAtLeast: Long)

@Serializable
data class ControlInventoryObservation(
    val boundary: ControlInventoryBoundary,
    val failure: ControlInventoryFailure,
    val limit: ControlLimitExceeded? = null,
)

@Serializable
private data class ControlInventoryAccepted(
    val boundary: ControlInventoryBoundary,
    val traversedEntries: Int,
    val payloadFiles: Int,
    val payloadBytes: Long,
)

sealed interface ControlInventoryAdmission {
    /** Paths retain the historical bin/lib/share ordering, sorted within each tree. */
    data class Admitted(val files: List<Path>, val traversedEntries: Int, val bytes: Long) : ControlInventoryAdmission {
        fun report(boundary: ControlInventoryBoundary) {
            System.err.println(
                Json.encodeToString(
                    ControlInventoryAccepted.serializer(),
                    ControlInventoryAccepted(boundary, traversedEntries, files.size, bytes),
                )
            )
        }
    }

    data class Rejected(val failure: ControlInventoryFailure, val limit: ControlLimitExceeded? = null) :
        ControlInventoryAdmission {
        fun report(boundary: ControlInventoryBoundary) {
            System.err.println(
                Json.encodeToString(
                    ControlInventoryObservation.serializer(),
                    ControlInventoryObservation(boundary, failure, limit),
                )
            )
        }
    }
}

/** Traversal counts every path including the three payload roots, across all trees, before sorting or hashing. */
object ControlPayloadInventory {
    fun admit(root: Path): ControlInventoryAdmission =
        try {
            val collection = PayloadCollection()
            collection.collect(root)
        } catch (_: IOException) {
            ControlInventoryAdmission.Rejected(ControlInventoryFailure.IO_REJECTED)
        } catch (_: java.io.UncheckedIOException) {
            ControlInventoryAdmission.Rejected(ControlInventoryFailure.IO_REJECTED)
        } catch (_: SecurityException) {
            ControlInventoryAdmission.Rejected(ControlInventoryFailure.IO_REJECTED)
        }

    fun exceeded(
        resource: ControlInventoryResource,
        maximum: Long,
        observedAtLeast: Long,
    ): ControlInventoryAdmission.Rejected =
        ControlInventoryAdmission.Rejected(
            ControlInventoryFailure.LIMIT_EXCEEDED,
            ControlLimitExceeded(resource, maximum, observedAtLeast),
        )
}

private sealed interface InventoryStep {
    data object Accepted : InventoryStep

    data class Rejected(val admission: ControlInventoryAdmission.Rejected) : InventoryStep
}

private class PayloadCollection {
    private var entries = 0
    private var bytes = 0L
    private val files = mutableListOf<Path>()

    fun collect(root: Path): ControlInventoryAdmission {
        for (name in listOf("bin", "lib", "share")) {
            when (val step = tree(root.resolve(name))) {
                InventoryStep.Accepted -> Unit
                is InventoryStep.Rejected -> return step.admission
            }
        }
        return ControlInventoryAdmission.Admitted(files.toList(), entries, bytes)
    }

    private fun tree(root: Path): InventoryStep {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return unsupported()
        val previous = files.size
        Files.walk(root).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                when (val step = entry(iterator.next())) {
                    InventoryStep.Accepted -> Unit
                    is InventoryStep.Rejected -> return step
                }
            }
        }
        files.subList(previous, files.size).sort()
        return InventoryStep.Accepted
    }

    private fun entry(path: Path): InventoryStep {
        entries += 1
        if (entries > ControlDistributionLimits.maximumTraversedEntries) {
            return exceeded(
                ControlInventoryResource.TRAVERSED_ENTRIES,
                ControlDistributionLimits.maximumTraversedEntries.toLong(),
                entries.toLong(),
            )
        }
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (attributes.isDirectory) return InventoryStep.Accepted
        if (!attributes.isRegularFile) return unsupported()
        return file(path, attributes.size())
    }

    private fun file(path: Path, size: Long): InventoryStep {
        val count = files.size + 1
        if (count > ControlDistributionLimits.maximumPayloadFiles) {
            return exceeded(
                ControlInventoryResource.PAYLOAD_FILES,
                ControlDistributionLimits.maximumPayloadFiles.toLong(),
                count.toLong(),
            )
        }
        bytes += size
        if (bytes > ControlDistributionLimits.maximumPayloadBytes) {
            return exceeded(
                ControlInventoryResource.PAYLOAD_BYTES,
                ControlDistributionLimits.maximumPayloadBytes,
                bytes,
            )
        }
        files.add(path)
        return InventoryStep.Accepted
    }

    private fun unsupported() =
        InventoryStep.Rejected(ControlInventoryAdmission.Rejected(ControlInventoryFailure.UNSUPPORTED_ENTRY))

    private fun exceeded(resource: ControlInventoryResource, maximum: Long, observed: Long) =
        InventoryStep.Rejected(ControlPayloadInventory.exceeded(resource, maximum, observed))
}
