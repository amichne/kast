package io.github.amichne.kast.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

private const val ROOT_MARKER = "settings.gradle.kts"

/** A canonical directory proven to own the repository's Gradle settings marker. */
class CanonicalRoot internal constructor(
    val path: Path,
) {
    override fun equals(other: Any?): Boolean = other is CanonicalRoot && path == other.path

    override fun hashCode(): Int = path.hashCode()

    override fun toString(): String = "CanonicalRoot(path=$path)"
}

sealed interface CanonicalRootDiscovery {
    data class Discovered(
        val root: CanonicalRoot,
    ) : CanonicalRootDiscovery

    data class Rejected(
        val failure: CanonicalRootFailure,
    ) : CanonicalRootDiscovery
}

enum class CanonicalRootFailure {
    START_UNAVAILABLE,
    START_NOT_DIRECTORY,
    ROOT_MARKER_NOT_FOUND,
    INVALID_ROOT_MARKER,
}

fun interface CanonicalRootDiscoverer {
    /**
     * Proof transition: `Path -> CanonicalRootDiscovery`.
     *
     * Establishes the nearest canonical, settings-owned repository directory.
     * [CanonicalRootFailure] is the closed expected failure. The canonical path may be extracted
     * only for exact-root process and socket admission.
     */
    fun discover(start: Path): CanonicalRootDiscovery
}

/** Filesystem-backed nearest-owner root discovery. */
object FilesystemCanonicalRootDiscovery : CanonicalRootDiscoverer {
    override fun discover(start: Path): CanonicalRootDiscovery {
        val canonicalStart = try {
            start.toRealPath()
        } catch (_: IOException) {
            return CanonicalRootDiscovery.Rejected(CanonicalRootFailure.START_UNAVAILABLE)
        } catch (_: SecurityException) {
            return CanonicalRootDiscovery.Rejected(CanonicalRootFailure.START_UNAVAILABLE)
        }
        if (!Files.isDirectory(canonicalStart, LinkOption.NOFOLLOW_LINKS)) {
            return CanonicalRootDiscovery.Rejected(CanonicalRootFailure.START_NOT_DIRECTORY)
        }

        var candidate = canonicalStart
        while (true) {
            val marker = candidate.resolve(ROOT_MARKER)
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                return if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                    CanonicalRootDiscovery.Discovered(CanonicalRoot(candidate))
                } else {
                    CanonicalRootDiscovery.Rejected(CanonicalRootFailure.INVALID_ROOT_MARKER)
                }
            }
            when (val parent = parentOf(candidate)) {
                is CanonicalParent.Parent -> candidate = parent.path
                CanonicalParent.FilesystemRoot -> return CanonicalRootDiscovery.Rejected(
                    CanonicalRootFailure.ROOT_MARKER_NOT_FOUND,
                )
            }
        }
    }
}

private sealed interface CanonicalParent {
    data class Parent(
        val path: Path,
    ) : CanonicalParent

    data object FilesystemRoot : CanonicalParent
}

private fun parentOf(path: Path): CanonicalParent = path.parent
                                                        ?.let(CanonicalParent::Parent)
                                                    ?: CanonicalParent.FilesystemRoot
