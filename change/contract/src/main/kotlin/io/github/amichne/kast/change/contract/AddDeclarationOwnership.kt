package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.Serializable

enum class AddDeclarationSourceOwnerFailure {
    SOURCE_ROOT_NOT_CANONICAL,
    BUILD_ROOT_NOT_CANONICAL,
    SOURCE_ROOT_OUTSIDE_BUILD,
    IDEA_MODULE_NAME_INVALID,
    GRADLE_PROJECT_PATH_INVALID,
    SOURCE_SET_NAME_INVALID,
}

@Serializable
@ConsistentCopyVisibility
data class AddDeclarationSourceOwner private constructor(
    val sourceRoot: String,
    val ideaModuleName: String,
    val gradleBuildRoot: String,
    val gradleProjectPath: String,
    val sourceSetName: String,
) {
    companion object {
        /**
         * Proof transition:
         * String owner fields to Refinement of AddDeclarationSourceOwner or
         * AddDeclarationSourceOwnerFailure.
         *
         * Establishes exact canonical source-root provenance for one imported Gradle source set.
         * AddDeclarationSourceOwnerFailure is the closed expected failure. Raw owner strings may
         * be extracted only by a physical project-model adapter.
         */
        fun admit(
            sourceRoot: String,
            ideaModuleName: String,
            gradleBuildRoot: String,
            gradleProjectPath: String,
            sourceSetName: String,
        ): Refinement<AddDeclarationSourceOwner, AddDeclarationSourceOwnerFailure> {
            val source = canonicalAbsolutePath(sourceRoot)
                         ?: return Refinement.Rejected(AddDeclarationSourceOwnerFailure.SOURCE_ROOT_NOT_CANONICAL)
            val build = canonicalAbsolutePath(gradleBuildRoot)
                        ?: return Refinement.Rejected(AddDeclarationSourceOwnerFailure.BUILD_ROOT_NOT_CANONICAL)
            if (source == build || !source.startsWith(build)) {
                return Refinement.Rejected(AddDeclarationSourceOwnerFailure.SOURCE_ROOT_OUTSIDE_BUILD)
            }
            if (!canonicalName(ideaModuleName)) {
                return Refinement.Rejected(AddDeclarationSourceOwnerFailure.IDEA_MODULE_NAME_INVALID)
            }
            if (!absoluteGradleProjectPath(gradleProjectPath)) {
                return Refinement.Rejected(AddDeclarationSourceOwnerFailure.GRADLE_PROJECT_PATH_INVALID)
            }
            if (!canonicalName(sourceSetName) || '/' in sourceSetName || '\\' in sourceSetName || ':' in sourceSetName) {
                return Refinement.Rejected(AddDeclarationSourceOwnerFailure.SOURCE_SET_NAME_INVALID)
            }
            return Refinement.Refined(
                AddDeclarationSourceOwner(
                    sourceRoot = sourceRoot,
                    ideaModuleName = ideaModuleName,
                    gradleBuildRoot = gradleBuildRoot,
                    gradleProjectPath = gradleProjectPath,
                    sourceSetName = sourceSetName,
                ),
            )
        }
    }
}

enum class AddDeclarationTargetCapabilityFailure {
    OWNER_OUTSIDE_WORKSPACE,
    TARGET_OUTSIDE_SOURCE_ROOT,
}

@Serializable
@ConsistentCopyVisibility
data class AddDeclarationTargetCapability private constructor(
    val workspaceRoot: AddDeclarationWorkspaceRoot,
    val targetPath: AddDeclarationTargetPath,
    val expectedCurrentSha256: AddDeclarationSha256,
    val owner: AddDeclarationSourceOwner,
) {
    companion object {
        /**
         * Proof transition:
         * AddDeclarationIntent and AddDeclarationSourceOwner to Refinement of
         * AddDeclarationTargetCapability or AddDeclarationTargetCapabilityFailure.
         *
         * Establishes that the exact target is owned by the proven authored source root inside the
         * admitted workspace and retains its expected preimage identity.
         * AddDeclarationTargetCapabilityFailure is the closed expected failure. Raw paths may be
         * extracted only by the physical planning adapter.
         */
        fun admit(
            intent: AddDeclarationIntent,
            owner: AddDeclarationSourceOwner,
        ): Refinement<AddDeclarationTargetCapability, AddDeclarationTargetCapabilityFailure> {
            val root = intent.workspaceRoot.toPath()
            val source = Path.of(owner.sourceRoot)
            val target = intent.targetPath.toPath()
            if (source == root || !source.startsWith(root)) {
                return Refinement.Rejected(AddDeclarationTargetCapabilityFailure.OWNER_OUTSIDE_WORKSPACE)
            }
            if (target == source || !target.startsWith(source)) {
                return Refinement.Rejected(AddDeclarationTargetCapabilityFailure.TARGET_OUTSIDE_SOURCE_ROOT)
            }
            return Refinement.Refined(
                AddDeclarationTargetCapability(
                    workspaceRoot = intent.workspaceRoot,
                    targetPath = intent.targetPath,
                    expectedCurrentSha256 = intent.expectedCurrentSha256,
                    owner = owner,
                ),
            )
        }
    }
}

private fun canonicalAbsolutePath(raw: String): Path? = runCatching {
    Path.of(raw).takeIf { path -> path.isAbsolute && path.normalize().toString() == raw }
}.getOrNull()

private fun canonicalName(raw: String): Boolean =
    raw.isNotBlank() && raw == raw.trim() && raw.none(Char::isISOControl)

private fun absoluteGradleProjectPath(raw: String): Boolean =
    raw.startsWith(':') && '/' !in raw && '\\' !in raw && raw.none(Char::isISOControl) &&
    (raw == ":" || (!raw.endsWith(':') && raw.drop(1).split(':').all(String::isNotBlank)))
