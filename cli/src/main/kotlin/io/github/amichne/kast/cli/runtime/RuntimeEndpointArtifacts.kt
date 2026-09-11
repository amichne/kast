package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.managed.endpoint.InstalledEndpointAliases
import io.github.amichne.kast.kernel.Validation
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** One filesystem artifact derived from an exact runtime endpoint. */
sealed interface RuntimeEndpointArtifact

/** Ephemeral markers whose presence identifies a published runtime endpoint. */
enum class RuntimeEndpointMarker : RuntimeEndpointArtifact {
    SOCKET,
    DESCRIPTOR,
}

/** Persistent state owned by one exact endpoint and removed when that endpoint stops. */
data object RuntimePersistentState : RuntimeEndpointArtifact

internal sealed interface RuntimeEndpointMarkerObservation {
    data class Observed(val present: Set<RuntimeEndpointMarker>) : RuntimeEndpointMarkerObservation

    data object Rejected : RuntimeEndpointMarkerObservation
}

internal sealed interface RuntimeEndpointArtifactCleaning {
    data class Cleaned(val removed: Set<RuntimeEndpointArtifact>) : RuntimeEndpointArtifactCleaning

    data object Rejected : RuntimeEndpointArtifactCleaning

    data object Interrupted : RuntimeEndpointArtifactCleaning
}

internal interface RuntimeEndpointArtifacts {
    /**
     * Proof transition: `RuntimeEndpoint -> RuntimeEndpointMarkerObservation`.
     *
     * Establishes the exact socket and descriptor markers currently present for the admitted endpoint.
     * [RuntimeEndpointMarkerObservation.Rejected] closes inaccessible filesystem state. Raw paths remain inside the
     * lifecycle filesystem adapter.
     */
    fun observeMarkers(endpoint: RuntimeEndpoint): RuntimeEndpointMarkerObservation

    /**
     * Proof transition: `InactiveRuntimeEndpoint -> RuntimeEndpointArtifactCleaning`.
     *
     * Establishes that the exact socket, descriptor, and persistent state are all absent.
     * [RuntimeEndpointArtifactCleaning] closes removal rejection and interruption. Raw paths remain inside the
     * lifecycle filesystem adapter.
     */
    fun clean(endpoint: InactiveRuntimeEndpoint): RuntimeEndpointArtifactCleaning
}

internal object PosixRuntimeEndpointArtifacts : RuntimeEndpointArtifacts {
    internal fun prepareTransport(endpoint: RuntimeEndpoint): RuntimeEndpointResolution {
        if (endpoint.route !is RuntimeSocketRoute.Planned) return observeTransport(endpoint)
        return try {
            val parent = endpoint.physicalSocketPath.parent
            Files.createDirectories(parent)
            if (parent.toRealPath() != parent || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
                return rejectedTransport()
            Files.setPosixFilePermissions(parent, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            if (endpoint.socketPath == endpoint.physicalSocketPath) endpoint.canonicalTransport()
            else
                when (val admission = InstalledEndpointAliases.prepare(parent)) {
                    is Validation.Validated -> endpoint.admittedAlias(admission.value)
                    is Validation.Rejected -> rejectedTransport()
                }
        } catch (_: Exception) {
            rejectedTransport()
        }
    }

    internal fun observeTransport(endpoint: RuntimeEndpoint): RuntimeEndpointResolution =
        when (val proof = endpoint.route) {
            RuntimeSocketRoute.Canonical -> RuntimeEndpointResolution.Resolved(endpoint)
            RuntimeSocketRoute.Planned ->
                if (
                    endpoint.socketPath == endpoint.physicalSocketPath ||
                        (!Files.exists(endpoint.socketPath.parent, LinkOption.NOFOLLOW_LINKS) &&
                            !Files.exists(
                                endpoint.physicalSocketPath.parent.resolve("endpoint-alias.json"),
                                LinkOption.NOFOLLOW_LINKS,
                            ))
                )
                    RuntimeEndpointResolution.Resolved(endpoint)
                else
                    when (val admission = InstalledEndpointAliases.observe(endpoint.socketPath)) {
                        is Validation.Validated -> endpoint.admittedAlias(admission.value)
                        is Validation.Rejected -> rejectedTransport()
                    }
            is RuntimeSocketRoute.Aliased ->
                when (proof.receipt.validate()) {
                    is Validation.Validated -> RuntimeEndpointResolution.Resolved(endpoint)
                    is Validation.Rejected -> rejectedTransport()
                }
        }

    private fun rejectedTransport() = RuntimeEndpointResolution.Rejected(RuntimeEndpointFailure.INVALID_SOCKET_PATH)

    override fun observeMarkers(endpoint: RuntimeEndpoint): RuntimeEndpointMarkerObservation =
        if (endpoint.observeTransport() is RuntimeEndpointResolution.Rejected) RuntimeEndpointMarkerObservation.Rejected
        else observeMarkers(RuntimeEndpointArtifactPaths.from(endpoint))

    override fun clean(endpoint: InactiveRuntimeEndpoint): RuntimeEndpointArtifactCleaning {
        if (endpoint.endpoint.observeTransport() is RuntimeEndpointResolution.Rejected)
            return RuntimeEndpointArtifactCleaning.Rejected
        val paths = RuntimeEndpointArtifactPaths.from(endpoint.endpoint)
        val observed =
            when (val observation = observeAll(paths)) {
                RuntimeEndpointArtifactObservation.Rejected -> return RuntimeEndpointArtifactCleaning.Rejected
                is RuntimeEndpointArtifactObservation.Observed -> observation
            }
        if (Files.isSymbolicLink(paths.state)) return RuntimeEndpointArtifactCleaning.Rejected
        val targets = buildList {
            if (observed.persistentState == RuntimePersistentStatePresence.PRESENT) {
                add(RemovalTarget.Tree(paths.state))
            }
            if (RuntimeEndpointMarker.DESCRIPTOR in observed.markers) {
                add(RemovalTarget.Entry(paths.descriptor))
            }
            if (RuntimeEndpointMarker.SOCKET in observed.markers) {
                add(RemovalTarget.Entry(paths.socket))
            }
        }
        return when (remove(targets)) {
            RuntimeArtifactRemoval.REMOVED ->
                when (val remaining = observeAll(paths)) {
                    RuntimeEndpointArtifactObservation.Rejected -> RuntimeEndpointArtifactCleaning.Rejected
                    is RuntimeEndpointArtifactObservation.Observed ->
                        when (remaining.presence()) {
                            RuntimeEndpointArtifactPresence.ABSENT ->
                                RuntimeEndpointArtifactCleaning.Cleaned(observed.artifacts())
                            RuntimeEndpointArtifactPresence.PRESENT -> RuntimeEndpointArtifactCleaning.Rejected
                        }
                }
            RuntimeArtifactRemoval.REJECTED -> RuntimeEndpointArtifactCleaning.Rejected
            RuntimeArtifactRemoval.INTERRUPTED -> RuntimeEndpointArtifactCleaning.Interrupted
        }
    }

    /**
     * Proof transition: `RuntimeEndpointArtifactPaths -> RuntimeEndpointMarkerObservation`.
     *
     * Establishes the exact marker set or closes inaccessible filesystem state as
     * [RuntimeEndpointMarkerObservation.Rejected]. Raw paths leave only at JDK filesystem reads.
     */
    private fun observeMarkers(paths: RuntimeEndpointArtifactPaths): RuntimeEndpointMarkerObservation {
        val present = linkedSetOf<RuntimeEndpointMarker>()
        when (observePath(paths.socket)) {
            PathObservation.PRESENT -> present += RuntimeEndpointMarker.SOCKET
            PathObservation.ABSENT -> Unit
            PathObservation.REJECTED -> return RuntimeEndpointMarkerObservation.Rejected
        }
        when (observePath(paths.descriptor)) {
            PathObservation.PRESENT -> present += RuntimeEndpointMarker.DESCRIPTOR
            PathObservation.ABSENT -> Unit
            PathObservation.REJECTED -> return RuntimeEndpointMarkerObservation.Rejected
        }
        return RuntimeEndpointMarkerObservation.Observed(present)
    }

    /**
     * Proof transition: `RuntimeEndpointArtifactPaths -> RuntimeEndpointArtifactObservation`.
     *
     * Establishes the exact marker set and persistent-state presence or closes inaccessible filesystem state as
     * [RuntimeEndpointArtifactObservation.Rejected]. Raw paths leave only at JDK filesystem reads.
     */
    private fun observeAll(paths: RuntimeEndpointArtifactPaths): RuntimeEndpointArtifactObservation {
        val markers =
            when (val observation = observeMarkers(paths)) {
                RuntimeEndpointMarkerObservation.Rejected -> return RuntimeEndpointArtifactObservation.Rejected
                is RuntimeEndpointMarkerObservation.Observed -> observation.present
            }
        val persistentState =
            when (observePath(paths.state)) {
                PathObservation.PRESENT -> RuntimePersistentStatePresence.PRESENT
                PathObservation.ABSENT -> RuntimePersistentStatePresence.ABSENT
                PathObservation.REJECTED -> return RuntimeEndpointArtifactObservation.Rejected
            }
        return RuntimeEndpointArtifactObservation.Observed(markers, persistentState)
    }

    /**
     * Proof transition: `List<RemovalTarget> -> RuntimeArtifactRemoval`.
     *
     * Establishes that every exact admitted target was accepted by one macOS POSIX removal process per target.
     * [RuntimeArtifactRemoval] closes process rejection and interruption. Raw paths leave only as distinct process
     * arguments at the CLI's process-control edge.
     */
    private fun remove(targets: List<RemovalTarget>): RuntimeArtifactRemoval {
        targets.forEach { target ->
            val arguments =
                when (target) {
                    is RemovalTarget.Entry -> listOf(RM_EXECUTABLE, "-f", "--", target.path.toString())
                    is RemovalTarget.Tree -> listOf(RM_EXECUTABLE, "-rf", "--", target.path.toString())
                }
            val exitCode =
                try {
                    ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.INHERIT).start().waitFor()
                } catch (_: IOException) {
                    return RuntimeArtifactRemoval.REJECTED
                } catch (_: SecurityException) {
                    return RuntimeArtifactRemoval.REJECTED
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return RuntimeArtifactRemoval.INTERRUPTED
                }
            if (exitCode != 0) return RuntimeArtifactRemoval.REJECTED
        }
        return RuntimeArtifactRemoval.REMOVED
    }

    /**
     * Proof transition: `Path -> PathObservation`.
     *
     * Establishes whether the exact non-followed filesystem entry exists or closes inaccessible state as
     * [PathObservation.REJECTED]. Raw paths leave only at the JDK filesystem boundary.
     */
    private fun observePath(path: Path): PathObservation =
        try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            PathObservation.PRESENT
        } catch (_: NoSuchFileException) {
            PathObservation.ABSENT
        } catch (_: IOException) {
            PathObservation.REJECTED
        } catch (_: SecurityException) {
            PathObservation.REJECTED
        }
}

private sealed interface RuntimeEndpointArtifactObservation {
    data class Observed(
        val markers: Set<RuntimeEndpointMarker>,
        val persistentState: RuntimePersistentStatePresence,
    ) : RuntimeEndpointArtifactObservation {
        fun presence(): RuntimeEndpointArtifactPresence =
            if (markers.isEmpty() && persistentState == RuntimePersistentStatePresence.ABSENT) {
                RuntimeEndpointArtifactPresence.ABSENT
            } else {
                RuntimeEndpointArtifactPresence.PRESENT
            }

        fun artifacts(): Set<RuntimeEndpointArtifact> = buildSet {
            addAll(markers)
            if (persistentState == RuntimePersistentStatePresence.PRESENT) {
                add(RuntimePersistentState)
            }
        }
    }

    data object Rejected : RuntimeEndpointArtifactObservation
}

private enum class RuntimeEndpointArtifactPresence {
    ABSENT,
    PRESENT,
}

private enum class RuntimePersistentStatePresence {
    PRESENT,
    ABSENT,
}

private enum class PathObservation {
    PRESENT,
    ABSENT,
    REJECTED,
}

private sealed interface RemovalTarget {
    val path: Path

    data class Entry(override val path: Path) : RemovalTarget

    data class Tree(override val path: Path) : RemovalTarget
}

private enum class RuntimeArtifactRemoval {
    REMOVED,
    REJECTED,
    INTERRUPTED,
}

private data class RuntimeEndpointArtifactPaths(
    val socket: Path,
    val descriptor: Path,
    val state: Path,
) {
    companion object {
        /**
         * Proof transition: `RuntimeEndpoint -> RuntimeEndpointArtifactPaths`.
         *
         * Establishes the sole descriptor and canonical-parent persistent-state paths derived from the exact socket.
         * Raw paths remain inside the lifecycle filesystem adapter.
         */
        fun from(endpoint: RuntimeEndpoint): RuntimeEndpointArtifactPaths {
            val socket = endpoint.physicalSocketPath
            val stateParent = socket.parent
            return RuntimeEndpointArtifactPaths(
                socket,
                socket.resolveSibling("${socket.fileName}.endpoint.json"),
                stateParent.resolve("${socket.fileName}.state"),
            )
        }
    }
}

private const val RM_EXECUTABLE = "/bin/rm"
