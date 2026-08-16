package io.github.amichne.kast.change.apply

import io.github.amichne.kast.change.contract.ChangePlan
import io.github.amichne.kast.evidence.contract.RecoveryPreimage
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Boundary request whose write scope is still weaker than mutation authority. */
data class ChangeApplyRequest(
    val plan: ChangePlan,
    val workspace: PublishedWorkspace,
    val writeScope: RequestedMutationWriteScope,
)

typealias AddDeclarationApplyRequest = ChangeApplyRequest

/** Exact root and source identities the caller permits this one operation to change. */
data class RequestedMutationWriteScope(
    val root: CanonicalWorkspaceRoot,
    val sources: Set<SymbolDiscoveryFileIdentity.Workspace>,
)

/** Closed physical writability observation. */
sealed interface SourceWriteAccess {
    data object Writable : SourceWriteAccess

    data object ReadOnly : SourceWriteAccess
}

/** Finite failures while refining raw source bytes into detached current-source evidence. */
enum class MutationSourceCaptureFailure {
    INVALID_UTF8,
    SOURCE_HASH_UNREPRESENTABLE,
}

/** Closed exact source state observed immediately before mutation admission. */
sealed interface ObservedMutationPrecondition {
    val source: SymbolDiscoveryFileIdentity.Workspace
    val access: SourceWriteAccess
    val recoveryPreimage: RecoveryPreimage
}

/** Exact detached source bytes, text, identity, and writability observed at one physical boundary. */
class ObservedMutationSource private constructor(
    override val source: SymbolDiscoveryFileIdentity.Workspace,
    val content: WorkspaceSourceContentHash,
    override val access: SourceWriteAccess,
    internal val text: String,
    override val recoveryPreimage: RecoveryPreimage,
) : ObservedMutationPrecondition {
    companion object {
        /**
         * Proof transition: `(WorkspaceFile, ByteArray, SourceWriteAccess) -> Refinement<
         * ObservedMutationSource, MutationSourceCaptureFailure>`.
         *
         * Establishes strict UTF-8 source text plus one SHA-256-bound immutable recovery preimage
         * for the exact observed file and writability state. [MutationSourceCaptureFailure] is the
         * closed expected failure. Raw bytes may enter only from an IntelliJ physical source
         * observation and may leave only through a later admitted mutation or rollback boundary.
         */
        fun capture(
            source: SymbolDiscoveryFileIdentity.Workspace,
            rawContent: ByteArray,
            access: SourceWriteAccess,
        ): Refinement<ObservedMutationSource, MutationSourceCaptureFailure> {
            val text = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(rawContent))
                    .toString()
            } catch (_: Exception) {
                return Refinement.Rejected(MutationSourceCaptureFailure.INVALID_UTF8)
            }
            val preimage = RecoveryPreimage.fromBoundary(rawContent)
            val content = when (
                val parsed = WorkspaceSourceContentHash.parse(preimage.digest.value)
            ) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    MutationSourceCaptureFailure.SOURCE_HASH_UNREPRESENTABLE,
                )
            }
            return Refinement.Refined(
                ObservedMutationSource(source, content, access, text, preimage),
            )
        }
    }
}

/** Exact absent source identity plus its parent-derived creation access observation. */
class ObservedAbsentMutationSource private constructor(
    override val source: SymbolDiscoveryFileIdentity.Workspace,
    override val access: SourceWriteAccess,
    override val recoveryPreimage: RecoveryPreimage,
) : ObservedMutationPrecondition {
    companion object {
        /**
         * Proof transition: `(WorkspaceFile, SourceWriteAccess) ->
         * ObservedAbsentMutationSource`.
         *
         * Establishes that the physical boundary observed no directory entry at the exact path,
         * retains parent-derived creation access, and carries the canonical absence recovery
         * marker. There is no expected failure because absence and access were already observed by
         * the physical adapter. Raw filesystem state may enter only at that adapter boundary.
         */
        fun fromPhysicalBoundary(
            source: SymbolDiscoveryFileIdentity.Workspace,
            access: SourceWriteAccess,
        ): ObservedAbsentMutationSource = ObservedAbsentMutationSource(
            source,
            access,
            RecoveryPreimage.fromBoundary(ByteArray(0)),
        )
    }
}
