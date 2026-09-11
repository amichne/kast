package io.github.amichne.kast.change.intellij

import io.github.amichne.kast.change.apply.MutationAuthority
import io.github.amichne.kast.change.apply.MutationPreconditionAtIntellijBoundary
import io.github.amichne.kast.change.apply.SourceWriteFailure
import io.github.amichne.kast.change.contract.SourceTextMutation
import io.github.amichne.kast.kernel.Refinement

/**
 * Proof transition: `MutationAuthority -> Refinement<IntellijMutationInput, SourceWriteFailure>`.
 *
 * Establishes an existing-source preimage plus only exact in-file transformations for the document protocol.
 * [SourceWriteFailure] closes absent or whole-file creation authority. Raw text extraction remains inside this IntelliJ
 * boundary.
 */
internal fun MutationAuthority.toIntellijInput():
    Refinement<
        IntellijMutationInput,
        SourceWriteFailure,
    > {
    val preimage =
        when (val expected = preconditionAtIntellijBoundary()) {
            MutationPreconditionAtIntellijBoundary.Absent ->
                return Refinement.Rejected(SourceWriteFailure.PREIMAGE_CHANGED)
            is MutationPreconditionAtIntellijBoundary.Existing -> expected.text
        }
    return projectIntellijMutationInput(
        path = source.path.value,
        preimage = preimage,
        postimage = postimageTextAtIntellijBoundary(),
        sourceMutations = mutationsAtIntellijBoundary(),
    )
}

internal fun projectIntellijMutationInput(
    path: String,
    preimage: String,
    postimage: String,
    sourceMutations: List<SourceTextMutation>,
): Refinement<IntellijMutationInput, SourceWriteFailure> {
    val mutations = sourceMutations.map { mutation ->
        when (mutation) {
            is SourceTextMutation.CreateFile -> return Refinement.Rejected(SourceWriteFailure.MUTATION_FAILED)
            is SourceTextMutation.InsertAfterDeclaration ->
                IntellijTextMutation(
                    mutation.anchor.endExclusive,
                    mutation.anchor.endExclusive,
                    "\n\n${mutation.declaration.value}",
                )
            is SourceTextMutation.InsertIntoClassBody ->
                IntellijTextMutation(
                    mutation.anchor.endExclusive - 1,
                    mutation.anchor.endExclusive - 1,
                    "\n    ${mutation.declaration.value}\n",
                )
            is SourceTextMutation.Replace ->
                IntellijTextMutation(
                    mutation.range.startInclusive,
                    mutation.range.endExclusive,
                    mutation.replacement.value,
                )
            is SourceTextMutation.ReplaceDeclaration ->
                IntellijTextMutation(
                    mutation.range.startInclusive,
                    mutation.range.endExclusive,
                    mutation.replacement.value,
                )
        }
    }
    return Refinement.Refined(
        IntellijMutationInput(
            sourcePath = path,
            preimageText = preimage,
            postimageText = postimage,
            mutations = mutations,
        )
    )
}
