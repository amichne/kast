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
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Durable request metadata retains original limits; decoding grants no read authority. */
internal object LiveVerificationScopeCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    fun canonicalIdentity(scope: LiveAddDeclarationVerificationScope): String =
        json.encodeToString(LiveVerificationScopeDocument.serializer(), document(scope))

    fun document(scope: LiveAddDeclarationVerificationScope) =
        LiveVerificationScopeDocument(
            scope.relations.map {
                LiveRelationReplayDocument(it.meaning.documentName(), it.budget.document(), it.boundary.name)
            },
            scope.traversals.map { replay ->
                val budget = replay.budget
                LiveTraversalReplayDocument(
                    meaning = replay.meaning.documentName(),
                    records = budget.records.value,
                    returnedBytes = budget.returnedBytes.value,
                    workUnits = budget.workUnits.value,
                    elapsedTime = budget.elapsedTime.value,
                    depth = budget.depth.value,
                    frontier = budget.frontier.value,
                    oneHop = budget.oneHop.document(),
                )
            },
            scope.diagnostics.map { it.files.map { file -> file.value } },
        )

    fun restore(
        document: LiveVerificationScopeDocument,
        root: CanonicalWorkspaceRoot,
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
                    RelationSearchBoundary.entries.singleOrNull { it.name == replay.boundary } ?: return rejected()
                LivePlannedRelationRead(meaning, budget, boundary)
            }
        val traversals =
            document.traversals.map { replay ->
                replay.restoreTraversal().required {
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
                    CanonicalWorkspaceFilePath.fromCanonicalPath(root, path).required {
                        return rejected()
                    }
                }
                LivePlannedDiagnosticScope.restore(files).required {
                    return rejected()
                }
            }
        return LiveAddDeclarationVerificationScope.restore(relations, traversals, diagnostics, evidence)
    }

    private fun LiveTraversalReplayDocument.restoreTraversal():
        Refinement<LivePlannedTraversal, LiveAddDeclarationPlanDecodeFailure> {
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
                depth =
                    TraversalDepthLimit.parse(depth).required {
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
        return Refinement.Refined(LivePlannedTraversal(meaning, budget))
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
    val boundary: String,
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
    val depth: Int,
    val frontier: Int,
    val oneHop: LiveRelationBudgetDocument,
)

private fun rejected() = Refinement.Rejected(LiveAddDeclarationPlanDecodeFailure.EVIDENCE_INCOMPLETE)

private inline fun <T, F> Refinement<T, F>.required(onFailure: () -> Nothing): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> onFailure()
    }
