package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.ControlDistributionLimits
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal sealed interface SkillDirectoryRead {
    data class Read(val files: List<Path>, val directories: Set<String>) : SkillDirectoryRead

    data object Rejected : SkillDirectoryRead
}

internal fun readSkillDirectory(directory: Path): SkillDirectoryRead {
    if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return SkillDirectoryRead.Rejected
    val paths =
        Files.walk(directory).use { it.limit(ControlDistributionLimits.maximumEntryCount.toLong() + 1).toList() }
    if (!regularSkillPaths(paths)) return SkillDirectoryRead.Rejected
    return SkillDirectoryRead.Read(
        paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) },
        paths.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.map { directory.relativize(it).toString() }.toSet(),
    )
}

private fun regularSkillPaths(paths: List<Path>): Boolean =
    paths.size <= ControlDistributionLimits.maximumEntryCount &&
        paths.all { Files.isDirectory(it, NOFOLLOW_LINKS) || Files.isRegularFile(it, NOFOLLOW_LINKS) }

internal fun expectedSkillDirectories(files: List<String>): Set<String> =
    files
        .flatMap { name -> generateSequence(Path.of(name).parent) { it.parent }.map(Path::toString).toList() }
        .toSet() + ""
