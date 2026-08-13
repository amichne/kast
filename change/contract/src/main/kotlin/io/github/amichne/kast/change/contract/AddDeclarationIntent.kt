package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import kotlinx.serialization.Serializable

private val SHA256_FORMAT = Regex("[0-9a-f]{64}")

enum class AddDeclarationIntentFailure {
    WORKSPACE_ROOT_NOT_CANONICAL,
    TARGET_PATH_NOT_CANONICAL_KOTLIN,
    TARGET_OUTSIDE_WORKSPACE,
    PREIMAGE_SHA256_INVALID,
    DECLARATION_BLANK,
    DECLARATION_NOT_NORMALIZED,
}

@Serializable
@JvmInline
value class AddDeclarationWorkspaceRoot private constructor(val value: String) {
    internal fun toPath(): Path = Path.of(value)

    companion object {
        internal fun fromProvenRaw(value: String): AddDeclarationWorkspaceRoot =
            AddDeclarationWorkspaceRoot(value)
    }
}

@Serializable
@JvmInline
value class AddDeclarationTargetPath private constructor(val value: String) :
    Comparable<AddDeclarationTargetPath> {
    internal fun toPath(): Path = Path.of(value)

    override fun compareTo(other: AddDeclarationTargetPath): Int = value.compareTo(other.value)

    companion object {
        internal fun fromProvenRaw(value: String): AddDeclarationTargetPath =
            AddDeclarationTargetPath(value)
    }
}

@Serializable
@JvmInline
value class AddDeclarationSha256 private constructor(val value: String) {
    companion object {
        internal fun fromProvenRaw(value: String): AddDeclarationSha256 = AddDeclarationSha256(value)
    }
}

@Serializable
@JvmInline
value class AddDeclarationText private constructor(val value: String) {
    companion object {
        internal fun fromProvenRaw(value: String): AddDeclarationText = AddDeclarationText(value)
    }
}

@Serializable
@ConsistentCopyVisibility
data class AddDeclarationIntent private constructor(
    val workspaceRoot: AddDeclarationWorkspaceRoot,
    val targetPath: AddDeclarationTargetPath,
    val expectedCurrentSha256: AddDeclarationSha256,
    val proposedDeclaration: AddDeclarationText,
) {
    companion object {
        internal fun fromProvenRaw(
            workspaceRoot: String,
            targetPath: String,
            expectedCurrentSha256: String,
            proposedDeclaration: String,
        ): AddDeclarationIntent = AddDeclarationIntent(
            workspaceRoot = AddDeclarationWorkspaceRoot.fromProvenRaw(workspaceRoot),
            targetPath = AddDeclarationTargetPath.fromProvenRaw(targetPath),
            expectedCurrentSha256 = AddDeclarationSha256.fromProvenRaw(expectedCurrentSha256),
            proposedDeclaration = AddDeclarationText.fromProvenRaw(proposedDeclaration),
        )
    }
}

data class RawAddDeclarationPlanRequest(
    val workspaceRoot: String,
    val targetPath: String,
    val expectedCurrentSha256: String,
    val proposedDeclaration: String,
) {
    /**
     * Proof transition:
     * RawAddDeclarationPlanRequest to Refinement of AddDeclarationIntent or AddDeclarationIntentFailure.
     *
     * Establishes a canonical absolute workspace root, one canonical workspace-contained Kotlin
     * target, an exact lowercase SHA-256 preimage identity, and non-blank normalized-LF declaration
     * text without a terminal line break. AddDeclarationIntentFailure is the closed expected
     * failure. Raw strings may be extracted only by the legacy transport compatibility boundary.
     */
    fun refine(): Refinement<AddDeclarationIntent, AddDeclarationIntentFailure> {
        val root = canonicalAbsolutePath(workspaceRoot)
                   ?: return Refinement.Rejected(AddDeclarationIntentFailure.WORKSPACE_ROOT_NOT_CANONICAL)
        val target = canonicalAbsolutePath(targetPath)
                     ?: return Refinement.Rejected(AddDeclarationIntentFailure.TARGET_PATH_NOT_CANONICAL_KOTLIN)
        if (!targetPath.endsWith(".kt") || targetPath.endsWith(".kts")) {
            return Refinement.Rejected(AddDeclarationIntentFailure.TARGET_PATH_NOT_CANONICAL_KOTLIN)
        }
        if (target == root || !target.startsWith(root)) {
            return Refinement.Rejected(AddDeclarationIntentFailure.TARGET_OUTSIDE_WORKSPACE)
        }
        if (!SHA256_FORMAT.matches(expectedCurrentSha256)) {
            return Refinement.Rejected(AddDeclarationIntentFailure.PREIMAGE_SHA256_INVALID)
        }
        if (proposedDeclaration.isBlank()) {
            return Refinement.Rejected(AddDeclarationIntentFailure.DECLARATION_BLANK)
        }
        if (
            '\r' in proposedDeclaration ||
            proposedDeclaration != proposedDeclaration.trim() ||
            proposedDeclaration.endsWith('\n')
        ) {
            return Refinement.Rejected(AddDeclarationIntentFailure.DECLARATION_NOT_NORMALIZED)
        }
        return Refinement.Refined(
            AddDeclarationIntent.fromProvenRaw(
                workspaceRoot = workspaceRoot,
                targetPath = targetPath,
                expectedCurrentSha256 = expectedCurrentSha256,
                proposedDeclaration = proposedDeclaration,
            ),
        )
    }
}

internal fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun canonicalAbsolutePath(raw: String): Path? = runCatching {
    Path.of(raw).takeIf { path ->
        path.isAbsolute && path.normalize().toString() == raw
    }
}.getOrNull()
