package io.github.amichne.kast.change.contract

import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class LiveAddDeclarationPlanDocument(
    @SerialName("format") val formatIdentity: String,
    val schemaVersion: Int,
    val planId: String,
    val workspaceRoot: String,
    val owner: String,
    val epoch: Long,
    val referenceVersion: Int,
    val contentView: String,
    val model: List<LivePlanSourceRootDocument>,
    val target: LivePlanTargetDocument,
    val scope: LivePlanScopeDocument,
    val constraints: LivePlanConstraintsDocument,
    val sourceContent: String,
    val declaration: String,
    val expectedPackage: String,
    val expectedName: String,
    val expectedKind: String,
    val relationEvidenceSemantics: String,
    val evidence: DurableAddDeclarationPlanningEvidence,
    val verificationScope: LiveVerificationScopeDocument,
    val semanticObligations: List<String>,
    val liveObligations: List<String>,
)

@Serializable
internal data class LivePlanSourceRootDocument(
    val module: String,
    val buildRoot: String,
    val projectPath: String,
    val sourceSet: String,
    val sourceRoot: String,
    val sourceKind: String,
    val provenance: String,
)

@Serializable
internal data class LivePlanTargetDocument(
    val sourcePath: String,
    val start: Int,
    val end: Int,
    val name: String,
    val qualifiedIdentity: String?,
    val kind: String,
    val signature: String,
    val compilerIdentity: String,
    val fingerprint: String,
)

@Serializable
internal data class LivePlanScopeDocument(
    val kind: String,
    val primary: String?,
    val secondary: String?,
    val sourceKinds: String,
    val generatedSources: String,
    val libraries: String?,
)

@Serializable internal data class LivePlanContainmentDocument(val value: String, val containment: String)

@Serializable
internal data class LivePlanConstraintsDocument(
    val directory: LivePlanContainmentDocument?,
    val packageName: LivePlanContainmentDocument?,
    val declarationKinds: List<String>?,
    val sourceSets: List<String>?,
)

internal fun LiveAddDeclarationChangePlan.document(): LiveAddDeclarationPlanDocument {
    val observed = basis.observation
    val scope = SymbolSearchScope.snapshot(target.scope)
    val qualified =
        when (val identity = target.evidence.qualifiedIdentity) {
            is ExactDeclarationQualifiedIdentity.Available -> identity.value
            ExactDeclarationQualifiedIdentity.Unavailable -> null
        }
    return LiveAddDeclarationPlanDocument(
        formatIdentity = "LIVE_ADD_DECLARATION",
        schemaVersion = LiveAddDeclarationPlanCodec.VERSION,
        planId = planId.value,
        workspaceRoot = observed.reference.workspaceRoot.value,
        owner = observed.reference.host.value.toString(),
        epoch = observed.reference.epoch.value,
        referenceVersion = observed.reference.version,
        contentView = observed.reference.contentView.name,
        model = observed.model.sourceRoots.map { root -> root.document() },
        target = target.document(qualified),
        scope = scope.document(),
        constraints = target.constraints.document(),
        sourceContent = content.value,
        declaration = declaration.value,
        expectedPackage = expectedSemanticDelta.packageName,
        expectedName = expectedSemanticDelta.declarationName,
        expectedKind = expectedSemanticDelta.declarationKind.name,
        relationEvidenceSemantics = evidence.relationDigestSemantics.name,
        evidence = evidence,
        verificationScope = LiveVerificationScopeCodec.document(verificationScope),
        semanticObligations = requiredVerification.semanticObligations.map { it.name },
        liveObligations = requiredVerification.liveObligations.map { it.name },
    )
}

private fun SymbolDiscoveryConstraints.document() =
    LivePlanConstraintsDocument(
        directory = directory?.let { LivePlanContainmentDocument(it.directory.value, it.containment.name) },
        packageName = packageName?.let { LivePlanContainmentDocument(it.packageName.value, it.containment.name) },
        declarationKinds = declarationKinds?.values?.map { it.name }?.sorted(),
        sourceSets =
            when (val selected = sourceSets) {
                SymbolDiscoverySourceSets.All -> null
                is SymbolDiscoverySourceSets.Exact -> selected.values.map { it.value }.sorted()
            },
    )

private fun io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot.document() =
    LivePlanSourceRootDocument(
        module = module.value,
        buildRoot = project.buildRoot.value,
        projectPath = project.projectPath.value,
        sourceSet = sourceSet.value,
        sourceRoot = sourceRoot.value,
        sourceKind = sourceKind.name,
        provenance = provenance.name,
    )

private fun PlannedDeclarationIdentity.document(qualified: String?) =
    LivePlanTargetDocument(
        sourcePath = file.path.value,
        start = range.startInclusive,
        end = range.endExclusive,
        name = evidence.name.value,
        qualifiedIdentity = qualified,
        kind = kind.name,
        signature = evidence.signature.canonicalEncoding().value,
        compilerIdentity = evidence.compilerIdentity.value,
        fingerprint = fingerprint.value,
    )

private fun SymbolSearchScopeSnapshot.document() =
    LivePlanScopeDocument(
        kind = kind.name,
        primary = primary,
        secondary = secondary,
        sourceKinds = sourceKinds.name,
        generatedSources = generatedSources.name,
        libraries = libraries?.name,
    )
