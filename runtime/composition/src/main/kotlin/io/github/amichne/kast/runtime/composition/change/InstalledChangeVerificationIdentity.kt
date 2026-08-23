package io.github.amichne.kast.runtime.composition.change

import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.SymbolSelector

internal data class InstalledObservedDeclarationIdentity(
    val packageName: String,
    val kind: AddDeclarationKind,
)

internal sealed interface InstalledObservedDeclarationIdentityObservation {
    data class Observed(
        val identity: InstalledObservedDeclarationIdentity,
    ) : InstalledObservedDeclarationIdentityObservation

    data object Rejected : InstalledObservedDeclarationIdentityObservation
}

private sealed interface InstalledQualifiedContainerObservation {
    data class Observed(
        val value: String,
    ) : InstalledQualifiedContainerObservation

    data object Rejected : InstalledQualifiedContainerObservation
}

/**
 * Proof transition: `(G1 added SymbolSelector, G1 anchor SymbolSelector, compiler-grounded package)
 * -> InstalledObservedDeclarationIdentityObservation`.
 *
 * Observed establishes that the unique added declaration shares the exact compiler-qualified
 * container with the re-resolved insertion anchor and retains the package proven by the planning
 * compiler. Rejected closes missing qualified identities, container movement, and unsupported
 * declaration kinds. Raw qualified names are extracted only inside this result-observation
 * boundary.
 */
internal fun SymbolSelector.observeAddedIdentity(
    anchor: SymbolSelector,
    expectedPackageName: String,
): InstalledObservedDeclarationIdentityObservation {
    val addedContainer = when (val observed = qualifiedContainer()) {
        is InstalledQualifiedContainerObservation.Observed -> observed.value
        InstalledQualifiedContainerObservation.Rejected ->
            return InstalledObservedDeclarationIdentityObservation.Rejected
    }
    val anchorContainer = when (val observed = anchor.expectedAddedContainer()) {
        is InstalledQualifiedContainerObservation.Observed -> observed.value
        InstalledQualifiedContainerObservation.Rejected ->
            return InstalledObservedDeclarationIdentityObservation.Rejected
    }
    if (addedContainer != anchorContainer) {
        return InstalledObservedDeclarationIdentityObservation.Rejected
    }
    val declarationKind = when (kind) {
        CompilerSymbolKind.FUNCTION -> AddDeclarationKind.FUNCTION
        CompilerSymbolKind.PROPERTY -> AddDeclarationKind.PROPERTY
        CompilerSymbolKind.TYPE_ALIAS -> AddDeclarationKind.TYPE_ALIAS
        CompilerSymbolKind.CLASSLIKE,
        CompilerSymbolKind.CONSTRUCTOR,
            -> return InstalledObservedDeclarationIdentityObservation.Rejected
    }
    return InstalledObservedDeclarationIdentityObservation.Observed(
        InstalledObservedDeclarationIdentity(expectedPackageName, declarationKind),
    )
}

/**
 * Proof transition: `insertion-anchor SymbolSelector -> InstalledQualifiedContainerObservation`.
 *
 * Observed establishes the exact compiler-qualified container where AddDeclaration places its
 * result: inside a classlike target, or beside any other declaration. Rejected closes unavailable
 * compiler-qualified identity. Raw qualified-name text remains inside result observation.
 */
private fun SymbolSelector.expectedAddedContainer(): InstalledQualifiedContainerObservation =
    if (kind == CompilerSymbolKind.CLASSLIKE) {
        when (val identity = qualifiedIdentity) {
            is ExactDeclarationQualifiedIdentity.Available ->
                InstalledQualifiedContainerObservation.Observed(identity.value)
            ExactDeclarationQualifiedIdentity.Unavailable ->
                InstalledQualifiedContainerObservation.Rejected
        }
    } else {
        qualifiedContainer()
    }

/**
 * Proof transition: `SymbolSelector -> InstalledQualifiedContainerObservation`.
 *
 * Observed establishes the exact compiler-qualified container prefix after removing the selector's
 * own canonical name. Rejected closes unavailable or structurally inconsistent qualified identity.
 * Raw qualified-name text is extracted only inside this result-observation boundary.
 */
private fun SymbolSelector.qualifiedContainer(): InstalledQualifiedContainerObservation {
    val qualified = (qualifiedIdentity as? ExactDeclarationQualifiedIdentity.Available)?.value
                    ?: return InstalledQualifiedContainerObservation.Rejected
    val suffix = ".${name.value}"
    return when {
        qualified == name.value -> InstalledQualifiedContainerObservation.Observed("")
        qualified.endsWith(suffix) -> InstalledQualifiedContainerObservation.Observed(
            qualified.removeSuffix(suffix),
        )
        else -> InstalledQualifiedContainerObservation.Rejected
    }
}

internal sealed interface InstalledPriorDeclarationMatch {
    data object Matched : InstalledPriorDeclarationMatch

    data object MovedOrChanged : InstalledPriorDeclarationMatch
}

/**
 * Proof transition: `(G1 SymbolSelector, G0 SymbolSelector) -> InstalledPriorDeclarationMatch`.
 *
 * Matched establishes the same file, declaration start, name, qualified identity, and kind across
 * generations while permitting the planned AddDeclaration to extend the target's end range.
 * Discovery scope is request provenance rather than declaration identity and remains subject to
 * the verification service's relation-target proof.
 * [InstalledPriorDeclarationMatch.MovedOrChanged] closes every identity mismatch. Raw selector
 * fields remain inside the resulting-generation observation boundary.
 */
internal fun SymbolSelector.matchDeclarationAcrossGeneration(
    prior: SymbolSelector,
): InstalledPriorDeclarationMatch = if (
    file == prior.file &&
    range.startInclusive == prior.range.startInclusive &&
    name == prior.name &&
    qualifiedIdentity == prior.qualifiedIdentity &&
    kind == prior.kind
) {
    InstalledPriorDeclarationMatch.Matched
} else {
    InstalledPriorDeclarationMatch.MovedOrChanged
}
