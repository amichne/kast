package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeKind
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeSnapshot
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSelectorFingerprint
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.GradleSourceRootEvidence
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class HostedAddDeclarationPlanDecodeFailure {
    MALFORMED_OR_TAMPERED,
}

@Serializable
private data class HostedAddDeclarationPlanDocument(
    val schemaVersion: Int,
    val planId: String,
    val workspaceRoot: String,
    val generation: Long,
    val workspaceState: String,
    val sourcePath: String,
    val sourceContent: String,
    val sourceRootModule: String,
    val sourceRootBuildRoot: String,
    val sourceRootProjectPath: String,
    val sourceRootSourceSet: String,
    val sourceRootLocation: String,
    val scopeKind: String,
    val scopePrimary: String?,
    val scopeSecondary: String?,
    val scopeSourceKinds: String,
    val scopeGeneratedSources: String,
    val scopeLibraries: String?,
    val selectorStart: Int,
    val selectorEnd: Int,
    val selectorName: String,
    val selectorQualifiedIdentity: String?,
    val selectorKind: String,
    val selectorCompilerIdentity: String,
    val selectorFingerprint: String,
    val declaration: String,
    val expectedPackage: String,
    val expectedName: String,
    val expectedKind: String,
    val evidence: DurableAddDeclarationPlanningEvidence,
)

/** Canonical durable codec for the sole hosted change.plan variant. */
object HostedAddDeclarationPlanCodec {
    private const val LEGACY_SCHEMA_VERSION = 1
    private const val SCHEMA_VERSION = 2

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        prettyPrint = false
    }

    fun encode(plan: AddDeclarationChangePlan): String = json.encodeToString(
        HostedAddDeclarationPlanDocument.serializer(),
        plan.document(),
    )

    fun decode(
        encoded: String,
    ): Refinement<AddDeclarationChangePlan, HostedAddDeclarationPlanDecodeFailure> {
        val document = runCatching {
            json.decodeFromString(HostedAddDeclarationPlanDocument.serializer(), encoded)
        }.getOrNull() ?: return rejected()
        if (
            document.schemaVersion !in setOf(LEGACY_SCHEMA_VERSION, SCHEMA_VERSION) ||
            json.encodeToString(HostedAddDeclarationPlanDocument.serializer(), document) != encoded
        ) {
            return rejected()
        }
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(document.workspaceRoot.pathOrNull() ?: return rejected())
            .valueOrNull() ?: return rejected()
        val generation = EvidenceGeneration.parse(document.generation).valueOrNull() ?: return rejected()
        val workspaceState = WorkspaceStateIdentity.parse(document.workspaceState).valueOrNull()
            ?: return rejected()
        val content = WorkspaceSourceContentHash.parse(document.sourceContent).valueOrNull()
            ?: return rejected()
        val sourceRoot = SourceRoot.admit(
            GradleSourceRootEvidence(
                document.sourceRootModule,
                document.sourceRootBuildRoot,
                document.sourceRootProjectPath,
                document.sourceRootSourceSet,
                document.sourceRootLocation,
                SourceRootProvenance.Authored,
            ),
        ).valueOrNull() ?: return rejected()
        val sourcePath = document.sourcePath.pathOrNull() ?: return rejected()
        val file = when (val admitted = SymbolDiscoveryFileIdentity.fromBoundary(
            root,
            sourcePath,
            sourcePath.toUri().toString(),
        )) {
            is Refinement.Refined -> admitted.value as? SymbolDiscoveryFileIdentity.Workspace
                ?: return rejected()
            is Refinement.Rejected -> return rejected()
        }
        val scopeSnapshot = SymbolSearchScopeSnapshot(
            document.scopeKind.enumOrNull<SymbolSearchScopeKind>() ?: return rejected(),
            document.scopePrimary,
            document.scopeSecondary,
            document.scopeSourceKinds.enumOrNull<SymbolSourceKindPolicy>() ?: return rejected(),
            document.scopeGeneratedSources.enumOrNull<SymbolGeneratedSourcePolicy>()
                ?: return rejected(),
            document.scopeLibraries?.enumOrNull<SymbolLibraryPolicy>(),
        )
        val scope = SymbolSearchScope.restore(root, sourceRoot, scopeSnapshot).valueOrNull()
            ?: return rejected()
        val compilerIdentity = CompilerSymbolIdentity.parse(document.selectorCompilerIdentity)
            .valueOrNull() ?: return rejected()
        val selectorEvidence = CompilerGroundedSymbolEvidence.fromBoundary(
            file,
            document.selectorStart,
            document.selectorEnd,
            document.selectorName,
            document.selectorQualifiedIdentity,
            document.selectorKind.enumOrNull<CompilerSymbolKind>() ?: return rejected(),
            compilerIdentity,
        ).valueOrNull() ?: return rejected()
        val selectorFingerprint = SymbolSelectorFingerprint.parse(document.selectorFingerprint)
            .valueOrNull() ?: return rejected()
        val lease = SemanticReadLease(root, generation)
        val selector = SymbolSelector.restore(lease, scope, selectorEvidence, selectorFingerprint)
            .valueOrNull() ?: return rejected()
        val target = EditableMutationTarget.restore(
            lease,
            workspaceState,
            file,
            content,
            sourceRoot,
            selector,
        ).valueOrNull() ?: return rejected()
        val declaration = AddDeclarationSourceText.parse(document.declaration).valueOrNull()
            ?: return rejected()
        val expectedDelta = ExpectedAddDeclarationDelta.admit(
            document.expectedPackage,
            document.expectedName,
            document.expectedKind.enumOrNull<AddDeclarationKind>() ?: return rejected(),
        ).valueOrNull() ?: return rejected()
        val evidence = DurableAddDeclarationPlanningEvidence.restore(
            document.evidence.relations,
            document.evidence.traversals,
            document.evidence.diagnostics,
            document.evidence.fingerprint,
            when (document.schemaVersion) {
                LEGACY_SCHEMA_VERSION -> StableRelationEvidenceSemantics.GENERATION_BOUND_V1
                SCHEMA_VERSION -> StableRelationEvidenceSemantics.SEMANTIC_V2
                else -> return rejected()
            },
        ).valueOrNull() ?: return rejected()
        val planId = ChangePlanId.parse(document.planId).valueOrNull() ?: return rejected()
        return AddDeclarationChangePlan.restore(
            planId,
            target,
            declaration,
            evidence,
            expectedDelta,
        )
    }

    private fun AddDeclarationChangePlan.document(): HostedAddDeclarationPlanDocument {
        val scope = SymbolSearchScope.snapshot(target.selector.scope)
        val declaration = when (val edit = plannedEdits.single()) {
            is AddDeclarationPlannedEdit.InsertAfterDeclaration -> edit.declaration
            is AddDeclarationPlannedEdit.InsertIntoClassBody -> edit.declaration
        }
        val qualified = when (val identity = target.selector.qualifiedIdentity) {
            is io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity.Available ->
                identity.value
            io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity.Unavailable -> null
        }
        return HostedAddDeclarationPlanDocument(
            when (evidence.relationDigestSemantics) {
                StableRelationEvidenceSemantics.GENERATION_BOUND_V1 -> LEGACY_SCHEMA_VERSION
                StableRelationEvidenceSemantics.SEMANTIC_V2 -> SCHEMA_VERSION
            },
            planId.value,
            priorLease.workspaceRoot.value,
            priorLease.generation.value,
            workspaceState.value,
            target.file.path.value,
            target.content.value,
            target.owner.module.value,
            target.owner.project.buildRoot.value,
            target.owner.project.projectPath.value,
            target.owner.sourceSet.value,
            target.sourceRoot.location.value,
            scope.kind.name,
            scope.primary,
            scope.secondary,
            scope.sourceKinds.name,
            scope.generatedSources.name,
            scope.libraries?.name,
            target.range.startInclusive,
            target.range.endExclusive,
            target.selector.name.value,
            qualified,
            target.selector.kind.name,
            target.selector.compilerIdentity.value,
            target.selector.fingerprint.value,
            declaration.value,
            expectedSemanticDelta.packageName,
            expectedSemanticDelta.declarationName,
            expectedSemanticDelta.declarationKind.name,
            evidence,
        )
    }
}

private fun rejected(): Refinement.Rejected<HostedAddDeclarationPlanDecodeFailure> =
    Refinement.Rejected(HostedAddDeclarationPlanDecodeFailure.MALFORMED_OR_TAMPERED)

private fun String.pathOrNull(): Path? = runCatching { Path.of(this) }.getOrNull()

private inline fun <reified Value : Enum<Value>> String.enumOrNull(): Value? =
    enumValues<Value>().singleOrNull { it.name == this }

private fun <Value, Failure> Refinement<Value, Failure>.valueOrNull(): Value? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}
