package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationQualifiedIdentity
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.IdeReadContentView
import io.github.amichne.kast.workspace.contract.IdeReadEpochRevision
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.LiveSemanticReadReference
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class LiveReplaceBodyPlanDecodeFailure {
    MALFORMED,
    VERSION_UNSUPPORTED,
    TARGET_MISMATCH,
    PREIMAGE_MISMATCH,
    IDENTITY_MISMATCH,
}

@Serializable
internal data class LiveReplaceBodyPlanDocument(
    val format: String,
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
    val preimage: String,
    val bodyStart: Int,
    val bodyEnd: Int,
    val originalBody: String,
    val replacementBody: String,
    val expectedPostimage: String,
)

/** Canonical, versioned historical body plan. Decoding never recreates live authority. */
object LiveReplaceBodyPlanCodec {
    const val VERSION = 1
    private const val FORMAT = "LIVE_REPLACE_BODY"
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        prettyPrint = false
    }

    fun encode(plan: LiveReplaceBodyChangePlan): String {
        val observed = plan.basis.observation
        val qualified =
            when (val identity = plan.target.evidence.qualifiedIdentity) {
                is ExactDeclarationQualifiedIdentity.Available -> identity.value
                ExactDeclarationQualifiedIdentity.Unavailable -> null
            }
        return json.encodeToString(
            LiveReplaceBodyPlanDocument(
                format = FORMAT,
                schemaVersion = VERSION,
                planId = plan.planId.value,
                workspaceRoot = observed.reference.workspaceRoot.value,
                owner = observed.reference.host.value.toString(),
                epoch = observed.reference.epoch.value,
                referenceVersion = observed.reference.version,
                contentView = observed.reference.contentView.name,
                model = observed.model.sourceRoots.map { it.document() },
                target = plan.target.document(qualified),
                scope = SymbolSearchScope.snapshot(plan.target.scope).document(),
                constraints = plan.target.constraints.document(),
                sourceContent = plan.content.value,
                preimage = plan.preservation.preimage,
                bodyStart = plan.preservation.bodyRange.startInclusive,
                bodyEnd = plan.preservation.bodyRange.endExclusive,
                originalBody = plan.preservation.originalBody.value,
                replacementBody = plan.body.value,
                expectedPostimage = plan.expectedPostimage.value,
            )
        )
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Each canonical field is admitted before identity reissue.
    fun decode(encoded: String): Refinement<LiveReplaceBodyChangePlan, LiveReplaceBodyPlanDecodeFailure> {
        val version =
            try {
                json.parseToJsonElement(encoded).jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull
            } catch (_: IllegalArgumentException) {
                return rejected()
            }
        if (version != VERSION) return rejected(LiveReplaceBodyPlanDecodeFailure.VERSION_UNSUPPORTED)
        val document =
            try {
                json.decodeFromString<LiveReplaceBodyPlanDocument>(encoded)
            } catch (_: IllegalArgumentException) {
                return rejected()
            }
        if (
            document.format != FORMAT ||
                document.referenceVersion != LiveSemanticReadReference.VERSION ||
                json.encodeToString(document) != encoded
        )
            return rejected()
        val basis = restoreBasis(document) ?: return rejected(LiveReplaceBodyPlanDecodeFailure.TARGET_MISMATCH)
        val target =
            when (val restored = restoreLivePlanTarget(basis, document.target, document.scope, document.constraints)) {
                is Refinement.Refined -> restored.value
                is Refinement.Rejected -> return rejected(LiveReplaceBodyPlanDecodeFailure.TARGET_MISMATCH)
            }
        val content = WorkspaceSourceContentHash.parse(document.sourceContent).valueOrNull() ?: return rejected()
        val range =
            ExactDeclarationTextRange.parse(document.bodyStart, document.bodyEnd).valueOrNull() ?: return rejected()
        val original = ExistingBodySourceText.fromCompiler(document.originalBody).valueOrNull() ?: return rejected()
        val replacement = ReplaceBodySourceText.parse(document.replacementBody).valueOrNull() ?: return rejected()
        val preservation =
            ReplaceBodyPreservation.capture(
                    document.preimage,
                    target.range,
                    range,
                    original,
                    replacement,
                    content,
                )
                .valueOrNull() ?: return rejected(LiveReplaceBodyPlanDecodeFailure.PREIMAGE_MISMATCH)
        if (preservation.expectedPostimage.value != document.expectedPostimage)
            return rejected(LiveReplaceBodyPlanDecodeFailure.PREIMAGE_MISMATCH)
        val input =
            AdmittedLiveReplaceBodyPlanInput.restore(basis, target, content, preservation).valueOrNull()
                ?: return rejected(LiveReplaceBodyPlanDecodeFailure.TARGET_MISMATCH)
        val plan = LiveReplaceBodyChangePlan.issue(input)
        if (plan.planId.value != document.planId || encode(plan) != encoded)
            return rejected(LiveReplaceBodyPlanDecodeFailure.IDENTITY_MISMATCH)
        return Refinement.Refined(plan)
    }

    @Suppress("LongMethod") // Restore the complete read basis before treating a persisted plan as live.
    private fun restoreBasis(document: LiveReplaceBodyPlanDocument): LiveChangeBasis? {
        val rootPath =
            try {
                Path.of(document.workspaceRoot)
            } catch (_: IllegalArgumentException) {
                return null
            }
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(rootPath).valueOrNull() ?: return null
        val owner =
            try {
                UUID.fromString(document.owner)
            } catch (_: IllegalArgumentException) {
                return null
            }
        val epoch = IdeReadEpochRevision.parse(document.epoch).valueOrNull() ?: return null
        val view = IdeReadContentView.entries.singleOrNull { it.name == document.contentView } ?: return null
        val boundaries =
            document.model.map { source ->
                val buildPath =
                    try {
                        Path.of(source.buildRoot)
                    } catch (_: IllegalArgumentException) {
                        return null
                    }
                val sourcePath =
                    try {
                        Path.of(source.sourceRoot)
                    } catch (_: IllegalArgumentException) {
                        return null
                    }
                WorkspaceSourceRootBoundary(
                    ideaModuleName = source.module,
                    linkedBuildRoot = rootPath.resolve(buildPath).normalize(),
                    gradleProjectPath = source.projectPath,
                    sourceSetName = source.sourceSet,
                    sourceRoot = sourcePath,
                    sourceKind =
                        WorkspaceSourceRootKind.entries.singleOrNull { it.name == source.sourceKind } ?: return null,
                    provenance =
                        WorkspaceSourceRootProvenance.entries.singleOrNull { it.name == source.provenance }
                            ?: return null,
                )
            }
        val model =
            when (
                val compiled = WorkspaceSearchScopeModel.compile(root, ImportedWorkspaceModelState.COMPLETE, boundaries)
            ) {
                is WorkspaceSearchScopeModelCompilation.Compiled -> compiled.model
                is WorkspaceSearchScopeModelCompilation.Rejected -> return null
            }
        val reference =
            LiveSemanticReadReference(
                workspaceRoot = root,
                host = IdeReadHostLifetime.fromBoundary(owner),
                epoch = epoch,
                contentView = view,
                version = document.referenceVersion,
            )
        return LiveChangeBasis.observe(reference, model).valueOrNull()
    }

    private fun rejected(failure: LiveReplaceBodyPlanDecodeFailure = LiveReplaceBodyPlanDecodeFailure.MALFORMED) =
        Refinement.Rejected(failure)
}

private fun <T, F> Refinement<T, F>.valueOrNull(): T? =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> null
    }
