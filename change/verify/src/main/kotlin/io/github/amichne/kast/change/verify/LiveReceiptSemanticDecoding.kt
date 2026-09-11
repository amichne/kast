package io.github.amichne.kast.change.verify

import io.github.amichne.kast.change.contract.LiveAddDeclarationChangePlan
import io.github.amichne.kast.change.contract.LiveChangeBasis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.workspace.contract.IdeReadContentView
import io.github.amichne.kast.workspace.contract.IdeReadEpochRevision
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import io.github.amichne.kast.workspace.contract.LiveSemanticReadReference
import java.util.UUID

internal fun decodeReceiptAfter(
    plan: LiveAddDeclarationChangePlan,
    body: LiveReceiptBody,
): Refinement<LiveChangeBasis, LiveReceiptFailure> {
    val original = plan.basis.observation
    if (
        body.after.model != body.plan.getValue("model") ||
            body.after.root != original.reference.workspaceRoot.value ||
            body.source != plan.target.file.path.value
    ) {
        return Refinement.Rejected(LiveReceiptFailure.RESULTING_BASIS_MISMATCH)
    }
    val owner =
        try {
            UUID.fromString(body.after.owner)
        } catch (_: IllegalArgumentException) {
            return Refinement.Rejected(LiveReceiptFailure.MALFORMED)
        }
    if (owner.toString() != body.after.owner) return Refinement.Rejected(LiveReceiptFailure.MALFORMED)
    val view =
        IdeReadContentView.entries.singleOrNull { it.name == body.after.contentView }
            ?: return Refinement.Rejected(LiveReceiptFailure.MALFORMED)
    val epoch =
        when (val decoded = IdeReadEpochRevision.parse(body.after.epoch)) {
            is Refinement.Refined -> decoded.value
            is Refinement.Rejected -> return Refinement.Rejected(LiveReceiptFailure.MALFORMED)
        }
    val reference =
        LiveSemanticReadReference(
            workspaceRoot = original.reference.workspaceRoot,
            host = IdeReadHostLifetime.fromBoundary(owner),
            epoch = epoch,
            contentView = view,
            version = body.after.version,
        )
    return when (val decoded = LiveChangeBasis.observe(reference, original.model)) {
        is Refinement.Refined -> decoded
        is Refinement.Rejected -> Refinement.Rejected(LiveReceiptFailure.RESULTING_BASIS_MISMATCH)
    }
}

internal fun decodeReceiptAnchor(
    plan: LiveAddDeclarationChangePlan,
    body: LiveReceiptAnchor,
): Refinement<CompilerGroundedSymbolEvidence, LiveReceiptFailure> {
    val signature =
        when (val decoded = CanonicalCompilerSignature.restoreCanonicalEncoding(body.signature)) {
            is Refinement.Refined -> decoded.value
            is Refinement.Rejected -> return Refinement.Rejected(LiveReceiptFailure.COMPILER_EVIDENCE_MISMATCH)
        }
    val identity =
        when (val decoded = CompilerSymbolIdentity.parse(body.compilerIdentity)) {
            is Refinement.Refined -> decoded.value
            is Refinement.Rejected -> return Refinement.Rejected(LiveReceiptFailure.COMPILER_EVIDENCE_MISMATCH)
        }
    val kind =
        CompilerSymbolKind.entries.singleOrNull { it.name == body.kind }
            ?: return Refinement.Rejected(LiveReceiptFailure.COMPILER_EVIDENCE_MISMATCH)
    return when (
        val decoded =
            CompilerGroundedSymbolEvidence.restoreBoundary(
                file = plan.target.file,
                rawStartInclusive = body.start,
                rawEndExclusive = body.end,
                rawName = body.name,
                rawQualifiedIdentity = body.qualifiedIdentity,
                kind = kind,
                signature = signature,
                compilerIdentity = identity,
            )
    ) {
        is Refinement.Refined -> decoded
        is Refinement.Rejected -> Refinement.Rejected(LiveReceiptFailure.COMPILER_EVIDENCE_MISMATCH)
    }
}
