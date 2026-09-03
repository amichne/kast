package io.github.amichne.kast.runtime.composition.protocol.graph

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.RelationContinuationDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.RelationReadPositionDocument
import io.github.amichne.kast.protocol.contract.RelationReadQualification
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverTargetDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.SymbolInspectRequest
import io.github.amichne.kast.protocol.contract.SymbolInspectTarget
import io.github.amichne.kast.protocol.contract.SymbolNameKindDocument
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOperations
import io.github.amichne.kast.relation.contract.RelationReadResult
import io.github.amichne.kast.relation.contract.RelationRequest as DomainRelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.runtime.composition.InstalledSymbolProtocolFixture
import io.github.amichne.kast.runtime.composition.protocol.CanonicalProtocolAuthority
import io.github.amichne.kast.runtime.composition.protocol.CanonicalSymbolDiscoverHandler
import io.github.amichne.kast.runtime.composition.protocol.CanonicalSymbolInspectHandler
import io.github.amichne.kast.runtime.composition.protocol.RelationSubjectLookup
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RelationContinuationCodecTest {
    @Test
    fun `self contained continuation round trips and runtime admits resume`(
        @TempDir temporary: Path,
    ) {
        val fixture = InstalledSymbolProtocolFixture.create(
            Files.createDirectories(temporary.resolve("repo")).toRealPath(),
        )
        val authority = CanonicalProtocolAuthority()
        val exact = exactSelector(fixture, authority)
        val selector = (authority.relationSubject(exact) as RelationSubjectLookup.Selector).selector
        val start = DomainRelationRequest.start(selector, RelationMeaning.References, budget())
        val continuation = io.github.amichne.kast.relation.contract.RelationContinuation.issue(
            start,
            start.providerCursor,
        )
        val document = checkNotNull(CanonicalRelationContinuationCodec.encode(continuation))
        val decoded = assertInstanceOf(
            CanonicalRelationContinuationDecoding.Decoded::class.java,
            CanonicalRelationContinuationCodec.decode(document),
        ).continuation
        assertEquals(continuation.fingerprint, decoded.fingerprint)
        assertEquals(continuation.nextProviderCursor, decoded.nextProviderCursor)

        var captured: DomainRelationRequest? = null
        val handler = CanonicalRelationReadHandler(
            RelationOperations { request ->
                captured = request
                RelationReadResult.Rejected(
                    io.github.amichne.kast.relation.contract.RelationReadRejection.COMPILER_CONTRACT_VIOLATION,
                )
            },
            authority,
        )
        runSuspend {
            handler.execute(
                RelationReadRequest(
                    exact,
                    RelationKindDocument.REFERENCES,
                    count(4),
                    RelationReadPositionDocument.Resume(document),
                ),
            )
        }
        assertInstanceOf(
            io.github.amichne.kast.relation.contract.RelationReadPosition.Resume::class.java,
            captured?.position,
        )

        val mismatch = runSuspend {
            handler.execute(
                RelationReadRequest(
                    exact,
                    RelationKindDocument.CALLERS,
                    count(4),
                    RelationReadPositionDocument.Resume(document),
                ),
            )
        }
        assertEquals(
            RelationReadRejection.CONTINUATION_RELATION_MISMATCH,
            assertInstanceOf(OperationOutcome.Rejected::class.java, mismatch).reason,
        )
    }

    @Test
    fun `malformed payload rejects before relation work`(@TempDir temporary: Path) {
        val fixture = InstalledSymbolProtocolFixture.create(
            Files.createDirectories(temporary.resolve("repo")).toRealPath(),
        )
        val authority = CanonicalProtocolAuthority()
        val exact = exactSelector(fixture, authority)
        var calls = 0
        val handler = CanonicalRelationReadHandler(
            RelationOperations {
                calls += 1
                RelationReadResult.Rejected(
                    io.github.amichne.kast.relation.contract.RelationReadRejection.COMPILER_CONTRACT_VIOLATION,
                )
            },
            authority,
        )

        val outcome = runSuspend {
            handler.execute(
                RelationReadRequest(
                    exact,
                    RelationKindDocument.REFERENCES,
                    count(4),
                    RelationReadPositionDocument.Resume(token("not-domain-fields")),
                ),
            )
        }

        assertEquals(0, calls)
        assertEquals(
            RelationReadRejection.CONTINUATION_MALFORMED,
            assertInstanceOf(OperationOutcome.Rejected::class.java, outcome).reason,
        )
    }

    @Test
    fun `terminal incomplete projection exposes no continuation`(@TempDir temporary: Path) {
        val fixture = InstalledSymbolProtocolFixture.create(
            Files.createDirectories(temporary.resolve("repo")).toRealPath(),
        )
        val authority = CanonicalProtocolAuthority()
        val exact = exactSelector(fixture, authority)
        val handler = CanonicalRelationReadHandler(
            RelationOperations { request -> terminalIncomplete(request) },
            authority,
        )

        val outcome = runSuspend {
            handler.execute(
                RelationReadRequest(exact, RelationKindDocument.REFERENCES, count(4)),
            )
        }

        val qualified = assertInstanceOf(OperationOutcome.Qualified::class.java, outcome)
        assertInstanceOf(
            RelationReadQualification.TerminalIncomplete::class.java,
            qualified.qualification,
        )
    }

    private fun terminalIncomplete(request: DomainRelationRequest): RelationReadResult {
        val batch = RelationBatch.create(
            request,
            emptyList(),
            RelationByteCount.parse(0L).refined(),
            RelationWorkCount.parse(0L).refined(),
            RelationResultCount.parse(0).refined(),
        ).refined()
        val qualified = RelationCompilation.qualifiedTerminal(
            batch,
            setOf(RelationLimitation.UNRESOLVED_TARGET),
        ).refined()
        return RelationReadResult.Qualified(batch, qualified.coverage)
    }

    private fun exactSelector(
        fixture: InstalledSymbolProtocolFixture,
        authority: CanonicalProtocolAuthority,
    ): ProtocolText {
        val discover = CanonicalSymbolDiscoverHandler(fixture.workspace, fixture.discovery, authority)
        val inspect = CanonicalSymbolInspectHandler(fixture.exact, authority)
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

    private fun budget(): RelationBudget = RelationBudget(
        ResourceBudget(
            ResultLimit.parse(4).refined(),
            WorkUnitLimit.parse(16L).refined(),
            ElapsedTimeLimitMillis.parse(1_000L).refined(),
        ),
        RelationByteLimit.parse(100_000L).refined(),
    )

    private fun token(payloadText: String): RelationContinuationDocument {
        val payload = payloadText.toByteArray()
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return RelationContinuationDocument.parse(
            "relation-continuation:v1:$encoded:$digest",
        ).refined()
    }

    private fun count(raw: Int): ProtocolCount = ProtocolCount.parse(raw).refined()

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
