package io.github.amichne.kast.workspace.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalSemanticProjectRoot
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val SEMANTIC_PROJECT_STORE_PREFIX = "intellij-project-"

/** Finite failures while creating one fresh runtime-owned IntelliJ project store. */
internal enum class InstalledSemanticProjectStoreFailure {
    OVERLAPS_WORKSPACE,
    CREATION_FAILED,
    IDENTITY_REJECTED,
}

/** One empty, fresh, runtime-owned location permitted to contain generated IntelliJ state. */
internal class InstalledSemanticProjectStore private constructor(
    val path: Path,
    val root: CanonicalSemanticProjectRoot,
) {
    companion object {
        /**
         * Proof transition:
         * `(CanonicalWorkspaceRoot, Path) -> InstalledSemanticProjectStorePreparation`.
         *
         * Prepared proves a newly created physical directory under runtime state that is disjoint
         * from the Gradle workspace. Rejected closes overlap, filesystem, and identity failures.
         * No existing project configuration is admitted.
         */
        fun prepare(
            workspaceRoot: CanonicalWorkspaceRoot,
            runtimeStateDirectory: Path,
        ): InstalledSemanticProjectStorePreparation {
            val workspacePath = Path.of(workspaceRoot.value)
            if (runtimeStateDirectory.startsWith(workspacePath)) {
                return rejected(InstalledSemanticProjectStoreFailure.OVERLAPS_WORKSPACE)
            }
            val created = try {
                Files.createTempDirectory(runtimeStateDirectory, SEMANTIC_PROJECT_STORE_PREFIX)
                    .toRealPath()
            } catch (_: IOException) {
                return rejected(InstalledSemanticProjectStoreFailure.CREATION_FAILED)
            } catch (_: SecurityException) {
                return rejected(InstalledSemanticProjectStoreFailure.CREATION_FAILED)
            }
            if (created.startsWith(workspacePath) || workspacePath.startsWith(created)) {
                return rejected(InstalledSemanticProjectStoreFailure.OVERLAPS_WORKSPACE)
            }
            val root = when (
                val admitted = CanonicalSemanticProjectRoot.fromCanonicalPath(
                    workspaceRoot,
                    created,
                )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return rejected(
                    InstalledSemanticProjectStoreFailure.IDENTITY_REJECTED,
                )
            }
            return InstalledSemanticProjectStorePreparation.Prepared(
                InstalledSemanticProjectStore(created, root),
            )
        }
    }
}

/** Closed result of allocating the installed semantic project's private configuration store. */
internal sealed interface InstalledSemanticProjectStorePreparation {
    data class Prepared(
        val store: InstalledSemanticProjectStore,
    ) : InstalledSemanticProjectStorePreparation

    data class Rejected(
        val failure: InstalledSemanticProjectStoreFailure,
    ) : InstalledSemanticProjectStorePreparation
}

private fun rejected(
    failure: InstalledSemanticProjectStoreFailure,
): InstalledSemanticProjectStorePreparation.Rejected =
    InstalledSemanticProjectStorePreparation.Rejected(failure)
