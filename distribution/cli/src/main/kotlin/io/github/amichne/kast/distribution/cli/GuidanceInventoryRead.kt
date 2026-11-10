package io.github.amichne.kast.distribution.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal sealed interface GuidanceInventoryRead {
    data class Read(val files: List<GuidanceFile>, val directories: Set<String>) : GuidanceInventoryRead

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
    return when (val read = readSkillDirectory(directory)) {
        SkillDirectoryRead.Rejected -> GuidanceInventoryRead.Rejected(ProvisionFailure.UNSUPPORTED_FILE_KIND)
        is SkillDirectoryRead.Read ->
            GuidanceInventoryRead.Read(
                read.files.map { GuidanceFile(directory.relativize(it).toString(), sha256(it)) }.sortedBy { it.path },
                read.directories,
            )
    }
}
