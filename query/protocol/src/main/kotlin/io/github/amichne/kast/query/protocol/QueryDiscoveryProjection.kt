package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryBlockDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryBytesDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryCountDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryDeclarationLanguageDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryNanosecondsDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryObservationDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryOrderDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryProgressDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourceSetsDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryStopDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryUniverseDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryPackageScopeDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.query.contract.QueryDeclarationLanguage
import io.github.amichne.kast.query.contract.QueryDiscoveryObservation
import io.github.amichne.kast.query.contract.QueryDiscoveryProgress
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPackageConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy

/** A request-local measured page retains its universe, provider ordering and precise stopping cause. */
internal fun QueryDiscoveryObservation.projectDiscoveryObservation(): QueryDiscoveryObservationDocument? {
    return QueryDiscoveryObservationDocument(
        universeDocument() ?: return null,
        if (target is SymbolDiscoveryTarget.All) QueryDiscoveryOrderDocument.KOTLIN_FILE_SOURCE_V2
        else QueryDiscoveryOrderDocument.NAMED_CANDIDATE_V1,
        progress.document() ?: return null,
        BoundedProtocolList.create(
                observedStops.map { QueryDiscoveryStopDocument.valueOf(it.name) }.sortedBy { it.ordinal }
            )
            .valueOrNull() ?: return null,
        examinedWorkUnits.value.countDocument(),
        measurements.inventoryFiles.value.countDocument(),
        measurements.reacquiredFiles.value.countDocument(),
        measurements.examinedLeaves.value.countDocument(),
        timings.inventory.value.nanosecondsDocument(),
        timings.declarationScan.value.nanosecondsDocument(),
        timings.projection.value.nanosecondsDocument(),
    )
}

private fun QueryDiscoveryObservation.universeDocument(): QueryDiscoveryUniverseDocument? {
    val kinds = constraints.declarationKinds?.values ?: target.defaultDeclarationKinds() ?: return null
    val sourceSets = constraints.sourceSets.document() ?: return null
    val directory = constraints.directory?.let { it.document() ?: return null }
    val packageName = constraints.packageName?.let { it.document() ?: return null }
    return QueryDiscoveryUniverseDocument(
        when (declarationLanguage) {
            QueryDeclarationLanguage.KOTLIN -> QueryDiscoveryDeclarationLanguageDocument.KOTLIN
        },
        target.matchDocument() ?: return null,
        BoundedProtocolList.create(kinds.map { it.document() }.sortedBy { it.ordinal }).valueOrNull() ?: return null,
        scope.sourceKinds.document(),
        scope.generatedSources.document(),
        ((scope as? SymbolSearchScope.Workspace)?.libraries ?: SymbolLibraryPolicy.EXCLUDE).document(),
        sourceSets,
        directory,
        packageName,
    )
}

private fun SymbolDiscoveryTarget.defaultDeclarationKinds(): Set<CompilerSymbolKind>? {
    val kind =
        when (this) {
            is SymbolDiscoveryTarget.All -> this.kind
            is SymbolDiscoveryTarget.Name -> this.kind
            is SymbolDiscoveryTarget.TextDeclarations ->
                return setOf(
                    CompilerSymbolKind.CLASSLIKE,
                    CompilerSymbolKind.FUNCTION,
                    CompilerSymbolKind.PROPERTY,
                    CompilerSymbolKind.TYPE_ALIAS,
                )
            is SymbolDiscoveryTarget.Location,
            is SymbolDiscoveryTarget.Text -> return null
        }
    return when (kind) {
        SymbolNameDiscoveryKind.CLASS -> setOf(CompilerSymbolKind.CLASSLIKE)
        SymbolNameDiscoveryKind.SYMBOL ->
            setOf(
                CompilerSymbolKind.CLASSLIKE,
                CompilerSymbolKind.FUNCTION,
                CompilerSymbolKind.PROPERTY,
                CompilerSymbolKind.TYPE_ALIAS,
            )
        SymbolNameDiscoveryKind.FILE -> null
    }
}

private fun SymbolDiscoveryTarget.matchDocument(): QueryMatchDocument? {
    return when (this) {
        is SymbolDiscoveryTarget.All -> QueryMatchDocument.All
        is SymbolDiscoveryTarget.Name ->
            QueryMatchDocument.Name(
                ProtocolText.parse(pattern.value).valueOrNull() ?: return null,
                when (match) {
                    SymbolDiscoveryMatch.FUZZY -> SymbolDiscoveryMatchDocument.FUZZY
                    SymbolDiscoveryMatch.EXACT_NAME -> SymbolDiscoveryMatchDocument.EXACT_NAME
                },
            )
        is SymbolDiscoveryTarget.TextDeclarations ->
            QueryMatchDocument.TextWord(ProtocolText.parse(word.value).valueOrNull() ?: return null)
        is SymbolDiscoveryTarget.Location,
        is SymbolDiscoveryTarget.Text -> null
    }
}

internal fun SymbolDiscoverySourceSets.document(): QueryDiscoverySourceSetsDocument? {
    return when (this) {
        SymbolDiscoverySourceSets.All -> QueryDiscoverySourceSetsDocument.All
        is SymbolDiscoverySourceSets.Exact ->
            QueryDiscoverySourceSetsDocument.Exact(
                BoundedProtocolList.create(
                        values.map { ProtocolText.parse(it.value).valueOrNull() ?: return null }.sortedBy { it.value }
                    )
                    .valueOrNull() ?: return null
            )
    }
}

internal fun SymbolDiscoveryDirectoryConstraint.document(): QueryDirectoryScopeDocument? {
    return QueryDirectoryScopeDocument(
        ProtocolText.parse(directory.value).valueOrNull() ?: return null,
        containment.document(),
    )
}

internal fun SymbolDiscoveryPackageConstraint.document(): QueryPackageScopeDocument? {
    return QueryPackageScopeDocument(
        ProtocolText.parse(packageName.value).valueOrNull() ?: return null,
        containment.document(),
    )
}

private fun SymbolDiscoveryContainment.document(): QueryContainmentDocument =
    when (this) {
        SymbolDiscoveryContainment.DIRECT -> QueryContainmentDocument.DIRECT
        SymbolDiscoveryContainment.DESCENDANTS -> QueryContainmentDocument.DESCENDANTS
    }

internal fun CompilerSymbolKind.document(): QueryDeclarationKindDocument =
    when (this) {
        CompilerSymbolKind.CLASSLIKE -> QueryDeclarationKindDocument.CLASS
        CompilerSymbolKind.CONSTRUCTOR -> QueryDeclarationKindDocument.CONSTRUCTOR
        CompilerSymbolKind.FUNCTION -> QueryDeclarationKindDocument.FUNCTION
        CompilerSymbolKind.PROPERTY -> QueryDeclarationKindDocument.PROPERTY
        CompilerSymbolKind.TYPE_ALIAS -> QueryDeclarationKindDocument.TYPE_ALIAS
    }

internal fun SymbolSourceKindPolicy.document(): QueryDiscoverySourcePolicyDocument =
    when (this) {
        SymbolSourceKindPolicy.PRODUCTION_ONLY -> QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY
        SymbolSourceKindPolicy.TEST_ONLY -> QueryDiscoverySourcePolicyDocument.TEST_ONLY
        SymbolSourceKindPolicy.PRODUCTION_AND_TEST -> QueryDiscoverySourcePolicyDocument.PRODUCTION_AND_TEST
    }

internal fun SymbolGeneratedSourcePolicy.document(): QueryDiscoveryInclusionPolicyDocument =
    when (this) {
        SymbolGeneratedSourcePolicy.EXCLUDE -> QueryDiscoveryInclusionPolicyDocument.EXCLUDE
        SymbolGeneratedSourcePolicy.INCLUDE -> QueryDiscoveryInclusionPolicyDocument.INCLUDE
    }

internal fun SymbolLibraryPolicy.document(): QueryDiscoveryInclusionPolicyDocument =
    when (this) {
        SymbolLibraryPolicy.EXCLUDE -> QueryDiscoveryInclusionPolicyDocument.EXCLUDE
        SymbolLibraryPolicy.INCLUDE -> QueryDiscoveryInclusionPolicyDocument.INCLUDE
    }

private fun QueryDiscoveryProgress.document(): QueryDiscoveryProgressDocument? {
    return when (this) {
        QueryDiscoveryProgress.Exhausted -> QueryDiscoveryProgressDocument.Exhausted
        is QueryDiscoveryProgress.Blocked ->
            QueryDiscoveryProgressDocument.Blocked(QueryDiscoveryBlockDocument.valueOf(cause.name))
        is QueryDiscoveryProgress.Resumable ->
            QueryDiscoveryProgressDocument.Resumable(
                completedFiles.value.countDocument(),
                ProtocolOffset.parse(nextOffset.value).valueOrNull() ?: return null,
                discoveredFiles.value.countDocument(),
                pendingPartitions.value.countDocument(),
                retainedBytes.bytesDocument(),
            )
    }
}

private fun Long.countDocument(): QueryDiscoveryCountDocument = QueryDiscoveryCountDocument.parse(this).proved()

private fun Long.nanosecondsDocument(): QueryDiscoveryNanosecondsDocument =
    QueryDiscoveryNanosecondsDocument.parse(this).proved()

private fun Long.bytesDocument(): QueryDiscoveryBytesDocument = QueryDiscoveryBytesDocument.parse(this).proved()

private fun <Value, Failure> Refinement<Value, Failure>.proved(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("An established discovery measure became invalid: $failure")
    }

private fun <Value, Failure> Refinement<Value, Failure>.valueOrNull(): Value? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }
