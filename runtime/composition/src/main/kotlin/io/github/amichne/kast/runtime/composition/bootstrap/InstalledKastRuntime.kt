package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.runtime.composition.platform.InstalledGradleModelFailure
import io.github.amichne.kast.runtime.composition.protocol.WorkspaceInspectHandlerConstructionFailure
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.intellij.InstalledIntellijWorkspaceFailure
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
    internal val canonicalRoot: CanonicalWorkspaceRoot,
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
            val canonical = when (val admitted = CanonicalWorkspaceRoot.fromCanonicalPath(physical)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    InstalledWorkspaceRootFailure.UNAVAILABLE,
                )
            }
            return Refinement.Refined(InstalledWorkspaceRoot(physical, canonical))
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
    internal val bootstrapObserver: InstalledRuntimeBootstrapObserver,
)

/** Ordered production runtime bootstrap boundaries visible to the installed process owner. */
enum class InstalledRuntimeBootstrapPhase {
    PROJECT_IMPORT,
    INDEXING,
    MODEL_CAPTURE,
    RUNTIME_ASSEMBLY,
}

/** Explicit effect boundary for observing production runtime bootstrap progress. */
fun interface InstalledRuntimeBootstrapObserver {
    fun observe(phase: InstalledRuntimeBootstrapPhase)

    /** Receives the detached exact-root semantic index authority after model capture. */
    fun observeIndexScope(scope: InstalledRuntimeIndexScope) = Unit
}

/** Detached proof of the exact workspace and Gradle source roots admitted for semantic reads. */
class InstalledRuntimeIndexScope internal constructor(
    internal val workspaceRoot: CanonicalWorkspaceRoot,
    sourceRoots: List<SourceRoot>,
) {
    internal val sourceRoots: List<SourceRoot> = sourceRoots.toList()

    /** Boundary extraction used only by the owning process diagnostic stream. */
    fun processDiagnostic(): String =
        "workspaceRoot=${workspaceRoot.value}, sourceRoots=$sourceRoots"
}

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
    data class Persistence(
        val failure: InstalledRuntimePersistenceFailure,
    ) : InstalledRuntimeAssemblyFailure

    data class WorkspacePublication(
        val failure: InstalledRuntimeWorkspaceFailure,
    ) : InstalledRuntimeAssemblyFailure

    data class WorkspaceHandler(
        val failure: WorkspaceInspectHandlerConstructionFailure,
    ) : InstalledRuntimeAssemblyFailure

    data class Composition(
        val failures: Set<KastRuntimeCompositionFailure>,
    ) : InstalledRuntimeAssemblyFailure
}

/** Finite composition-owned persistence bootstrap failures. */
enum class InstalledRuntimePersistenceFailure {
    WORKSPACE_PUBLICATION_UNAVAILABLE,
    MUTATION_RECOVERY_UNAVAILABLE,
    TOPOLOGY_SNAPSHOT_UNAVAILABLE,
}

/** Finite initial exact-root publication failures. */
sealed interface InstalledRuntimeWorkspaceFailure {
    data class IntellijBootstrap(
        val failure: InstalledIntellijWorkspaceFailure,
    ) : InstalledRuntimeWorkspaceFailure

    data class ModelRefinementUnavailable(
        val failure: InstalledGradleModelFailure,
    ) : InstalledRuntimeWorkspaceFailure

    data object NoPublication : InstalledRuntimeWorkspaceFailure

    data object Invalidated : InstalledRuntimeWorkspaceFailure

    data object Blocked : InstalledRuntimeWorkspaceFailure

    data object RootMismatch : InstalledRuntimeWorkspaceFailure
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
     * Proof transition: `(Path, Path) -> InstalledKastRuntimeConstruction`.
     *
     * Establishes exact physical workspace/state admission followed by the sole production
     * IntelliJ/K2 assembly. [InstalledKastRuntimeFailure] closes every admission, bootstrap,
     * persistence, publication, handler, and composition failure. Raw paths leave only for those
     * named outer boundaries; callers receive only dispatch authority.
     */
    fun create(
        workspaceRoot: Path,
        stateDirectory: Path,
    ): InstalledKastRuntimeConstruction = create(
        workspaceRoot,
        stateDirectory,
        InstalledRuntimeBootstrapObserver {},
    )

    /**
     * Proof transition: `(Path, Path, InstalledRuntimeBootstrapObserver) ->
     * InstalledKastRuntimeConstruction`.
     *
     * Preserves the same exact-root runtime proof while making every production bootstrap phase
     * an explicit effect owned by the caller.
     */
    fun create(
        workspaceRoot: Path,
        stateDirectory: Path,
        bootstrapObserver: InstalledRuntimeBootstrapObserver,
    ): InstalledKastRuntimeConstruction = create(
        workspaceRoot,
        stateDirectory,
        productionInstalledRuntimeAssembler(),
        bootstrapObserver,
    )

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
    ): InstalledKastRuntimeConstruction = create(
        workspaceRoot,
        stateDirectory,
        assembler,
        InstalledRuntimeBootstrapObserver {},
    )

    internal fun create(
        workspaceRoot: Path,
        stateDirectory: Path,
        assembler: InstalledRuntimeAssembler,
        bootstrapObserver: InstalledRuntimeBootstrapObserver,
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
        val request = InstalledKastRuntimeRequest(
            checkNotNull(root),
            checkNotNull(state),
            bootstrapObserver,
        )
        return when (val assembly = assembler.assemble(request)) {
            is InstalledRuntimeAssembly.Assembled ->
                InstalledKastRuntimeConstruction.Created(assembly.dispatch)
            is InstalledRuntimeAssembly.Rejected -> InstalledKastRuntimeConstruction.Rejected(
                setOf(InstalledKastRuntimeFailure.Assembly(assembly.failure)),
            )
        }
    }
}
