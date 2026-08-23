package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.change.plan.PureAddDeclarationPlanningService
import io.github.amichne.kast.change.plan.PureAddFilePlanningService
import io.github.amichne.kast.change.plan.PureRenameSymbolPlanningService
import io.github.amichne.kast.change.plan.PureReplaceDeclarationPlanningService
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanRejection
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverTargetDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.SymbolNameKindDocument
import io.github.amichne.kast.protocol.contract.SymbolResolveRequest
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.runtime.composition.change.requireCompleteChangePlanTraversal
import io.github.amichne.kast.runtime.composition.protocol.CanonicalChangeAuthority
import io.github.amichne.kast.runtime.composition.protocol.CanonicalChangePlanHandler
import io.github.amichne.kast.runtime.composition.protocol.CanonicalProtocolAuthority
import io.github.amichne.kast.runtime.composition.protocol.CanonicalSymbolDiscoverHandler
import io.github.amichne.kast.runtime.composition.protocol.CanonicalSymbolResolveHandler
import io.github.amichne.kast.runtime.composition.protocol.ChangePlanAdmission
import io.github.amichne.kast.runtime.composition.protocol.ChangePlanAdmissionFailure
import io.github.amichne.kast.runtime.composition.protocol.ChangePlanAdmissionOperations
import io.github.amichne.kast.runtime.composition.protocol.ExactSelectorLookup
import io.github.amichne.kast.runtime.composition.protocol.graph.CanonicalTraversalRunHandler
import io.github.amichne.kast.runtime.composition.protocol.graph.TopologyBackedTraversalOperations
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.topology.contract.PublishedTopologySnapshot
import io.github.amichne.kast.topology.contract.TopologySnapshotContentReader
import io.github.amichne.kast.topology.contract.TopologySnapshotEligibility
import io.github.amichne.kast.topology.contract.TopologySnapshotManifest
import io.github.amichne.kast.topology.contract.TopologySnapshotReader
import io.github.amichne.kast.topology.contract.TopologyWorkspaceIdentity
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.contract.TraversalRejection
import io.github.amichne.kast.traversal.contract.TraversalResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.startCoroutine

class TopologyPrerequisiteAcceptanceTest {
    @Test
    fun `missing topology rejects public traversal before content read`(@TempDir temporary: Path) {
        val context = resolvedContext(temporary)
        var contentRead = false
        val operations = TopologyBackedTraversalOperations(
            context.fixture.workspace,
            TopologySnapshotReader { TopologySnapshotEligibility.Unavailable },
            TopologySnapshotContentReader {
                contentRead = true
                error("missing topology must not reach content")
            },
        )
        val domainResult = runImmediate { operations.run(context.traversalPlan()) }
        assertEquals(
            TraversalResult.Rejected(TraversalRejection.RequiredEvidenceUnavailable),
            domainResult,
        )
        val required = domainResult.requireCompleteChangePlanTraversal()

        val outcome = runImmediate {
            CanonicalTraversalRunHandler(operations, context.authority).execute(
                traversalRequest(context.exact),
            )
        }

        assertEquals(
            OperationOutcome.Rejected(TraversalRunRejection.TOPOLOGY_BUILD_REQUIRED),
            outcome,
        )
        assertEquals(
            Refinement.Rejected(ChangePlanAdmissionFailure.TOPOLOGY_BUILD_REQUIRED),
            required,
        )
        assertFalse(contentRead)
    }

    @Test
    fun `stale topology for current selector rejects before content read`(@TempDir temporary: Path) {
        val context = resolvedContext(temporary)
        var observedIdentity: TopologyWorkspaceIdentity? = null
        var contentRead = false
        val operations = TopologyBackedTraversalOperations(
            context.fixture.workspace,
            TopologySnapshotReader { identity ->
                observedIdentity = identity
                TopologySnapshotEligibility.Stale(staleSnapshot(identity))
            },
            TopologySnapshotContentReader {
                contentRead = true
                error("stale topology must not reach content")
            },
        )
        val domainResult = runImmediate { operations.run(context.traversalPlan()) }
        assertEquals(
            TraversalResult.Rejected(TraversalRejection.RequiredEvidenceStale),
            domainResult,
        )
        val required = domainResult.requireCompleteChangePlanTraversal()

        val outcome = runImmediate {
            CanonicalTraversalRunHandler(operations, context.authority).execute(
                traversalRequest(context.exact),
            )
        }

        assertEquals(
            OperationOutcome.Rejected(TraversalRunRejection.TOPOLOGY_BUILD_REQUIRED),
            outcome,
        )
        assertEquals(
            Refinement.Rejected(ChangePlanAdmissionFailure.TOPOLOGY_BUILD_REQUIRED),
            required,
        )
        assertEquals(context.workspaceIdentity, observedIdentity)
        assertFalse(contentRead)
    }

    @Test
    fun `stale selector rejects public traversal without running traversal`(
        @TempDir temporary: Path,
    ) {
        val context = resolvedContext(temporary)
        var traversalRun = false
        val operations = TraversalOperations {
            traversalRun = true
            error("stale selector must not reach traversal")
        }

        val outcome = runImmediate {
            CanonicalTraversalRunHandler(operations, context.authority).execute(
                traversalRequest(ProtocolText.parse("unissued-exact-selector").refined()),
            )
        }

        assertEquals(
            OperationOutcome.Rejected(TraversalRunRejection.SELECTOR_STALE),
            outcome,
        )
        assertFalse(traversalRun)
    }

    @Test
    fun `traversal contract violation rejects public traversal as plan rejected`(
        @TempDir temporary: Path,
    ) {
        val context = resolvedContext(temporary)
        var traversalRuns = 0
        val operations = TraversalOperations {
            traversalRuns += 1
            TraversalResult.Rejected(TraversalRejection.TraversalContractViolation)
        }

        val outcome = runImmediate {
            CanonicalTraversalRunHandler(operations, context.authority).execute(
                traversalRequest(context.exact),
            )
        }

        assertEquals(
            OperationOutcome.Rejected(TraversalRunRejection.PLAN_REJECTED),
            outcome,
        )
        assertEquals(1, traversalRuns)
    }

    @Test
    fun `qualified required traversal rejects public change planning`(@TempDir temporary: Path) {
        val context = resolvedContext(temporary)
        val result = runImmediate { context.fixture.traversal.run(context.traversalPlan()) }
        assertInstanceOf(TraversalResult.Qualified::class.java, result)
        val required = result.requireCompleteChangePlanTraversal()
        assertEquals(
            Refinement.Rejected(ChangePlanAdmissionFailure.REQUIRED_TRAVERSAL_INCOMPLETE),
            required,
        )
        val admission = ChangePlanAdmissionOperations {
            when (required) {
                is Refinement.Refined -> error("qualified traversal cannot admit change planning")
                is Refinement.Rejected -> ChangePlanAdmission.Rejected(required.failure)
            }
        }
        val handler = CanonicalChangePlanHandler(
            ChangePlanningOperations(
                PureAddFilePlanningService(),
                PureAddDeclarationPlanningService(),
                PureReplaceDeclarationPlanningService(),
                PureRenameSymbolPlanningService(),
            ),
            admission,
            context.authority,
            CanonicalChangeAuthority(),
        )

        assertEquals(
            OperationOutcome.Rejected(ChangePlanRejection.REQUIRED_TRAVERSAL_INCOMPLETE),
            runImmediate {
                handler.execute(
                    ChangePlanRequest(
                        ChangeIntentDocument.AddDeclaration(
                            context.exact,
                            ProtocolText.parse("fun added() = Unit").refined(),
                        ),
                    ),
                )
            },
        )
    }
}

private data class ResolvedTopologyContext(
    val fixture: InstalledSymbolProtocolFixture,
    val authority: CanonicalProtocolAuthority,
    val exact: ProtocolText,
    val selector: SymbolSelector,
    val workspaceIdentity: TopologyWorkspaceIdentity,
) {
    fun traversalPlan(): TraversalPlan = TraversalPlan.start(
        selector,
        io.github.amichne.kast.relation.contract.RelationMeaning.References,
        checkNotNull(installedSemanticBudgets()).traversal,
    ).refined()
}

private fun resolvedContext(temporary: Path): ResolvedTopologyContext {
    val root = Files.createDirectories(temporary.resolve("repo")).toRealPath()
    val fixture = InstalledSymbolProtocolFixture.create(root)
    val authority = CanonicalProtocolAuthority()
    val discover = CanonicalSymbolDiscoverHandler(fixture.workspace, fixture.discovery, authority)
    val resolve = CanonicalSymbolResolveHandler(fixture.exact, authority)
    val discovered = runImmediate {
        discover.execute(symbolDiscoverRequest("sample", 4))
    } as OperationOutcome.Complete
    val candidate = (
        discovered.evidence.payload.items.values.single() as SymbolDiscoveryDocument.Declaration
        ).candidateSelector
    val resolved = runImmediate { resolve.execute(SymbolResolveRequest(candidate)) } as
        OperationOutcome.Complete
    val workspace = runImmediate { fixture.workspace.inspect() } as
        io.github.amichne.kast.workspace.contract.WorkspaceRuntimeState.Ready
    val exact = resolved.evidence.payload.exactSelector
    val selector = when (val lookup = authority.exact(exact)) {
        is ExactSelectorLookup.Found -> lookup.selector
        ExactSelectorLookup.Missing -> error("resolved selector authority is required")
    }
    return ResolvedTopologyContext(
        fixture,
        authority,
        exact,
        selector,
        TopologyWorkspaceIdentity.from(workspace.workspace),
    )
}

private fun symbolDiscoverRequest(raw: String, limit: Int): SymbolDiscoverRequest =
    SymbolDiscoverRequest(
        SymbolDiscoverTargetDocument.Name(
            ProtocolText.parse(raw).refined(),
            SymbolNameKindDocument.SYMBOL,
            SymbolDiscoveryMatchDocument.FUZZY,
        ),
        ProtocolCount.parse(limit).refined(),
    )

private fun traversalRequest(exact: ProtocolText): TraversalRunRequest = TraversalRunRequest(
    exact,
    RelationKindDocument.REFERENCES,
    ProtocolCount.parse(1).refined(),
    ProtocolCount.parse(4).refined(),
)

private fun staleSnapshot(workspaceIdentity: TopologyWorkspaceIdentity): PublishedTopologySnapshot =
    object : PublishedTopologySnapshot {
        override val identity: TopologyWorkspaceIdentity = workspaceIdentity
        override val manifest: TopologySnapshotManifest
            get() = error("stale snapshot manifest must not be read")
    }

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error("unexpected rejection: $failure")
}

private fun <Value> runImmediate(block: suspend () -> Value): Value {
    var completed: Result<Value>? = null
    block.startCoroutine(
        object : kotlin.coroutines.Continuation<Value> {
            override val context = kotlin.coroutines.EmptyCoroutineContext

            override fun resumeWith(result: Result<Value>) {
                completed = result
            }
        },
    )
    return checkNotNull(completed) { "operation suspended unexpectedly" }.getOrThrow()
}
