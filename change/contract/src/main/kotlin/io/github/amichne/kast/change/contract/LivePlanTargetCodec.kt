package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackage
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackageConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeKind
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeSnapshot
import io.github.amichne.kast.symbol.contract.SymbolSelectorFingerprint
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.WorkspaceSourceSetName
import java.nio.file.Path

internal fun LiveAddDeclarationPlanDocument.restoreTarget(
    basis: LiveChangeBasis
): Refinement<PlannedDeclarationIdentity, LiveAddDeclarationPlanDecodeFailure> {
    val path = target.sourcePath.pathOrNull() ?: return rejected()
    val file =
        SymbolDiscoveryFileIdentity.fromBoundary(basis.reference.workspaceRoot, path, path.toUri().toString())
            .valueOrNull() as? SymbolDiscoveryFileIdentity.Workspace
            ?: return rejected(LiveAddDeclarationPlanDecodeFailure.TARGET_MISMATCH)
    val restoredScope =
        when (val restored = restoreScope(basis)) {
            is Refinement.Refined -> restored.value
            is Refinement.Rejected -> return restored
        }
    val restoredConstraints =
        when (val restored = constraints.restore()) {
            is Refinement.Refined -> restored.value
            is Refinement.Rejected -> return restored
        }
    val signature =
        CanonicalCompilerSignature.restoreCanonicalEncoding(target.signature).valueOrNull() ?: return rejected()
    val compilerIdentity = CompilerSymbolIdentity.parse(target.compilerIdentity).valueOrNull() ?: return rejected()
    val compiler =
        CompilerGroundedSymbolEvidence.restoreBoundary(
                file = file,
                rawStartInclusive = target.start,
                rawEndExclusive = target.end,
                rawName = target.name,
                rawQualifiedIdentity = target.qualifiedIdentity,
                kind = target.kind.enumOrNull<CompilerSymbolKind>() ?: return rejected(),
                signature = signature,
                compilerIdentity = compilerIdentity,
            )
            .valueOrNull() ?: return rejected(LiveAddDeclarationPlanDecodeFailure.TARGET_MISMATCH)
    val fingerprint = SymbolSelectorFingerprint.parse(target.fingerprint).valueOrNull() ?: return rejected()
    return PlannedDeclarationIdentity.restore(
        basis = basis,
        evidence = compiler,
        fingerprint = fingerprint,
        scope = restoredScope,
        constraints = restoredConstraints,
    )
}

private fun LivePlanConstraintsDocument.restore():
    Refinement<SymbolDiscoveryConstraints, LiveAddDeclarationPlanDecodeFailure> {
    val restoredDirectory = directory?.let { captured ->
        SymbolDiscoveryDirectoryConstraint(
            SymbolDiscoveryDirectory.parse(captured.value).valueOrNull() ?: return rejected(),
            captured.containment.enumOrNull<SymbolDiscoveryContainment>() ?: return rejected(),
        )
    }
    val restoredPackage = packageName?.let { captured ->
        SymbolDiscoveryPackageConstraint(
            SymbolDiscoveryPackage.parse(captured.value).valueOrNull() ?: return rejected(),
            captured.containment.enumOrNull<SymbolDiscoveryContainment>() ?: return rejected(),
        )
    }
    val restoredKinds = declarationKinds?.let { kinds ->
        SymbolDiscoveryDeclarationKinds.from(
                kinds.map { it.enumOrNull<CompilerSymbolKind>() ?: return rejected() }.toSet()
            )
            .valueOrNull() ?: return rejected()
    }
    val restoredSourceSets = restoreSourceSets().valueOrNull() ?: return rejected()
    return Refinement.Refined(
        SymbolDiscoveryConstraints(
            directory = restoredDirectory,
            packageName = restoredPackage,
            declarationKinds = restoredKinds,
            sourceSets = restoredSourceSets,
        )
    )
}

private fun LiveAddDeclarationPlanDocument.restoreScope(
    basis: LiveChangeBasis
): Refinement<SymbolSearchScope, LiveAddDeclarationPlanDecodeFailure> {
    val capturedScope =
        SymbolSearchScopeSnapshot(
            kind = scope.kind.enumOrNull<SymbolSearchScopeKind>() ?: return rejected(),
            primary = scope.primary,
            secondary = scope.secondary,
            sourceKinds = scope.sourceKinds.enumOrNull<SymbolSourceKindPolicy>() ?: return rejected(),
            generatedSources = scope.generatedSources.enumOrNull<SymbolGeneratedSourcePolicy>() ?: return rejected(),
            libraries = scope.libraries?.let { it.enumOrNull<SymbolLibraryPolicy>() ?: return rejected() },
        )
    return Refinement.Refined(SymbolSearchScope.restore(basis.model, capturedScope).valueOrNull() ?: return rejected())
}

private fun LivePlanConstraintsDocument.restoreSourceSets():
    Refinement<SymbolDiscoverySourceSets, LiveAddDeclarationPlanDecodeFailure> {
    return Refinement.Refined(
        sourceSets?.let { names ->
            SymbolDiscoverySourceSets.Exact.from(
                    names.map { WorkspaceSourceSetName.parse(it).valueOrNull() ?: return rejected() }.toSet()
                )
                .valueOrNull() ?: return rejected()
        } ?: SymbolDiscoverySourceSets.All
    )
}

private fun rejected(failure: LiveAddDeclarationPlanDecodeFailure = LiveAddDeclarationPlanDecodeFailure.MALFORMED) =
    Refinement.Rejected(failure)

private fun String.pathOrNull(): Path? =
    try {
        Path.of(this)
    } catch (_: IllegalArgumentException) {
        null
    }

private inline fun <reified T : Enum<T>> String.enumOrNull(): T? = enumValues<T>().singleOrNull { it.name == this }

private fun <T, F> Refinement<T, F>.valueOrNull(): T? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }
