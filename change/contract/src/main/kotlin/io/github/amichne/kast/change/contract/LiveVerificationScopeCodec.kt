package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalExtent
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.workspace.contract.WorkspaceSourceSetName
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Durable request metadata retains original limits; decoding grants no read authority. */
internal object LiveVerificationScopeCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    fun canonicalIdentity(scope: LiveAddDeclarationVerificationScope): String =
        when (val selected = representation(scope)) {
            is LiveVerificationScopeRepresentation.LegacyDepth ->
                json.encodeToString(LegacyLiveVerificationScopeDocument.serializer(), selected.document)
            is LiveVerificationScopeRepresentation.Extent ->
                json.encodeToString(LiveVerificationScopeDocument.serializer(), selected.document)
        }

    fun representation(scope: LiveAddDeclarationVerificationScope): LiveVerificationScopeRepresentation {
        val current = document(scope)
        val legacy = mutableListOf<LegacyLiveTraversalReplayDocument>()
        for (traversal in current.traversals) {
            when (val extent = traversal.extent) {
                LiveTraversalExtentDocument.Exhaustive -> return LiveVerificationScopeRepresentation.Extent(current)
                is LiveTraversalExtentDocument.ThroughDepth ->
                    legacy +=
                        LegacyLiveTraversalReplayDocument(
                            traversal.meaning,
                            traversal.records,
                            traversal.returnedBytes,
                            traversal.workUnits,
                            traversal.elapsedTime,
                            extent.maximumDepth,
                            traversal.frontier,
                            traversal.oneHop,
                            traversal.expansion,
                        )
            }
        }
        return LiveVerificationScopeRepresentation.LegacyDepth(
            LegacyLiveVerificationScopeDocument(current.relations, legacy, current.diagnostics)
        )
    }

    fun document(scope: LiveAddDeclarationVerificationScope) =
        LiveVerificationScopeDocument(
            scope.relations.map {
                LiveRelationReplayDocument(it.meaning.documentName(), it.budget.document(), it.boundary.document())
            },
            scope.traversals.map { replay ->
                val budget = replay.budget
                LiveTraversalReplayDocument(
                    meaning = replay.meaning.documentName(),
                    records = budget.records.value,
                    returnedBytes = budget.returnedBytes.value,
                    workUnits = budget.workUnits.value,
                    elapsedTime = budget.elapsedTime.value,
                    extent =
                        when (val selected = budget.extent) {
                            TraversalExtent.Exhaustive -> LiveTraversalExtentDocument.Exhaustive
                            is TraversalExtent.ThroughDepth ->
                                LiveTraversalExtentDocument.ThroughDepth(selected.maximumDepth.value)
                        },
                    frontier = budget.frontier.value,
                    oneHop = budget.oneHop.document(),
                    expansion = replay.expansion.document(),
                )
            },
            scope.diagnostics.map { it.files.map { file -> file.value } },
        )

    fun restore(
        document: LiveVerificationScopeDocument,
        basis: LiveChangeBasis,
        evidence: DurableAddDeclarationPlanningEvidence,
    ): Refinement<LiveAddDeclarationVerificationScope, LiveAddDeclarationPlanDecodeFailure> {
        val relations =
            document.relations.map { replay ->
                val meaning =
                    replay.meaning.restoreMeaning().required {
                        return rejected()
                    }
                val budget =
                    replay.budget.restore().required {
                        return rejected()
                    }
                val boundary =
                    replay.boundary.restore(basis).required {
                        return rejected()
                    }
                LivePlannedRelationRead(meaning, budget, boundary)
            }
        val traversals =
            document.traversals.map { replay ->
                replay.restoreTraversal(basis).required {
                    return rejected()
                }
            }
        val diagnostics =
            document.diagnostics.map { paths ->
                val files = paths.map { raw ->
                    val path =
                        try {
                            Path.of(raw)
                        } catch (_: IllegalArgumentException) {
                            return rejected()
                        }
                    CanonicalWorkspaceFilePath.fromCanonicalPath(basis.reference.workspaceRoot, path).required {
                        return rejected()
                    }
                }
                LivePlannedDiagnosticScope.restore(files).required {
                    return rejected()
                }
            }
        return LiveAddDeclarationVerificationScope.restore(relations, traversals, diagnostics, evidence)
    }

    private fun LiveTraversalReplayDocument.restoreTraversal(
        basis: LiveChangeBasis
    ): Refinement<LivePlannedTraversal, LiveAddDeclarationPlanDecodeFailure> {
        val meaning =
            meaning.restoreMeaning().required {
                return rejected()
            }
        val budget =
            TraversalBudget(
                records =
                    ResultLimit.parse(records).required {
                        return rejected()
                    },
                returnedBytes =
                    TraversalByteLimit.parse(returnedBytes).required {
                        return rejected()
                    },
                workUnits =
                    WorkUnitLimit.parse(workUnits).required {
                        return rejected()
                    },
                elapsedTime =
                    ElapsedTimeLimitMillis.parse(elapsedTime).required {
                        return rejected()
                    },
                extent =
                    restoreExtent(extent).required {
                        return rejected()
                    },
                frontier =
                    TraversalFrontierLimit.parse(frontier).required {
                        return rejected()
                    },
                oneHop =
                    oneHop.restore().required {
                        return rejected()
                    },
            )
        return Refinement.Refined(
            LivePlannedTraversal(
                meaning,
                budget,
                expansion.restore(basis).required {
                    return rejected()
                },
            )
        )
    }

    private fun restoreExtent(
        document: LiveTraversalExtentDocument
    ): Refinement<TraversalExtent, LiveAddDeclarationPlanDecodeFailure> =
        when (document) {
            LiveTraversalExtentDocument.Exhaustive -> Refinement.Refined(TraversalExtent.Exhaustive)
            is LiveTraversalExtentDocument.ThroughDepth ->
                when (val depth = TraversalDepthLimit.parse(document.maximumDepth)) {
                    is Refinement.Refined -> Refinement.Refined(TraversalExtent.ThroughDepth(depth.value))
                    is Refinement.Rejected -> rejected()
                }
        }

    private fun RelationBudget.document() =
        LiveRelationBudgetDocument(
            records = resources.resultLimit.value,
            workUnits = resources.workUnitLimit.value,
            elapsedTime = resources.elapsedTimeLimit.value,
            returnedBytes = returnedBytes.value,
        )

    private fun LiveRelationBudgetDocument.restore(): Refinement<RelationBudget, LiveAddDeclarationPlanDecodeFailure> =
        Refinement.Refined(
            RelationBudget(
                ResourceBudget(
                    resultLimit =
                        ResultLimit.parse(records).required {
                            return rejected()
                        },
                    workUnitLimit =
                        WorkUnitLimit.parse(workUnits).required {
                            return rejected()
                        },
                    elapsedTimeLimit =
                        ElapsedTimeLimitMillis.parse(elapsedTime).required {
                            return rejected()
                        },
                ),
                RelationByteLimit.parse(returnedBytes).required {
                    return rejected()
                },
            )
        )

    private fun String.restoreMeaning(): Refinement<RelationMeaning, LiveAddDeclarationPlanDecodeFailure> =
        when (val meaning = AddDeclarationRelationMeaning.entries.singleOrNull { it.name == this }) {
            null -> rejected()
            else -> Refinement.Refined(meaning.domain())
        }

    private fun RelationMeaning.documentName(): String =
        when (this) {
            RelationMeaning.References -> AddDeclarationRelationMeaning.REFERENCES.name
            RelationMeaning.Callers -> AddDeclarationRelationMeaning.CALLERS.name
            RelationMeaning.Callees -> AddDeclarationRelationMeaning.CALLEES.name
            RelationMeaning.Implementations -> AddDeclarationRelationMeaning.IMPLEMENTATIONS.name
            RelationMeaning.Inheritors -> AddDeclarationRelationMeaning.INHERITORS.name
            RelationMeaning.Overrides -> AddDeclarationRelationMeaning.OVERRIDES.name
            RelationMeaning.TypeUses -> AddDeclarationRelationMeaning.TYPE_USES.name
        }
}

@Serializable
internal data class LiveVerificationScopeDocument(
    val relations: List<LiveRelationReplayDocument>,
    val traversals: List<LiveTraversalReplayDocument>,
    val diagnostics: List<List<String>>,
)

@Serializable
internal data class LiveRelationReplayDocument(
    val meaning: String,
    val budget: LiveRelationBudgetDocument,
    val boundary: LiveRelationBoundaryDocument,
)

@Serializable
internal data class LiveRelationBudgetDocument(
    val records: Int,
    val workUnits: Long,
    val elapsedTime: Long,
    val returnedBytes: Long,
)

@Serializable
internal data class LiveTraversalReplayDocument(
    val meaning: String,
    val records: Int,
    val returnedBytes: Long,
    val workUnits: Long,
    val elapsedTime: Long,
    val extent: LiveTraversalExtentDocument,
    val frontier: Int,
    val oneHop: LiveRelationBudgetDocument,
    val expansion: LiveRelationBoundaryDocument,
)

private fun rejected() = Refinement.Rejected(LiveAddDeclarationPlanDecodeFailure.EVIDENCE_INCOMPLETE)

private inline fun <T, F> Refinement<T, F>.required(onFailure: () -> Nothing): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> onFailure()
    }

/** Durable replay preserves expansion alternatives and case-owned restrictions. */
@Serializable
internal sealed interface LiveRelationBoundaryDocument {
    @Serializable @SerialName("RETAINED_SUBJECT") data object RetainedSubject : LiveRelationBoundaryDocument

    @Serializable @SerialName("WORKSPACE_EXPANSION") data object WorkspaceExpansion : LiveRelationBoundaryDocument

    @Serializable
    @SerialName("EXPLICIT")
    data class Explicit(
        val scope: LivePlanScopeDocument,
        val directory: LivePlanContainmentDocument?,
        val sourceSets: LiveExpansionSourceSetsDocument,
    ) : LiveRelationBoundaryDocument
}

@Serializable
internal sealed interface LiveExpansionSourceSetsDocument {
    @Serializable @SerialName("ALL") data object All : LiveExpansionSourceSetsDocument

    @Serializable @SerialName("EXACT") data class Exact(val values: List<String>) : LiveExpansionSourceSetsDocument
}

private fun RelationSearchBoundary.document(): LiveRelationBoundaryDocument =
    when (this) {
        RelationSearchBoundary.RETAINED_SUBJECT -> LiveRelationBoundaryDocument.RetainedSubject
        RelationSearchBoundary.WORKSPACE_EXPANSION -> LiveRelationBoundaryDocument.WorkspaceExpansion
        is RelationSearchBoundary.Explicit ->
            LiveRelationBoundaryDocument.Explicit(
                SymbolSearchScope.snapshot(scope).document(),
                directory?.let { LivePlanContainmentDocument(it.directory.value, it.containment.name) },
                when (val sets = sourceSets) {
                    SymbolDiscoverySourceSets.All -> LiveExpansionSourceSetsDocument.All
                    is SymbolDiscoverySourceSets.Exact ->
                        LiveExpansionSourceSetsDocument.Exact(sets.values.map { it.value })
                },
            )
    }

private fun LiveRelationBoundaryDocument.restore(
    basis: LiveChangeBasis
): Refinement<RelationSearchBoundary, LiveAddDeclarationPlanDecodeFailure> =
    when (this) {
        LiveRelationBoundaryDocument.RetainedSubject -> Refinement.Refined(RelationSearchBoundary.RETAINED_SUBJECT)
        LiveRelationBoundaryDocument.WorkspaceExpansion ->
            Refinement.Refined(RelationSearchBoundary.WORKSPACE_EXPANSION)
        is LiveRelationBoundaryDocument.Explicit -> {
            val scope =
                restoreLivePlanScope(basis, scope).required {
                    return rejected()
                }
            val directory = directory?.let {
                SymbolDiscoveryDirectoryConstraint(
                    SymbolDiscoveryDirectory.parse(it.value).required {
                        return rejected()
                    },
                    SymbolDiscoveryContainment.entries.singleOrNull { selected -> selected.name == it.containment }
                        ?: return rejected(),
                )
            }
            val sets =
                when (val captured = sourceSets) {
                    LiveExpansionSourceSetsDocument.All -> SymbolDiscoverySourceSets.All
                    is LiveExpansionSourceSetsDocument.Exact -> {
                        if (captured.values.distinct().size != captured.values.size) return rejected()
                        SymbolDiscoverySourceSets.Exact.from(
                                captured.values.mapTo(linkedSetOf()) {
                                    WorkspaceSourceSetName.parse(it).required {
                                        return rejected()
                                    }
                                }
                            )
                            .required {
                                return rejected()
                            }
                    }
                }
            Refinement.Refined(RelationSearchBoundary.Explicit(scope, directory, sets))
        }
    }
