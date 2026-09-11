package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Versioned historical plan representation. Decoding never creates read or source-write authority. */
object LiveAddDeclarationPlanCodec {
    const val VERSION = 1
    private const val FORMAT = "LIVE_ADD_DECLARATION"

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        prettyPrint = false
    }

    fun encode(plan: LiveAddDeclarationChangePlan): String = json.encodeToString(plan.document())

    fun decode(encoded: String): Refinement<LiveAddDeclarationChangePlan, LiveAddDeclarationPlanDecodeFailure> {
        val document =
            decodeDocument(encoded).required {
                return it
            }
        val basis =
            document.restoreBasis().required {
                return it
            }
        val input =
            document.restoreInput(basis).required {
                return it
            }
        val planId =
            AddDeclarationPlanId.parse(document.planId).required {
                return rejected()
            }
        return when (val restored = LiveAddDeclarationChangePlan.restore(planId, input)) {
            is Refinement.Rejected -> restored
            is Refinement.Refined -> if (encode(restored.value) == encoded) restored else rejected()
        }
    }

    private fun decodeDocument(
        encoded: String
    ): Refinement<LiveAddDeclarationPlanDocument, LiveAddDeclarationPlanDecodeFailure> {
        val version =
            try {
                json.parseToJsonElement(encoded).jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull
            } catch (_: IllegalArgumentException) {
                return rejected()
            } ?: return rejected()
        if (version != VERSION) return rejected(LiveAddDeclarationPlanDecodeFailure.VERSION_UNSUPPORTED)
        val document =
            try {
                json.decodeFromString<LiveAddDeclarationPlanDocument>(encoded)
            } catch (_: IllegalArgumentException) {
                return rejected()
            }
        if (document.formatIdentity != FORMAT) return rejected()
        if (document.referenceVersion != LiveSemanticReadReference.VERSION) {
            return rejected(LiveAddDeclarationPlanDecodeFailure.VERSION_UNSUPPORTED)
        }
        if (json.encodeToString(document) != encoded) return rejected()
        val required = LiveAddDeclarationVerificationContract.required
        if (
            document.semanticObligations != required.semanticObligations.map { it.name } ||
                document.liveObligations != required.liveObligations.map { it.name }
        ) {
            return rejected(LiveAddDeclarationPlanDecodeFailure.EVIDENCE_INCOMPLETE)
        }
        return Refinement.Refined(document)
    }

    private fun LiveAddDeclarationPlanDocument.restoreBasis():
        Refinement<LiveChangeBasis, LiveAddDeclarationPlanDecodeFailure> {
        val rootPath = workspaceRoot.pathOrNull() ?: return rejected()
        val root =
            CanonicalWorkspaceRoot.fromCanonicalPath(rootPath).required {
                return rejected()
            }
        val owner =
            try {
                UUID.fromString(owner)
            } catch (_: IllegalArgumentException) {
                return rejected()
            }
        val epoch =
            IdeReadEpochRevision.parse(epoch).required {
                return rejected()
            }
        val view = contentView.enumOrNull<IdeReadContentView>() ?: return rejected()
        val boundaries = model.map { source ->
            source.restore(rootPath).required {
                return it
            }
        }
        val model =
            when (
                val compiled = WorkspaceSearchScopeModel.compile(root, ImportedWorkspaceModelState.COMPLETE, boundaries)
            ) {
                is WorkspaceSearchScopeModelCompilation.Compiled -> compiled.model
                is WorkspaceSearchScopeModelCompilation.Rejected ->
                    return rejected(LiveAddDeclarationPlanDecodeFailure.TARGET_MISMATCH)
            }
        val reference =
            LiveSemanticReadReference(
                workspaceRoot = root,
                host = IdeReadHostLifetime.fromBoundary(owner),
                epoch = epoch,
                contentView = view,
                version = referenceVersion,
            )
        return Refinement.Refined(
            LiveChangeBasis.observe(reference, model).required {
                return rejected()
            }
        )
    }

    private fun LivePlanSourceRootDocument.restore(
        root: Path
    ): Refinement<WorkspaceSourceRootBoundary, LiveAddDeclarationPlanDecodeFailure> {
        val buildRoot = buildRoot.pathOrNull() ?: return rejected()
        return Refinement.Refined(
            WorkspaceSourceRootBoundary(
                ideaModuleName = module,
                linkedBuildRoot = root.resolve(buildRoot).normalize(),
                gradleProjectPath = projectPath,
                sourceSetName = sourceSet,
                sourceRoot = sourceRoot.pathOrNull() ?: return rejected(),
                sourceKind = sourceKind.enumOrNull<WorkspaceSourceRootKind>() ?: return rejected(),
                provenance = provenance.enumOrNull<WorkspaceSourceRootProvenance>() ?: return rejected(),
            )
        )
    }

    private fun LiveAddDeclarationPlanDocument.restoreInput(
        basis: LiveChangeBasis
    ): Refinement<AdmittedLiveAddDeclarationPlanInput, LiveAddDeclarationPlanDecodeFailure> {
        val target =
            restoreTarget(basis).required {
                return it
            }
        val content =
            WorkspaceSourceContentHash.parse(sourceContent).required {
                return rejected()
            }
        val declaration =
            AddDeclarationSourceText.parse(declaration).required {
                return rejected()
            }
        val delta =
            ExpectedAddDeclarationDelta.admit(
                    expectedPackage,
                    expectedName,
                    expectedKind.enumOrNull<AddDeclarationKind>() ?: return rejected(),
                )
                .required {
                    return rejected()
                }
        val evidence =
            restoreEvidence().required {
                return it
            }
        val scope =
            LiveVerificationScopeCodec.restore(verificationScope, basis.reference.workspaceRoot, evidence).required {
                return it
            }
        return AdmittedLiveAddDeclarationPlanInput.restore(
            planningTarget = LivePlanningTarget(basis, target, content),
            declaration = LivePlannedDeclaration(declaration, delta),
            evidence = LivePlanningEvidence(evidence, scope),
        )
    }

    private fun LiveAddDeclarationPlanDocument.restoreEvidence():
        Refinement<DurableAddDeclarationPlanningEvidence, LiveAddDeclarationPlanDecodeFailure> {
        if (relationEvidenceSemantics != StableRelationEvidenceSemantics.SEMANTIC_V2.name) {
            return rejected(LiveAddDeclarationPlanDecodeFailure.EVIDENCE_INCOMPLETE)
        }
        return Refinement.Refined(
            DurableAddDeclarationPlanningEvidence.restore(
                    relations = evidence.relations,
                    traversals = evidence.traversals,
                    diagnostics = evidence.diagnostics,
                    fingerprint = evidence.fingerprint,
                    relationDigestSemantics = StableRelationEvidenceSemantics.SEMANTIC_V2,
                )
                .required {
                    return rejected(LiveAddDeclarationPlanDecodeFailure.EVIDENCE_INCOMPLETE)
                }
        )
    }
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

private inline fun <T, F> Refinement<T, F>.required(onFailure: (Refinement.Rejected<F>) -> Nothing): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> onFailure(this)
    }
