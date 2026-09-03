package io.github.amichne.kast.runtime.composition.protocol.graph

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationLimitationDocument
import io.github.amichne.kast.protocol.contract.TraversalContinuationDocument
import io.github.amichne.kast.protocol.contract.TraversalRunPositionDocument
import io.github.amichne.kast.protocol.contract.TraversalRunQualification
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverTargetDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.SymbolInspectRequest
import io.github.amichne.kast.protocol.contract.SymbolInspectTarget
import io.github.amichne.kast.protocol.contract.SymbolNameKindDocument
import io.github.amichne.kast.runtime.composition.InstalledSymbolProtocolFixture
import io.github.amichne.kast.runtime.composition.protocol.CanonicalProtocolAuthority
import io.github.amichne.kast.runtime.composition.protocol.RelationSubjectLookup
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationContinuation
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationProviderItemDescriptor
import io.github.amichne.kast.relation.contract.RelationRequest as DomainRelationRequest
import io.github.amichne.kast.traversal.contract.TraversalBudget
import io.github.amichne.kast.traversal.contract.TraversalByteLimit
import io.github.amichne.kast.traversal.contract.TraversalCheckpoint
import io.github.amichne.kast.traversal.contract.TraversalContinuation
import io.github.amichne.kast.traversal.contract.TraversalDepth
import io.github.amichne.kast.traversal.contract.TraversalDepthLimit
import io.github.amichne.kast.traversal.contract.TraversalFrontierEntry
import io.github.amichne.kast.traversal.contract.TraversalFrontierLimit
import io.github.amichne.kast.traversal.contract.TraversalLimitation
import io.github.amichne.kast.traversal.contract.TraversalNode
import io.github.amichne.kast.traversal.contract.TraversalOperations
import io.github.amichne.kast.traversal.contract.TraversalPage
import io.github.amichne.kast.traversal.contract.TraversalPendingRead
import io.github.amichne.kast.traversal.contract.TraversalPendingState
import io.github.amichne.kast.traversal.contract.TraversalPlan
import io.github.amichne.kast.traversal.contract.TraversalResult
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TraversalContinuationCodecTest {
    @Test
    fun `self contained checkpoint round trips pending relation authority`(
        @TempDir temporary: Path,
    ) {
        val fixture = InstalledSymbolProtocolFixture.create(
            Files.createDirectories(temporary.resolve("repo")).toRealPath(),
        )
        val authority = CanonicalProtocolAuthority()
        val exact = exactSelector(fixture, authority)
        val selector = (authority.relationSubject(exact) as RelationSubjectLookup.Selector).selector
        val budget = traversalBudget()
        val plan = TraversalPlan.start(selector, RelationMeaning.Callees, budget).refined()
        val node = TraversalNode.start(selector)
        val entry = TraversalFrontierEntry.create(plan, node, TraversalDepth.Zero).refined()
        val relationRequest = DomainRelationRequest.start(
            selector,
            RelationMeaning.Callees,
            budget.oneHop,
        )
        val relationCursor = relationRequest.providerCursor.advance(
            RelationProviderItemDescriptor.parse("consumed-provider-item").refined(),
        )
        val relationContinuation = RelationContinuation.issue(relationRequest, relationCursor)
        val pending = TraversalPendingRead.create(plan, entry, relationContinuation).refined()
        val checkpoint = TraversalCheckpoint.create(
            plan,
            emptyList(),
            setOf(node.fingerprint),
            TraversalPendingState.active(pending),
            setOf(RelationLimitation.UNRESOLVED_TARGET),
        ).refined()
        val continuation = TraversalContinuation.issue(plan, checkpoint).refined()

        val document = checkNotNull(
            CanonicalTraversalContinuationCodec.encode(continuation, authority),
        )
        val decoded = assertInstanceOf(
            CanonicalTraversalContinuationDecoding.Decoded::class.java,
            CanonicalTraversalContinuationCodec.decode(document, budget, authority),
        ).continuation
        val decodedPending = assertInstanceOf(
            TraversalPendingState.Active::class.java,
            decoded.checkpoint.pending,
        )

        assertEquals(continuation.fingerprint, decoded.fingerprint)
        assertEquals(
            relationContinuation.fingerprint,
            decodedPending.read.relationContinuation.fingerprint,
        )
        assertEquals(
            setOf(RelationLimitation.UNRESOLVED_TARGET),
            decoded.checkpoint.terminalRelationLimitations,
        )
    }

    @Test
    fun `public continuation restores the exact frontier without repeating its start`(
        @TempDir temporary: Path,
    ) {
        val fixture = InstalledSymbolProtocolFixture.create(
            Files.createDirectories(temporary.resolve("repo")).toRealPath(),
        )
        val authority = CanonicalProtocolAuthority()
        val exact = exactSelector(fixture, authority)
        val handler = CanonicalTraversalRunHandler(fixture.traversal, authority)
        val startOutcome = runSuspend {
                handler.execute(
                    TraversalRunRequest(
                        exact,
                        RelationKindDocument.CALLEES,
                        count(8),
                        count(1),
                    ),
                )
            }
        assertInstanceOf(OperationOutcome.Qualified::class.java, startOutcome)
        @Suppress("UNCHECKED_CAST")
        val start = startOutcome as
            OperationOutcome.Qualified<TraversalRunResult, TraversalRunQualification>
        val qualification = assertInstanceOf(
            TraversalRunQualification.Resumable::class.java,
            start.qualification,
        )
        val firstRelation = start.evidence.payload.records.values.single().relation

        val resumedOutcome = runSuspend {
                handler.execute(
                    TraversalRunRequest(
                        exact,
                        RelationKindDocument.CALLEES,
                        count(8),
                        count(1),
                        TraversalRunPositionDocument.Resume(qualification.continuation),
                    ),
                )
            }
        assertInstanceOf(OperationOutcome.Qualified::class.java, resumedOutcome)
        @Suppress("UNCHECKED_CAST")
        val resumed = resumedOutcome as
            OperationOutcome.Qualified<TraversalRunResult, TraversalRunQualification>
        val resumedRelation = resumed.evidence.payload.records.values.single().relation

        assertEquals(firstRelation.target.selector, resumedRelation.source.selector)
        assertNotEquals(firstRelation.source.selector, resumedRelation.source.selector)

        val wrongRelation = runSuspend {
            handler.execute(
                TraversalRunRequest(
                    exact,
                    RelationKindDocument.CALLERS,
                    count(8),
                    count(1),
                    TraversalRunPositionDocument.Resume(qualification.continuation),
                ),
            )
        }
        assertEquals(
            TraversalRunRejection.CONTINUATION_RELATION_MISMATCH,
            assertInstanceOf(OperationOutcome.Rejected::class.java, wrongRelation).reason,
        )

        val otherFixture = InstalledSymbolProtocolFixture.create(
            Files.createDirectories(temporary.resolve("other-repo")).toRealPath(),
        )
        val otherExact = exactSelector(otherFixture, authority)
        val wrongSubject = runSuspend {
            handler.execute(
                TraversalRunRequest(
                    otherExact,
                    RelationKindDocument.CALLEES,
                    count(8),
                    count(1),
                    TraversalRunPositionDocument.Resume(qualification.continuation),
                ),
            )
        }
        assertEquals(
            TraversalRunRejection.CONTINUATION_SUBJECT_MISMATCH,
            assertInstanceOf(OperationOutcome.Rejected::class.java, wrongSubject).reason,
        )
    }

    @Test
    fun `malformed checkpoint rejects before traversal work`(@TempDir temporary: Path) {
        val fixture = InstalledSymbolProtocolFixture.create(
            Files.createDirectories(temporary.resolve("repo")).toRealPath(),
        )
        val authority = CanonicalProtocolAuthority()
        val exact = exactSelector(fixture, authority)
        var calls = 0
        val handler = CanonicalTraversalRunHandler(
            TraversalOperations {
                calls += 1
                TraversalResult.Rejected(
                    io.github.amichne.kast.traversal.contract.TraversalRejection
                        .TraversalContractViolation,
                )
            },
            authority,
        )

        val outcome = runSuspend {
            handler.execute(
                TraversalRunRequest(
                    exact,
                    RelationKindDocument.CALLEES,
                    count(8),
                    count(1),
                    TraversalRunPositionDocument.Resume(token("not-a-checkpoint")),
                ),
            )
        }

        assertEquals(0, calls)
        assertEquals(
            TraversalRunRejection.CONTINUATION_MALFORMED,
            assertInstanceOf(OperationOutcome.Rejected::class.java, outcome).reason,
        )
    }

    @Test
    fun `terminal incomplete traversal projection has no continuation`(@TempDir temporary: Path) {
        val fixture = InstalledSymbolProtocolFixture.create(
            Files.createDirectories(temporary.resolve("repo")).toRealPath(),
        )
        val authority = CanonicalProtocolAuthority()
        val exact = exactSelector(fixture, authority)
        val handler = CanonicalTraversalRunHandler(
            TraversalOperations { plan ->
                val page = TraversalPage.fromBoundary(plan, emptyList(), 0L, 0L, 0L, 0)
                    .refined()
                TraversalResult.qualifiedTerminal(
                    page,
                    setOf(TraversalLimitation.ONE_HOP_INCOMPLETE),
                    setOf(io.github.amichne.kast.relation.contract.RelationLimitation.UNRESOLVED_TARGET),
                ).refined()
            },
            authority,
        )

        val outcome = assertInstanceOf(
            OperationOutcome.Qualified::class.java,
            runSuspend {
                handler.execute(
                    TraversalRunRequest(
                        exact,
                        RelationKindDocument.CALLEES,
                        count(8),
                        count(1),
                    ),
                )
            },
        )
        val qualification = assertInstanceOf(
            TraversalRunQualification.TerminalIncomplete::class.java,
            outcome.qualification,
        )
        assertEquals(
            listOf(RelationLimitationDocument.UNRESOLVED_TARGET),
            qualification.relationLimitations,
        )
    }

    private fun exactSelector(
        fixture: InstalledSymbolProtocolFixture,
        authority: CanonicalProtocolAuthority,
    ): ProtocolText {
        val discover = io.github.amichne.kast.runtime.composition.protocol
            .CanonicalSymbolDiscoverHandler(fixture.workspace, fixture.discovery, authority)
        val inspect = io.github.amichne.kast.runtime.composition.protocol
            .CanonicalSymbolInspectHandler(fixture.exact, authority)
        val candidate = (
            runSuspend {
                discover.execute(
                    SymbolDiscoverRequest(
                        SymbolDiscoverTargetDocument.Name(
                            text("sample"),
                            SymbolNameKindDocument.SYMBOL,
                            SymbolDiscoveryMatchDocument.EXACT_NAME,
                        ),
                        count(4),
                    ),
                )
            } as OperationOutcome.Complete
            ).evidence.payload.items.values.single() as SymbolDiscoveryDocument.Declaration
        return (
            runSuspend {
                inspect.execute(
                    SymbolInspectRequest(SymbolInspectTarget.Candidate(candidate.candidateSelector)),
                )
            } as OperationOutcome.Complete
            ).evidence.payload.symbol.selector
    }

    private fun token(payloadText: String): TraversalContinuationDocument {
        val payload = payloadText.toByteArray()
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return TraversalContinuationDocument.parse(
            "traversal-continuation:v1:$encoded:$digest",
        ).refined()
    }

    private fun count(raw: Int): ProtocolCount = ProtocolCount.parse(raw).refined()

    private fun traversalBudget(): TraversalBudget {
        val results = ResultLimit.parse(8).refined()
        val work = WorkUnitLimit.parse(64L).refined()
        val elapsed = ElapsedTimeLimitMillis.parse(1_000L).refined()
        val oneHop = RelationBudget(
            ResourceBudget(results, work, elapsed),
            RelationByteLimit.parse(100_000L).refined(),
        )
        return TraversalBudget(
            results,
            TraversalByteLimit.parse(100_000L).refined(),
            work,
            elapsed,
            TraversalDepthLimit.parse(8).refined(),
            TraversalFrontierLimit.parse(8).refined(),
            oneHop,
        )
    }

    private fun text(raw: String): ProtocolText = ProtocolText.parse(raw).refined()

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Unexpected fixture rejection: $failure")
    }
}

private fun <Value> runSuspend(block: suspend () -> Value): Value {
    var result: Result<Value>? = null
    block.startCoroutine(
        object : Continuation<Value> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(resultValue: Result<Value>) {
                result = resultValue
            }
        },
    )
    return checkNotNull(result).getOrThrow()
}
