@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Closed public mutation intent; no generic edit variant exists. */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface ChangeIntentDocument {
    @Serializable
    @SerialName("add-file")
    data class AddFile(
        val relativePath: ProtocolText,
        val content: ProtocolText,
    ) : ChangeIntentDocument

    @Serializable
    @SerialName("add-declaration")
    data class AddDeclaration(
        val exactTarget: ProtocolText,
        val declaration: ProtocolText,
    ) : ChangeIntentDocument

    @Serializable
    @SerialName("replace-declaration")
    data class ReplaceDeclaration(
        val exactTarget: ProtocolText,
        val replacement: ProtocolText,
    ) : ChangeIntentDocument

    @Serializable
    @SerialName("rename-symbol")
    data class RenameSymbol(
        val exactTarget: ProtocolText,
        val newName: ProtocolText,
    ) : ChangeIntentDocument
}

@Serializable
data class ChangePlanRequest(
    val intent: ChangeIntentDocument,
) : OperationRequest

enum class ChangeFilePreviewKind {
    ADD,
    DELETE,
    UPDATE,
}

enum class ChangePreviewPathFailure {
    BLANK,
    INVALID,
    ABSOLUTE,
    NOT_NORMALIZED,
    ESCAPES_WORKSPACE,
    CONTROL_CHARACTER,
}

@JvmInline
value class ChangePreviewPath private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<ChangePreviewPath, ChangePreviewPathFailure> {
            if (raw.isBlank()) return Refinement.Rejected(ChangePreviewPathFailure.BLANK)
            if (raw.any(Char::isISOControl)) {
                return Refinement.Rejected(ChangePreviewPathFailure.CONTROL_CHARACTER)
            }
            val path = try {
                Path.of(raw)
            } catch (_: InvalidPathException) {
                return Refinement.Rejected(ChangePreviewPathFailure.INVALID)
            }
            if (path.isAbsolute) return Refinement.Rejected(ChangePreviewPathFailure.ABSOLUTE)
            if (path.any { segment -> segment.toString() == ".." }) {
                return Refinement.Rejected(ChangePreviewPathFailure.ESCAPES_WORKSPACE)
            }
            if (path.normalize().toString().replace('\\', '/') != raw) {
                return Refinement.Rejected(ChangePreviewPathFailure.NOT_NORMALIZED)
            }
            return Refinement.Refined(ChangePreviewPath(raw))
        }
    }
}

enum class ChangePreviewDiffFailure {
    BLANK,
    TOO_LARGE,
    CONTROL_CHARACTER,
}

@JvmInline
value class ChangePreviewDiff private constructor(val value: String) {
    companion object {
        private const val MAXIMUM_UTF8_BYTES = 512 * 1024

        fun parse(raw: String): Refinement<ChangePreviewDiff, ChangePreviewDiffFailure> = when {
            raw.isBlank() -> Refinement.Rejected(ChangePreviewDiffFailure.BLANK)
            raw.toByteArray(Charsets.UTF_8).size > MAXIMUM_UTF8_BYTES ->
                Refinement.Rejected(ChangePreviewDiffFailure.TOO_LARGE)
            raw.any { character ->
                character.isISOControl() && character !in setOf('\n', '\t')
            } -> Refinement.Rejected(ChangePreviewDiffFailure.CONTROL_CHARACTER)
            else -> Refinement.Refined(ChangePreviewDiff(raw))
        }
    }
}

/** One bounded, transport-safe change fragment whose path and diff retain their domain roles. */
data class ChangeFilePreview(
    val path: ChangePreviewPath,
    val kind: ChangeFilePreviewKind,
    val diff: ChangePreviewDiff,
)

enum class ChangeFilePreviewSetFailure {
    EMPTY,
    DUPLICATE_PATH,
}

/** Non-empty set of files changed by one admitted semantic plan. */
class ChangeFilePreviewSet private constructor(entries: List<ChangeFilePreview>) {
    val entries: List<ChangeFilePreview> = entries.toList()

    override fun equals(other: Any?): Boolean =
        other is ChangeFilePreviewSet && entries == other.entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "ChangeFilePreviewSet(entries=$entries)"

    companion object {
        fun admit(
            entries: List<ChangeFilePreview>,
        ): Refinement<ChangeFilePreviewSet, ChangeFilePreviewSetFailure> = when {
            entries.isEmpty() -> Refinement.Rejected(ChangeFilePreviewSetFailure.EMPTY)
            entries.map { it.path }.distinct().size != entries.size ->
                Refinement.Rejected(ChangeFilePreviewSetFailure.DUPLICATE_PATH)
            else -> Refinement.Refined(ChangeFilePreviewSet(entries))
        }
    }
}

data class ChangePlanResult(
    val planIdentity: ProtocolText,
    val changes: ChangeFilePreviewSet,
) : OperationResult

enum class ChangePlanQualification : OperationQualification {
    OPTIONAL_EVIDENCE_INCOMPLETE,
}

enum class ChangePlanRejection : OperationRejection {
    WORKSPACE_NOT_READY,
    EXACT_SYMBOL_REQUIRED,
    EDITABLE_TARGET_REQUIRED,
    RELATION_READ_REQUIRED,
    TOPOLOGY_BUILD_REQUIRED,
    REQUIRED_TRAVERSAL_INCOMPLETE,
    DIAGNOSTIC_CHECK_REQUIRED,
    RECOVERY_REQUIRED,
    INTENT_REJECTED,
}

@Serializable
data class ChangeApplyRequest(
    val planIdentity: ProtocolText,
) : OperationRequest

data class ChangeApplyResult(
    val receiptIdentity: ProtocolText,
    val changes: ChangeFilePreviewSet,
) : OperationResult

enum class ChangeApplyQualification : OperationQualification {
    RECOVERY_REQUIRED,
}

enum class ChangeApplyRejection : OperationRejection {
    PLAN_NOT_FOUND,
    ROOT_MISMATCH,
    GENERATION_STALE,
    CONTENT_CHANGED,
    WRITE_SCOPE_REJECTED,
    ROLLED_BACK,
    RECOVERY_REQUIRED,
    RESULTING_GENERATION_UNAVAILABLE,
    OBLIGATION_FAILED,
    DIAGNOSTIC_REGRESSION,
    SEMANTIC_DELTA_REJECTED,
}

@Serializable
data class ChangeRecoverRequest(
    val planIdentity: ProtocolText,
) : OperationRequest

data class ChangeRecoverResult(
    val state: ChangeRecoveryDocumentState,
) : OperationResult

enum class ChangeRecoveryDocumentState {
    PRIOR_STATE,
    ROLLED_BACK,
    RECOVERY_REQUIRED,
}

enum class ChangeRecoverQualification : OperationQualification {
    MANUAL_RECOVERY_REQUIRED,
}

enum class ChangeRecoverRejection : OperationRejection {
    PLAN_NOT_FOUND,
    JOURNAL_UNAVAILABLE,
    RECOVERY_FAILED,
}
