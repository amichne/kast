package io.github.amichne.kast.idea.backend.mutation

import io.github.amichne.kast.api.client.WorkspacePathPolicy
import io.github.amichne.kast.api.contract.result.AdditionSourceRoot
import io.github.amichne.kast.api.contract.result.ExactAdditionProofContext
import io.github.amichne.kast.api.protocol.AdditionProofLimitation
import io.github.amichne.kast.idea.IdeaGradleProjectLoadBridge.GradleSourceRoot
import io.github.amichne.kast.idea.IdeaGradleProjectLoadBridge.GradleSourceRootProvenance
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.mutation.SecureSourceProofRead
import io.github.amichne.kast.idea.mutation.SecureSourceProofReadOutcome
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

private enum class MutationAuthoritySubject(val description: String) {
    ADDITION_TARGET("addition target"),
    TARGET_SOURCE_ROOT("target source root"),
}

private class MutationAuthorizedPath private constructor(val path: Path) {
    companion object {
        /**
         * Proof transition: `Path -> MutationAuthorizedPath`.
         *
         * Establishes exact workspace containment and exclusion from every permanently denied
         * mutation location. Expected failures are closed by `AdditionProofIncompleteException`
         * and `AdditionProofLimitation`.
         */
        fun admit(
            backend: KastIndexerBackend,
            path: Path,
            subject: MutationAuthoritySubject,
        ): MutationAuthorizedPath {
            val relativePath = backend.sharedWorkspaceIdentity.relativizeIfContained(path) ?: failAddition(
                AdditionProofLimitation.OUTSIDE_WORKSPACE_AUTHORITY,
                "The ${subject.description} is outside the exact workspace authority",
            )
            if (WorkspacePathPolicy.isHardExcluded(relativePath)) failAddition(
                AdditionProofLimitation.HARD_EXCLUDED_MUTATION_TARGET,
                "The ${subject.description} is inside a permanently denied mutation location",
            )
            return MutationAuthorizedPath(path)
        }
    }
}

internal class EditableAdditionTarget private constructor(
    authorizedTarget: MutationAuthorizedPath,
    authorizedSourceRoot: MutationAuthorizedPath,
    val sourceRoot: GradleSourceRoot,
) {
    /** Boundary extraction for exact filesystem and PSI adapters only. */
    val targetPath: Path = authorizedTarget.path

    /** Boundary extraction for exact Gradle and filesystem adapters only. */
    val sourceRootPath: Path = authorizedSourceRoot.path
    val additionSourceRoot: AdditionSourceRoot = AdditionSourceRoot.parse(sourceRootPath.toString())

    /**
     * Proof transition: `EditableAdditionTarget -> AdditionProofRoot`.
     *
     * Preserves the classified target root while intentionally dropping write authority at the
     * proof-context boundary.
     */
    fun asProofRoot(): AdditionProofRoot = AdditionProofRoot.from(sourceRoot)

    companion object {
        /**
         * Proof transition: `(Path, Collection<GradleSourceRoot>) -> EditableAdditionTarget`.
         *
         * Establishes one exact authored Gradle source root, exact workspace containment, and
         * permanent mutation-location admission for the target. Expected failures are closed by
         * `AdditionProofIncompleteException` and `AdditionProofLimitation`. Raw paths are permitted
         * only at this Gradle/workspace admission boundary.
         */
        fun admit(
            backend: KastIndexerBackend,
            target: Path,
            exactSourceRoots: Collection<GradleSourceRoot>,
        ): EditableAdditionTarget {
            val roots = exactSourceRoots.distinctBy(GradleSourceRoot::stableIdentity)
            if (roots.isEmpty()) failAddition(
                AdditionProofLimitation.SOURCE_OWNER_UNPROVEN,
                "Editable addition admission requires an exact source root",
            )
            val rootPaths = roots.map(GradleSourceRoot::path).distinct()
            if (rootPaths.size != 1) failAddition(
                AdditionProofLimitation.SOURCE_OWNER_AMBIGUOUS,
                "Editable addition admission requires one source-root path",
            )

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
            val authorizedTarget = MutationAuthorizedPath.admit(
                backend,
                normalizedTarget,
                MutationAuthoritySubject.ADDITION_TARGET,
            )
            val authorizedSourceRoot = MutationAuthorizedPath.admit(
                backend,
                sourceRoot.path(),
                MutationAuthoritySubject.TARGET_SOURCE_ROOT,
            )
            if (normalizedTarget == sourceRoot.path() || !normalizedTarget.startsWith(sourceRoot.path())) failAddition(
                AdditionProofLimitation.SOURCE_OWNER_UNPROVEN,
                "The target is not inside its exact Gradle source root",
            )
            return EditableAdditionTarget(authorizedTarget, authorizedSourceRoot, sourceRoot)
        }
    }
}

internal class RevalidatedAdditionContext private constructor(
    /** Extraction is permitted only at the exact-proof result construction boundary. */
    val context: ExactAdditionProofContext,
) {
    companion object {
        /**
         * Proof transition: `(AdditionOwnerSnapshot, ExactAdditionProofContext, AdditionTargetState)
         * -> RevalidatedAdditionContext`.
         *
         * Establishes that semantic generation, Gradle model, classpath, classified source-file set,
         * target existence state, and every exact proof hash still match the planning read epoch.
         * Expected failures are closed by `AdditionProofIncompleteException` and
         * `AdditionProofLimitation`.
         */
        fun admit(
            backend: KastIndexerBackend,
            owner: AdditionOwnerSnapshot,
            generation: Long,
            context: ExactAdditionProofContext,
            target: AdditionTargetState,
        ): RevalidatedAdditionContext {
            if (backend.psiGeneration() != generation) failAddition(
                AdditionProofLimitation.GENERATION_CHANGED,
                "The semantic generation changed during addition planning",
            )
            val currentOwner = backend.exactAdditionOwner(target.targetPath)
            if (currentOwner.modelFingerprint != owner.modelFingerprint) failAddition(
                AdditionProofLimitation.PROJECT_MODEL_CHANGED,
                "The Gradle project model changed during addition planning",
            )
            if (currentOwner.classpathFingerprint != owner.classpathFingerprint) failAddition(
                AdditionProofLimitation.CLASSPATH_CHANGED,
                "The compiler classpath changed during addition planning",
            )
            if (currentOwner.sourceFiles != owner.sourceFiles) failAddition(
                AdditionProofLimitation.SOURCE_CONTEXT_CHANGED,
                "The model-owned Kotlin and Java source-file set changed during addition planning",
            )
            when (target) {
                is CreatableAdditionTarget -> if (Files.exists(target.targetPath, NOFOLLOW_LINKS)) failAddition(
                    AdditionProofLimitation.TARGET_ALREADY_EXISTS,
                    "The addition target state changed during planning",
                )
                is ExistingAdditionTarget -> if (!Files.exists(target.targetPath, NOFOLLOW_LINKS)) failAddition(
                    AdditionProofLimitation.TARGET_FILE_MISSING,
                    "The addition target state changed during planning",
                )
            }
            val currentSourceFiles = currentOwner.sourceFiles.associateBy(AdditionProofFile::path)
            context.contextFileHashes.forEach { expected ->
                val path = Path.of(expected.filePath)
                val sourceFile = currentSourceFiles[path]
                if (sourceFile == null || !Files.isRegularFile(path, NOFOLLOW_LINKS) ||
                    sourceFile.readExactHash().sha256 != expected.sha256
                ) failAddition(
                    AdditionProofLimitation.SOURCE_CONTEXT_CHANGED,
                    "A compiler source-context file changed during addition planning",
                )
            }
            if (backend.psiGeneration() != generation) failAddition(
                AdditionProofLimitation.GENERATION_CHANGED,
                "The semantic generation changed during addition proof finalization",
            )
            return RevalidatedAdditionContext(context)
        }
    }
}

@ConsistentCopyVisibility
internal data class AdditionProofRoot private constructor(val sourceRoot: GradleSourceRoot) {
    /** Boundary extraction for no-follow filesystem discovery only. */
    val path: Path = sourceRoot.path()

    /**
     * Proof transition: `(AdditionProofRoot, Path) -> AdditionProofFile`.
     *
     * Establishes that the normalized file path is a strict descendant of this classified root.
     */
    fun file(path: Path): AdditionProofFile = AdditionProofFile.from(this, path)

    companion object {
        /**
         * Proof transition: `GradleSourceRoot -> AdditionProofRoot`.
         *
         * Retains classified Gradle provenance while granting read-only proof-context authority.
         */
        fun from(sourceRoot: GradleSourceRoot): AdditionProofRoot = AdditionProofRoot(sourceRoot)
    }
}

internal class ExactAdditionProofHash private constructor(
    val file: AdditionProofFile,
    val sha256: String,
) {
    companion object {
        /**
         * Proof transition: `(AdditionProofFile, SecureSourceProofReadOutcome.Read)
         * -> ExactAdditionProofHash`.
         *
         * Retains both the classified proof file and the hash proven by its no-follow descriptor
         * read. The hash may be extracted only when constructing or comparing exact proof context.
         */
        fun from(file: AdditionProofFile, read: SecureSourceProofReadOutcome.Read): ExactAdditionProofHash =
            ExactAdditionProofHash(file, read.sha256)
    }
}

@ConsistentCopyVisibility
internal data class AdditionProofFile private constructor(
    val proofRoot: AdditionProofRoot,
    /** Boundary extraction for no-follow filesystem and PSI lookup adapters only. */
    val path: Path,
) {
    /**
     * Proof transition: `AdditionProofFile -> ExactAdditionProofHash`.
     *
     * Establishes a no-follow regular-file read and retains its exact content hash. Expected read
     * failures become `AdditionProofIncompleteException(SOURCE_CONTEXT_CHANGED)`; raw bytes remain
     * confined to the filesystem adapter.
     */
    fun readExactHash(): ExactAdditionProofHash = when (val outcome = SecureSourceProofRead.sha256(path)) {
        is SecureSourceProofReadOutcome.Read -> ExactAdditionProofHash.from(this, outcome)
        is SecureSourceProofReadOutcome.Unavailable -> failAddition(
            AdditionProofLimitation.SOURCE_CONTEXT_CHANGED,
            when (outcome) {
                SecureSourceProofReadOutcome.Unavailable.UNSUPPORTED_PLATFORM ->
                    "Secure read-only source-context reads require a supported POSIX runtime"
                SecureSourceProofReadOutcome.Unavailable.NATIVE_PRIMITIVES_UNAVAILABLE ->
                    "Secure read-only source-context primitives are unavailable"
                SecureSourceProofReadOutcome.Unavailable.UNSAFE_OR_UNREADABLE_PATH ->
                    "An exact read-only source-context image could not be read without following symbolic links"
            },
        )
    }

    companion object {
        /**
         * Proof transition: `(AdditionProofRoot, Path) -> AdditionProofFile`.
         *
         * Establishes strict normalized descendant containment. An invalid input is an internal
         * invariant breach because filesystem discovery is the only production caller.
         */
        fun from(proofRoot: AdditionProofRoot, path: Path): AdditionProofFile {
            val normalizedPath = path.toAbsolutePath().normalize()
            require(normalizedPath != proofRoot.path && normalizedPath.startsWith(proofRoot.path)) {
                "Addition proof file must be a strict descendant of its classified source root"
            }
            return AdditionProofFile(proofRoot, normalizedPath)
        }
    }
}
