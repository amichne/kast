package io.github.amichne.kast.idea.backend.mutation

import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.api.contract.ExactFileImagePath
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.result.MutationPostconditionEvidence
import io.github.amichne.kast.api.contract.result.ReplacementOutboundEvidence
import io.github.amichne.kast.api.protocol.MutationPostconditionFailedException
import io.github.amichne.kast.api.protocol.MutationPostconditionLimitation
import io.github.amichne.kast.api.protocol.ReplacementProofIncompleteException
import io.github.amichne.kast.api.validation.ParsedMutationPostconditionAuthority
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction

private sealed interface ReplacementPostconditionAdmission {
    data class Verified(
        val evidence: MutationPostconditionEvidence.Replacement,
        val diagnostics: ReplacementBodyDiagnosticObservation.ErrorFree,
    ) : ReplacementPostconditionAdmission

    data class Rejected(
        val limitation: MutationPostconditionLimitation,
        val message: String,
    ) : ReplacementPostconditionAdmission
}

/**
 * Postcondition boundary projection from exact replacement authority to verified receipt evidence.
 *
 * [ReplacementPostconditionAdmission] keeps every expected semantic failure finite below this
 * boundary. This function alone projects a rejection into the public postcondition exception
 * protocol consumed by the JSON-RPC dispatcher.
 */
internal fun KastIndexerBackend.verifyReplacement(
    authority: ParsedMutationPostconditionAuthority.Replacement,
): MutationPostconditionEvidence.Replacement = when (
    val admission = admitReplacementPostcondition(authority)
) {
    is ReplacementPostconditionAdmission.Verified -> admission.evidence
    is ReplacementPostconditionAdmission.Rejected ->
        failPostcondition(admission.limitation, admission.message)
}

/**
 * Proof transition: parsed replacement postcondition authority ->
 * [ReplacementPostconditionAdmission].
 *
 * A verified result establishes unchanged compiler context, exact body bytes/range, complete
 * target-body diagnostics, stable function identity/signature, and the exact compiler outbound
 * set. Failure is the closed [MutationPostconditionLimitation] carried by
 * [ReplacementPostconditionAdmission.Rejected]. Raw PSI and edit text may be extracted only inside
 * this indexer verification boundary.
 */
private fun KastIndexerBackend.admitReplacementPostcondition(
    authority: ParsedMutationPostconditionAuthority.Replacement,
): ReplacementPostconditionAdmission {
    val currentCompilerContext = when (
        val observation = observeReplacementCompilerContext(
            ExactFileImagePath(authority.proof.target.declarationFile.value),
        )
    ) {
        is ReplacementCompilerContextObservation.Proven -> observation.context
        is ReplacementCompilerContextObservation.Rejected -> return ReplacementPostconditionAdmission.Rejected(
            MutationPostconditionLimitation.SOURCE_CONTEXT_CHANGED,
            "Replacement compiler context could not be observed: ${observation.failure.name}",
        )
    }
    if (currentCompilerContext != authority.proof.compilerContext) {
        return ReplacementPostconditionAdmission.Rejected(
            MutationPostconditionLimitation.SOURCE_CONTEXT_CHANGED,
            "Kotlin or Java compiler context outside the replacement write set changed",
        )
    }
    val file = try {
        exactPostimageKtFile(authority.edit.filePath)
    } catch (failure: MutationPostconditionFailedException) {
        return ReplacementPostconditionAdmission.Rejected(
            failure.limitations.first(),
            failure.message ?: "The exact replacement postimage was unavailable",
        )
    }
    val targets = PsiTreeUtil.findChildrenOfType(file, KtNamedDeclaration::class.java)
        .filter { declaration ->
            declaration.nameIdentifier?.textRange?.startOffset ==
                authority.proof.target.declarationStartOffset.value
        }
    if (targets.size != 1 || targets.single() !is KtNamedFunction) {
        return ReplacementPostconditionAdmission.Rejected(
            MutationPostconditionLimitation.TARGET_IDENTITY_MISMATCH,
            "The exact replacement function is not present at its compiler declaration offset",
        )
    }
    val target = targets.single() as KtNamedFunction
    val body = target.bodyExpression ?: return ReplacementPostconditionAdmission.Rejected(
        MutationPostconditionLimitation.TARGET_IDENTITY_MISMATCH,
        "The exact replacement function has no resulting declaration body",
    )
    if (
        body.textRange.startOffset != authority.edit.startOffset ||
        body.textRange.endOffset != authority.edit.startOffset + authority.edit.newText.length ||
        body.text != authority.edit.newText
    ) {
        return ReplacementPostconditionAdmission.Rejected(
            MutationPostconditionLimitation.POSTIMAGE_MISMATCH,
            "The semantic replacement body does not equal its exact persisted body write",
        )
    }
    val diagnosticProof = when (observeReplacementBodyDiagnostics(file, body)) {
        ReplacementBodyDiagnosticObservation.ErrorFree ->
            ReplacementBodyDiagnosticObservation.ErrorFree

        ReplacementBodyDiagnosticObservation.ContainsErrors ->
            return ReplacementPostconditionAdmission.Rejected(
                MutationPostconditionLimitation.SOURCE_CONTEXT_CHANGED,
                "The exact replacement body gained compiler error diagnostics",
            )

        ReplacementBodyDiagnosticObservation.Unavailable ->
            return ReplacementPostconditionAdmission.Rejected(
                MutationPostconditionLimitation.SEMANTIC_SOURCE_UNAVAILABLE,
                "The exact replacement body diagnostics could not be completed",
            )
    }
    val compilerTarget = compilerSourceIdentity(target)
    val expected = authority.proof.target
    if (
        compilerTarget.fqName != expected.fqName ||
        compilerTarget.kind != expected.kind ||
        compilerTarget.containingType != expected.containingType
    ) {
        return ReplacementPostconditionAdmission.Rejected(
            MutationPostconditionLimitation.TARGET_IDENTITY_MISMATCH,
            "The replacement changed its compiler FQ owner or declaration kind",
        )
    }
    val resultingTarget = compilerTarget.copy(declarationFile = expected.declarationFile)
    val signature = try {
        compilerReplacementSignature(target)
    } catch (_: ReplacementProofIncompleteException) {
        return ReplacementPostconditionAdmission.Rejected(
            MutationPostconditionLimitation.SIGNATURE_MISMATCH,
            "The resulting replacement compiler signature is incomplete",
        )
    }
    if (signature != authority.proof.proposedSignature) {
        return ReplacementPostconditionAdmission.Rejected(
            MutationPostconditionLimitation.SIGNATURE_MISMATCH,
            "The resulting replacement compiler signature changed",
        )
    }
    val annotationProof = when (val admission = admitAnnotationFreeReplacementFunction(target)) {
        is ReplacementAdmission.Admitted -> admission.value
        is ReplacementAdmission.Rejected -> return ReplacementPostconditionAdmission.Rejected(
            MutationPostconditionLimitation.OUTBOUND_SET_MISMATCH,
            "The resulting replacement annotation proof is incomplete: ${admission.rejection.limitation.name}",
        )
    }
    val referenceProof = when (
        val admission = admitExplicitReferenceReplacementFunction(annotationProof)
    ) {
        is ReplacementAdmission.Admitted -> admission.value
        is ReplacementAdmission.Rejected -> return ReplacementPostconditionAdmission.Rejected(
            MutationPostconditionLimitation.OUTBOUND_SET_MISMATCH,
            "The resulting replacement reference proof is incomplete: ${admission.rejection.limitation.name}",
        )
    }
    val outbound = when (
        val admission = collectExactOutboundReferences(
            syntheticFile = file,
            proposed = referenceProof,
            replacement = body,
            replacementStartOffset = authority.edit.startOffset,
            proposedBodyText = authority.edit.newText,
            sourceIdentityBasis = ReplacementSourceIdentityBasis.PersistedPreimage(
                preimageBodyLength = PositiveInt(
                    authority.proof.sourceRange.endOffset - authority.proof.sourceRange.startOffset,
                ),
            ),
        )
    ) {
        is ReplacementAdmission.Admitted -> admission.value
        is ReplacementAdmission.Rejected -> return ReplacementPostconditionAdmission.Rejected(
            MutationPostconditionLimitation.OUTBOUND_SET_MISMATCH,
            "The resulting replacement outbound proof is incomplete: ${admission.rejection.limitation.name}",
        )
    }
    if (outbound != authority.proof.outboundReferences) {
        return ReplacementPostconditionAdmission.Rejected(
            MutationPostconditionLimitation.OUTBOUND_SET_MISMATCH,
            "The resulting replacement outbound occurrence set changed",
        )
    }
    return ReplacementPostconditionAdmission.Verified(
        evidence = MutationPostconditionEvidence.Replacement(
            resultingTarget = resultingTarget,
            sourceRange = authority.proof.sourceRange,
            signature = signature,
            outboundEvidence = ReplacementOutboundEvidence.Complete.of(outbound.size),
            outboundReferences = outbound,
        ),
        diagnostics = diagnosticProof,
    )
}
