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
enum class ControlInventoryResource { TRAVERSED_ENTRIES, PAYLOAD_FILES, PAYLOAD_BYTES }

@Serializable
enum class ControlInventoryBoundary { INSTALLER, RUNTIME_IDENTITY }

@Serializable
enum class ControlInventoryFailure { LIMIT_EXCEEDED, UNSUPPORTED_ENTRY, IO_REJECTED }

@Serializable
data class ControlLimitExceeded(val resource: ControlInventoryResource, val maximum: Long, val observedAtLeast: Long)

@Serializable
data class ControlInventoryObservation(
    val boundary: ControlInventoryBoundary,
    val failure: ControlInventoryFailure,
    val limit: ControlLimitExceeded? = null,
)

sealed interface ControlInventoryAdmission {
    /** Paths retain the historical bin/lib/share ordering, sorted within each tree. */
    data class Admitted(val files: List<Path>, val traversedEntries: Int, val bytes: Long) : ControlInventoryAdmission
    data class Rejected(val failure: ControlInventoryFailure, val limit: ControlLimitExceeded? = null) : ControlInventoryAdmission {
        fun report(boundary: ControlInventoryBoundary) {
            System.err.println(Json.encodeToString(ControlInventoryObservation.serializer(), ControlInventoryObservation(boundary, failure, limit)))
        }
    }
}

/** Traversal counts every path including the three payload roots, across all trees, before sorting or hashing. */
object ControlPayloadInventory {
    fun admit(root: Path): ControlInventoryAdmission {
        var entries = 0
        var bytes = 0L
        val files = mutableListOf<Path>()
        return try {
            for (name in listOf("bin", "lib", "share")) {
                val tree = root.resolve(name)
                if (!Files.isDirectory(tree, LinkOption.NOFOLLOW_LINKS)) return unsupported()
                val treeFiles = mutableListOf<Path>()
                Files.walk(tree).use { stream ->
                    val iterator = stream.iterator()
                    while (iterator.hasNext()) {
                        val path = iterator.next()
                        if (++entries > ControlDistributionLimits.maximumTraversedEntries) {
                            return exceeded(ControlInventoryResource.TRAVERSED_ENTRIES, ControlDistributionLimits.maximumTraversedEntries.toLong(), entries.toLong())
                        }
                        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        if (attributes.isDirectory) continue
                        if (!attributes.isRegularFile) return unsupported()
                        val count = files.size + treeFiles.size + 1
                        if (count > ControlDistributionLimits.maximumPayloadFiles) {
                            return exceeded(ControlInventoryResource.PAYLOAD_FILES, ControlDistributionLimits.maximumPayloadFiles.toLong(), count.toLong())
                        }
                        bytes += attributes.size()
                        if (bytes > ControlDistributionLimits.maximumPayloadBytes) {
                            return exceeded(ControlInventoryResource.PAYLOAD_BYTES, ControlDistributionLimits.maximumPayloadBytes, bytes)
                        }
                        treeFiles.add(path)
                    }
                }
                files.addAll(treeFiles.sorted())
            }
            ControlInventoryAdmission.Admitted(files.toList(), entries, bytes)
        } catch (_: IOException) {
            ControlInventoryAdmission.Rejected(ControlInventoryFailure.IO_REJECTED)
        } catch (_: java.io.UncheckedIOException) {
            ControlInventoryAdmission.Rejected(ControlInventoryFailure.IO_REJECTED)
        } catch (_: SecurityException) {
            ControlInventoryAdmission.Rejected(ControlInventoryFailure.IO_REJECTED)
        }
    }

    private fun unsupported() = ControlInventoryAdmission.Rejected(ControlInventoryFailure.UNSUPPORTED_ENTRY)

    fun exceeded(resource: ControlInventoryResource, maximum: Long, observedAtLeast: Long): ControlInventoryAdmission.Rejected =
        ControlInventoryAdmission.Rejected(ControlInventoryFailure.LIMIT_EXCEEDED, ControlLimitExceeded(resource, maximum, observedAtLeast))
}
