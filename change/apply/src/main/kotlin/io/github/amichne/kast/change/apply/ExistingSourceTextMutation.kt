package io.github.amichne.kast.change.apply

import io.github.amichne.kast.change.contract.SourceTextMutation
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** Deterministic single-mutation rendering after the preimage and non-overlap checks have passed. */
internal fun SourceTextMutation.applyToExistingSource(source: String): Refinement<String, MutationAdmissionFailure> =
    when (this) {
        is SourceTextMutation.CreateFile -> Refinement.Rejected(MutationAdmissionFailure.MUTATION_KIND_MISMATCH)
        is SourceTextMutation.InsertAfterDeclaration -> {
            val offset = anchor.endExclusive
            Refinement.Refined(source.substring(0, offset) + "\n\n${declaration.value}" + source.substring(offset))
        }
        is SourceTextMutation.InsertIntoClassBody -> {
            val offset = anchor.endExclusive - 1
            Refinement.Refined(source.substring(0, offset) + "\n    ${declaration.value}\n" + source.substring(offset))
        }
        is SourceTextMutation.Replace -> Refinement.Refined(splice(source, range, replacement.value))
        is SourceTextMutation.ReplaceDeclaration -> Refinement.Refined(splice(source, range, replacement.value))
        is SourceTextMutation.ReplaceBody -> Refinement.Refined(splice(source, range, replacement.value))
    }

private fun splice(source: String, range: ExactDeclarationTextRange, replacement: String): String =
    source.substring(0, range.startInclusive) + replacement + source.substring(range.endExclusive)
