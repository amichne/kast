package io.github.amichne.kast.idea.backend.mutation

import io.github.amichne.kast.api.client.WorkspacePathPolicy
import io.github.amichne.kast.api.contract.result.AdditionSourceRoot
import io.github.amichne.kast.api.protocol.AdditionProofLimitation
import io.github.amichne.kast.idea.IdeaGradleProjectLoadBridge.GradleSourceRoot
import io.github.amichne.kast.idea.IdeaGradleProjectLoadBridge.GradleSourceRootProvenance
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.mutation.SecureSourceProofRead
import java.nio.file.Path

internal class EditableAdditionTarget private constructor(
    val targetPath: Path,
    val sourceRoot: GradleSourceRoot,
) {
    val sourceRootPath: Path = sourceRoot.path()
    val additionSourceRoot: AdditionSourceRoot = AdditionSourceRoot.parse(sourceRootPath.toString())

    fun asProofRoot(): AdditionProofRoot = AdditionProofRoot.from(sourceRoot)

    companion object {
        fun admit(
            backend: KastIndexerBackend,
            target: Path,
            exactSourceRoots: Collection<GradleSourceRoot>,
        ): EditableAdditionTarget {
            val roots = exactSourceRoots.distinctBy(GradleSourceRoot::stableIdentity)
            require(roots.isNotEmpty()) { "Editable addition admission requires an exact source root" }
            val rootPaths = roots.map(GradleSourceRoot::path).distinct()
            require(rootPaths.size == 1) { "Editable addition admission requires one source-root path" }

            val unknownReasons = roots.map(GradleSourceRoot::provenance)
                .filterIsInstance<GradleSourceRootProvenance.Unknown>()
                .map(GradleSourceRootProvenance.Unknown::reason)
                .distinct()
                .sorted()
            if (unknownReasons.isNotEmpty()) failAddition(
                AdditionProofLimitation.SOURCE_PROVENANCE_UNKNOWN,
                "The target source-root provenance is unknown: ${unknownReasons.joinToString("; ")}",
            )
            val authored = roots.all { it.provenance() is GradleSourceRootProvenance.Authored }
            val generated = roots.all { it.provenance() is GradleSourceRootProvenance.Generated }
            if (generated) failAddition(
                AdditionProofLimitation.GENERATED_SOURCE_READ_ONLY,
                "The target source root is generated and read-only",
            )
            if (!authored) failAddition(
                AdditionProofLimitation.SOURCE_PROVENANCE_UNKNOWN,
                "The target source root has conflicting Gradle provenance",
            )

            val normalizedTarget = target.toAbsolutePath().normalize()
            val sourceRoot = roots.sortedBy(GradleSourceRoot::stableIdentity).first()
            requireMutationAuthority(backend, normalizedTarget, "addition target")
            requireMutationAuthority(backend, sourceRoot.path(), "target source root")
            if (normalizedTarget == sourceRoot.path() || !normalizedTarget.startsWith(sourceRoot.path())) failAddition(
                AdditionProofLimitation.SOURCE_OWNER_UNPROVEN,
                "The target is not inside its exact Gradle source root",
            )
            return EditableAdditionTarget(normalizedTarget, sourceRoot)
        }

        private fun requireMutationAuthority(backend: KastIndexerBackend, path: Path, subject: String) {
            val relativePath = backend.sharedWorkspaceIdentity.relativizeIfContained(path) ?: failAddition(
                AdditionProofLimitation.OUTSIDE_WORKSPACE_AUTHORITY,
                "The $subject is outside the exact workspace authority",
            )
            if (WorkspacePathPolicy.isHardExcluded(relativePath)) failAddition(
                AdditionProofLimitation.HARD_EXCLUDED_MUTATION_TARGET,
                "The $subject is inside a permanently denied mutation location",
            )
        }
    }
}

@ConsistentCopyVisibility
internal data class AdditionProofRoot private constructor(val sourceRoot: GradleSourceRoot) {
    val path: Path = sourceRoot.path()

    fun file(path: Path): AdditionProofFile = AdditionProofFile.from(this, path)

    companion object {
        fun from(sourceRoot: GradleSourceRoot): AdditionProofRoot = AdditionProofRoot(sourceRoot)
    }
}

@ConsistentCopyVisibility
internal data class AdditionProofFile private constructor(
    val proofRoot: AdditionProofRoot,
    val path: Path,
) {
    fun readExactBytes(): ByteArray = try {
        SecureSourceProofRead.fileBytes(path)
    } catch (_: Exception) {
        failAddition(
            AdditionProofLimitation.SOURCE_CONTEXT_CHANGED,
            "An exact read-only source-context image could not be read without following symbolic links",
        )
    } catch (_: LinkageError) {
        failAddition(
            AdditionProofLimitation.SOURCE_CONTEXT_CHANGED,
            "Secure read-only source-context primitives are unavailable",
        )
    }

    companion object {
        fun from(proofRoot: AdditionProofRoot, path: Path): AdditionProofFile {
            val normalizedPath = path.toAbsolutePath().normalize()
            require(normalizedPath != proofRoot.path && normalizedPath.startsWith(proofRoot.path)) {
                "Addition proof file must be a strict descendant of its classified source root"
            }
            return AdditionProofFile(proofRoot, normalizedPath)
        }
    }
}
