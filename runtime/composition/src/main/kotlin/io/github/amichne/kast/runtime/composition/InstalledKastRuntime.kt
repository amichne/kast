package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.runtime.composition.protocol.WorkspaceInspectHandlerConstructionFailure
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Finite physical admission failures for the installed exact workspace root. */
enum class InstalledWorkspaceRootFailure {
    NOT_ABSOLUTE,
    NOT_NORMALIZED,
    UNAVAILABLE,
    NOT_DIRECTORY,
    SETTINGS_MARKER_UNAVAILABLE,
}

/** Canonical settings-owned workspace root admitted for one installed runtime. */
class InstalledWorkspaceRoot private constructor(
    internal val path: Path,
) {
    companion object {
        /**
         * Proof transition: `Path -> Refinement<InstalledWorkspaceRoot,
         * InstalledWorkspaceRootFailure>`.
         *
         * Establishes an absolute, normalized, physically canonical, non-symlinked directory with
         * one regular `settings.gradle.kts` marker. [InstalledWorkspaceRootFailure] is the closed
         * expected failure. Raw path extraction is permitted only at installed IntelliJ project
         * bootstrap and physical workspace adapter boundaries.
         */
        fun admit(
            raw: Path,
        ): Refinement<InstalledWorkspaceRoot, InstalledWorkspaceRootFailure> {
            val normalized = when {
                !raw.isAbsolute -> return Refinement.Rejected(
                    InstalledWorkspaceRootFailure.NOT_ABSOLUTE,
                )
                raw.normalize() != raw -> return Refinement.Rejected(
                    InstalledWorkspaceRootFailure.NOT_NORMALIZED,
                )
                else -> raw
            }
            if (Files.isSymbolicLink(normalized)) {
                return Refinement.Rejected(InstalledWorkspaceRootFailure.UNAVAILABLE)
            }
            val physical = try {
                normalized.toRealPath()
            } catch (_: IOException) {
                return Refinement.Rejected(InstalledWorkspaceRootFailure.UNAVAILABLE)
            } catch (_: SecurityException) {
                return Refinement.Rejected(InstalledWorkspaceRootFailure.UNAVAILABLE)
            }
            if (!Files.isDirectory(physical, LinkOption.NOFOLLOW_LINKS)) {
                return Refinement.Rejected(InstalledWorkspaceRootFailure.NOT_DIRECTORY)
            }
            val settings = physical.resolve("settings.gradle.kts")
            if (
                Files.isSymbolicLink(settings) ||
                !Files.isRegularFile(settings, LinkOption.NOFOLLOW_LINKS)
            ) {
                return Refinement.Rejected(
                    InstalledWorkspaceRootFailure.SETTINGS_MARKER_UNAVAILABLE,
                )
            }
            return Refinement.Refined(InstalledWorkspaceRoot(physical))
        }
    }
}

/** Finite physical admission failures for installed runtime-owned state. */
enum class InstalledRuntimeStateDirectoryFailure {
    NOT_ABSOLUTE,
    NOT_NORMALIZED,
    UNAVAILABLE,
    NOT_DIRECTORY,
}

/** Canonical non-symlinked directory that may contain only this exact runtime's state. */
class InstalledRuntimeStateDirectory private constructor(
    internal val path: Path,
) {
    companion object {
        /**
         * Proof transition: `Path -> Refinement<InstalledRuntimeStateDirectory,
         * InstalledRuntimeStateDirectoryFailure>`.
         *
         * Establishes an absolute, normalized, physically canonical, non-symlinked directory.
         * [InstalledRuntimeStateDirectoryFailure] is the closed expected failure. Raw extraction is
         * permitted only while opening composition-owned persistence.
         */
        fun admit(
            raw: Path,
        ): Refinement<InstalledRuntimeStateDirectory, InstalledRuntimeStateDirectoryFailure> {
            val normalized = when {
                !raw.isAbsolute -> return Refinement.Rejected(
                    InstalledRuntimeStateDirectoryFailure.NOT_ABSOLUTE,
                )
                raw.normalize() != raw -> return Refinement.Rejected(
                    InstalledRuntimeStateDirectoryFailure.NOT_NORMALIZED,
                )
                else -> raw
            }
            if (Files.isSymbolicLink(normalized)) {
                return Refinement.Rejected(InstalledRuntimeStateDirectoryFailure.UNAVAILABLE)
            }
            val physical = try {
                normalized.toRealPath()
            } catch (_: IOException) {
                return Refinement.Rejected(InstalledRuntimeStateDirectoryFailure.UNAVAILABLE)
            } catch (_: SecurityException) {
                return Refinement.Rejected(InstalledRuntimeStateDirectoryFailure.UNAVAILABLE)
            }
            return if (Files.isDirectory(physical, LinkOption.NOFOLLOW_LINKS)) {
                Refinement.Refined(InstalledRuntimeStateDirectory(physical))
            } else {
                Refinement.Rejected(InstalledRuntimeStateDirectoryFailure.NOT_DIRECTORY)
            }
        }
    }
}

/** Strong installed request whose physical root and state ownership are already established. */
class InstalledKastRuntimeRequest internal constructor(
    internal val workspaceRoot: InstalledWorkspaceRoot,
    internal val stateDirectory: InstalledRuntimeStateDirectory,
)

/** Finite installed construction failures visible to the isolated host. */
sealed interface InstalledKastRuntimeFailure {
    data class WorkspaceRoot(
        val failure: InstalledWorkspaceRootFailure,
    ) : InstalledKastRuntimeFailure

    data class StateDirectory(
        val failure: InstalledRuntimeStateDirectoryFailure,
    ) : InstalledKastRuntimeFailure

    data class Assembly(
        val failure: InstalledRuntimeAssemblyFailure,
    ) : InstalledKastRuntimeFailure
}

/** Closed production-graph assembly failures. */
sealed interface InstalledRuntimeAssemblyFailure {
    data class WorkspaceHandler(
        val failure: WorkspaceInspectHandlerConstructionFailure,
    ) : InstalledRuntimeAssemblyFailure

    data class Composition(
        val failures: Set<KastRuntimeCompositionFailure>,
    ) : InstalledRuntimeAssemblyFailure
}

/** Installed construction exports only the composition-owned dispatch capability. */
sealed interface InstalledKastRuntimeConstruction {
    data class Created(
        val dispatch: KastRuntimeDispatchOperations,
    ) : InstalledKastRuntimeConstruction

    data class Rejected(
        val failures: Set<InstalledKastRuntimeFailure>,
    ) : InstalledKastRuntimeConstruction
}

internal sealed interface InstalledRuntimeAssembly {
    data class Assembled(
        val dispatch: KastRuntimeDispatchOperations,
    ) : InstalledRuntimeAssembly

    data class Rejected(
        val failure: InstalledRuntimeAssemblyFailure,
    ) : InstalledRuntimeAssembly
}

internal fun interface InstalledRuntimeAssembler {
    fun assemble(request: InstalledKastRuntimeRequest): InstalledRuntimeAssembly
}

/** Exact-root admission boundary used before composition-owned production assembly. */
object InstalledKastRuntime {
    /**
     * Proof transition: `(Path, Path, InstalledRuntimeAssembler) ->
     * InstalledKastRuntimeConstruction`.
     *
     * Establishes exact physical workspace and state ownership before granting either value to
     * the composition-owned assembler. [InstalledKastRuntimeFailure] is the closed expected
     * failure. Raw paths remain confined to this installed admission boundary.
     */
    internal fun create(
        workspaceRoot: Path,
        stateDirectory: Path,
        assembler: InstalledRuntimeAssembler,
    ): InstalledKastRuntimeConstruction {
        val failures = linkedSetOf<InstalledKastRuntimeFailure>()
        val root = when (val admitted = InstalledWorkspaceRoot.admit(workspaceRoot)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> {
                failures += InstalledKastRuntimeFailure.WorkspaceRoot(admitted.failure)
                null
            }
        }
        val state = when (val admitted = InstalledRuntimeStateDirectory.admit(stateDirectory)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> {
                failures += InstalledKastRuntimeFailure.StateDirectory(admitted.failure)
                null
            }
        }
        if (failures.isNotEmpty()) return InstalledKastRuntimeConstruction.Rejected(failures)
        val request = InstalledKastRuntimeRequest(checkNotNull(root), checkNotNull(state))
        return when (val assembly = assembler.assemble(request)) {
            is InstalledRuntimeAssembly.Assembled ->
                InstalledKastRuntimeConstruction.Created(assembly.dispatch)
            is InstalledRuntimeAssembly.Rejected -> InstalledKastRuntimeConstruction.Rejected(
                setOf(InstalledKastRuntimeFailure.Assembly(assembly.failure)),
            )
        }
    }
}
