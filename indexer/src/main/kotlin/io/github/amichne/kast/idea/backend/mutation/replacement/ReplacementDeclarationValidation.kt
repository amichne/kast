package io.github.amichne.kast.idea.backend.mutation

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.result.ReplacementContractAdmission
import io.github.amichne.kast.api.contract.result.ReplacementDeclarationSlice
import io.github.amichne.kast.api.contract.result.ReplacementSubmittedBodySlice
import io.github.amichne.kast.api.protocol.ReplacementProofLimitation
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory

internal data class ParsedProposedDeclaration(
    val declaration: KtNamedFunction,
    val nameOffset: Int,
    val declarationSlice: ReplacementDeclarationSlice,
    val proposedBodySlice: ReplacementSubmittedBodySlice,
)

internal class AnnotationFreeReplacementFunction private constructor(
    val declaration: KtNamedFunction,
) {
    companion object {
        internal fun admitted(declaration: KtNamedFunction): AnnotationFreeReplacementFunction =
            AnnotationFreeReplacementFunction(declaration)
    }
}

internal class ExplicitReferenceReplacementFunction private constructor(
    val declaration: KtNamedFunction,
) {
    companion object {
        internal fun admitted(declaration: KtNamedFunction): ExplicitReferenceReplacementFunction =
            ExplicitReferenceReplacementFunction(declaration)
    }
}

/**
 * Proof transition: [String] -> [ReplacementAdmission] of [ParsedProposedDeclaration].
 *
 * Establishes exactly one syntactically valid named Kotlin function with a non-empty declaration
 * and body slice inside the submitted text. Failure is a closed [ReplacementProofRejection].
 * Raw text and PSI may be extracted only while constructing copied-PSI preflight evidence.
 */
internal fun KastIndexerBackend.parseProposedDeclaration(
    text: String,
): ReplacementAdmission<ParsedProposedDeclaration> {
    val parsed = KtPsiFactory(project).createFile("KastProposedReplacement.kt", text)
    val declarations = parsed.declarations
    if (declarations.isEmpty()) {
        return replacementRejection(
            ReplacementProofLimitation.ZERO_REPLACEMENT_DECLARATIONS,
            "The proposed replacement must contain exactly one Kotlin declaration",
        )
    }
    if (declarations.size > 1) {
        return replacementRejection(
            ReplacementProofLimitation.MULTIPLE_REPLACEMENT_DECLARATIONS,
            "The proposed replacement contains more than one Kotlin declaration",
        )
    }
    if (PsiTreeUtil.findChildOfType(parsed, PsiErrorElement::class.java) != null) {
        return replacementRejection(
            ReplacementProofLimitation.PROPOSED_DECLARATION_SYNTAX_INVALID,
            "The proposed replacement declaration contains Kotlin syntax errors",
        )
    }
    val declaration = when (val candidate = declarations.single()) {
        is KtNamedFunction -> candidate
        else -> return replacementRejection(
            ReplacementProofLimitation.UNSUPPORTED_REPLACEMENT_KIND,
            "The proposed declaration-body replacement is not a named Kotlin function",
        )
    }
    val body = declaration.bodyExpression ?: return replacementRejection(
        ReplacementProofLimitation.UNSUPPORTED_REPLACEMENT_CONTENT,
        "The proposed Kotlin function has no declaration body",
    )
    val declarationRange = declaration.textRange
    if (
        text.substring(0, declarationRange.startOffset).isNotBlank() ||
        text.substring(declarationRange.endOffset).isNotBlank()
    ) {
        return replacementRejection(
            ReplacementProofLimitation.UNSUPPORTED_REPLACEMENT_CONTENT,
            "The proposed replacement must contain only one declaration",
        )
    }
    val nameOffset = declaration.nameIdentifier?.textRange?.startOffset
        ?: return replacementRejection(
            ReplacementProofLimitation.PROPOSED_DECLARATION_SYNTAX_INVALID,
            "The proposed replacement declaration has no compiler-visible name",
        )
    val declarationSlice = when (
        val admission = ReplacementDeclarationSlice.of(
            startOffset = NonNegativeInt(declarationRange.startOffset),
            endOffset = NonNegativeInt(declarationRange.endOffset),
        )
    ) {
        is ReplacementContractAdmission.Admitted -> admission.value
        is ReplacementContractAdmission.Rejected -> return replacementRejection(
            ReplacementProofLimitation.PROPOSED_DECLARATION_SYNTAX_INVALID,
            "The proposed replacement declaration slice is empty",
        )
    }
    val proposedBodySlice = when (
        val admission = ReplacementSubmittedBodySlice.of(
            startOffset = NonNegativeInt(body.textRange.startOffset),
            endOffset = NonNegativeInt(body.textRange.endOffset),
        )
    ) {
        is ReplacementContractAdmission.Admitted -> admission.value
        is ReplacementContractAdmission.Rejected -> return replacementRejection(
            ReplacementProofLimitation.UNSUPPORTED_REPLACEMENT_CONTENT,
            "The proposed replacement body slice is empty",
        )
    }
    return ReplacementAdmission.Admitted(
        ParsedProposedDeclaration(
            declaration = declaration,
            nameOffset = nameOffset,
            declarationSlice = declarationSlice,
            proposedBodySlice = proposedBodySlice,
        ),
    )
}

/**
 * Proof transition: [KtNamedFunction] -> [ReplacementAdmission] of
 * [AnnotationFreeReplacementFunction].
 *
 * Establishes that function annotations cannot escape the replacement proof. Failure is a closed
 * [ReplacementProofRejection]. Raw PSI may be extracted only by indexer planning.
 */
internal fun admitAnnotationFreeReplacementFunction(
    declaration: KtNamedFunction,
): ReplacementAdmission<AnnotationFreeReplacementFunction> =
    if (declaration.annotationEntries.isEmpty()) {
        ReplacementAdmission.Admitted(AnnotationFreeReplacementFunction.admitted(declaration))
    } else {
        replacementRejection(
            ReplacementProofLimitation.UNSUPPORTED_DECLARATION_ANNOTATION,
            "Declaration-body replacement proof does not model function annotations",
        )
    }

/**
 * Proof transition: [AnnotationFreeReplacementFunction] -> [ReplacementAdmission] of
 * [ExplicitReferenceReplacementFunction].
 *
 * Establishes that the function contains no implicit-call syntax outside the exact K2 outbound
 * model. Failure is a closed [ReplacementProofRejection]. Raw PSI may be extracted only by the
 * indexer reference traversal boundary.
 */
internal fun admitExplicitReferenceReplacementFunction(
    function: AnnotationFreeReplacementFunction,
): ReplacementAdmission<ExplicitReferenceReplacementFunction> =
    when (function.declaration.implicitCallSyntax()) {
        ImplicitCallSyntax.ExplicitOnly ->
            ReplacementAdmission.Admitted(
                ExplicitReferenceReplacementFunction.admitted(function.declaration),
            )

        ImplicitCallSyntax.Unsupported -> replacementRejection(
            ReplacementProofLimitation.UNSUPPORTED_REFERENCE_KIND,
            "The proposed replacement contains implicit-call syntax that exact outbound proof does not model",
        )
    }

private sealed interface ImplicitCallSyntax {
    data object ExplicitOnly : ImplicitCallSyntax
    data object Unsupported : ImplicitCallSyntax
}

private fun KtNamedDeclaration.implicitCallSyntax(): ImplicitCallSyntax = when {
    PsiTreeUtil.findChildOfType(this, KtForExpression::class.java) != null ->
        ImplicitCallSyntax.Unsupported

    PsiTreeUtil.findChildOfType(this, KtArrayAccessExpression::class.java) != null ->
        ImplicitCallSyntax.Unsupported

    PsiTreeUtil.findChildOfType(this, KtDestructuringDeclaration::class.java) != null ->
        ImplicitCallSyntax.Unsupported

    this is KtProperty && delegateExpression != null ->
        ImplicitCallSyntax.Unsupported

    PsiTreeUtil.findChildrenOfType(this, KtProperty::class.java).any { property ->
        property.delegateExpression != null
    } -> ImplicitCallSyntax.Unsupported

    else -> ImplicitCallSyntax.ExplicitOnly
}
