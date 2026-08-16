package io.github.amichne.kast.change.apply

import io.github.amichne.kast.change.contract.AddDeclarationChangePlan
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
data class AddDeclarationApplyRequest(
    val plan: AddDeclarationChangePlan,
    val workspace: PublishedWorkspace,
    val writeScope: RequestedMutationWriteScope,
)

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

/** Exact detached source bytes, text, identity, and writability observed at one physical boundary. */
class ObservedMutationSource private constructor(
    val source: SymbolDiscoveryFileIdentity.Workspace,
    val content: WorkspaceSourceContentHash,
    val access: SourceWriteAccess,
    internal val text: String,
    internal val recoveryPreimage: RecoveryPreimage,
) {
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
