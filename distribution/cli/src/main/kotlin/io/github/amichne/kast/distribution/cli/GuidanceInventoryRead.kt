package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.ControlDistributionLimits
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal sealed interface GuidanceInventoryRead {
    data class Read(val files: List<GuidanceFile>) : GuidanceInventoryRead

    data class Rejected(val reason: ProvisionFailure) : GuidanceInventoryRead
}

internal fun guidanceInventory(directory: Path): GuidanceInventoryRead =
    try {
        readGuidanceInventory(directory)
    } catch (_: IOException) {
        GuidanceInventoryRead.Rejected(ProvisionFailure.DESTINATION_UNAVAILABLE)
    } catch (_: SecurityException) {
        GuidanceInventoryRead.Rejected(ProvisionFailure.DESTINATION_UNAVAILABLE)
    }

private fun readGuidanceInventory(directory: Path): GuidanceInventoryRead {
    if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || directory.toRealPath() != directory)
        return GuidanceInventoryRead.Rejected(ProvisionFailure.UNSUPPORTED_FILE_KIND)
    val paths =
        Files.walk(directory).use { it.limit(ControlDistributionLimits.maximumEntryCount.toLong() + 1).toList() }
    if (!regularGuidancePaths(paths)) return GuidanceInventoryRead.Rejected(ProvisionFailure.UNSUPPORTED_FILE_KIND)
    val files =
        paths
            .filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
            .map { GuidanceFile(directory.relativize(it).toString(), sha256(it)) }
            .sortedBy { it.path }
    return if (completeGuidanceDirectories(directory, paths, files)) GuidanceInventoryRead.Read(files)
    else GuidanceInventoryRead.Rejected(ProvisionFailure.RESOURCE_MISMATCH)
}

private fun regularGuidancePaths(paths: List<Path>): Boolean =
    paths.size <= ControlDistributionLimits.maximumEntryCount &&
        paths.all { Files.isDirectory(it, NOFOLLOW_LINKS) || Files.isRegularFile(it, NOFOLLOW_LINKS) }

private fun completeGuidanceDirectories(directory: Path, paths: List<Path>, files: List<GuidanceFile>): Boolean {
    val expected =
        files
            .flatMap { file -> generateSequence(Path.of(file.path).parent) { it.parent }.map(Path::toString).toList() }
            .toSet() + ""
    return paths.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.map { directory.relativize(it).toString() }.toSet() ==
        expected
}

internal fun matchesGuidance(directory: Path, expected: List<GuidanceFile>): Boolean =
    when (val inventory = guidanceInventory(directory)) {
        is GuidanceInventoryRead.Read -> inventory.files == expected
        is GuidanceInventoryRead.Rejected -> false
    }
