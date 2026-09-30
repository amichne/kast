package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.source.contract.Containment
import io.github.amichne.kast.source.contract.DeclarationKind
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadContext
import io.github.amichne.kast.source.contract.SourceReadContinuation
import io.github.amichne.kast.source.contract.SourceReadContinuationPort
import io.github.amichne.kast.source.contract.SourceReadContinuationState
import io.github.amichne.kast.source.contract.SourceReadCursorProof
import io.github.amichne.kast.source.contract.SourceReadCursorRetentionFailure
import io.github.amichne.kast.source.contract.SourceReadEntityCursor
import io.github.amichne.kast.source.contract.SourceReadLimitation
import io.github.amichne.kast.source.contract.SourceReadPage
import io.github.amichne.kast.source.contract.SourceReadRejection
import io.github.amichne.kast.source.contract.SourceReadRequest
import io.github.amichne.kast.source.contract.SourceReadResult
import io.github.amichne.kast.source.contract.SourceTextByteLimit
import io.github.amichne.kast.source.contract.TextProjection
import io.github.amichne.kast.source.intellij.IntellijSourceEntityFixture.declarations
import io.github.amichne.kast.source.intellij.IntellijSourceEntityFixture.fixture
import io.github.amichne.kast.source.intellij.IntellijSourceEntityFixture.matching
import io.github.amichne.kast.source.intellij.IntellijSourceEntityFixture.names
import io.github.amichne.kast.source.intellij.IntellijSourceEntityFixture.refined
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/** Native adapter rules with detached observations; these cases make no compiler or host-publication claim. */
class SourceCursorReadTest {
    @Test
    fun `retention refusal preserves proven entities and exact bounded coverage`() {
        val result =
            read(SourceReadContinuationPort.Cursorless) { entities, request, cursor ->
                selectDetachedSourceFixture(entities.asSequence(), request.entities, cursor, request.entityLimit)
            }
        val qualified = assertInstanceOf(SourceReadResult.Qualified::class.java, result)
        assertEquals(listOf("direct"), qualified.entities.names())
        assertEquals(2, qualified.qualification.knownMinimumEntityCount.value)
        assertEquals(
            listOf(SourceReadLimitation.ENTITY_LIMIT_REACHED, SourceReadLimitation.RETENTION_LIMIT_REACHED),
            qualified.qualification.limitations,
        )
        assertEquals(SourceReadContinuationState.Unavailable, qualified.qualification.continuation)
    }

    @Test
    fun `bounded scan without a retained position cannot issue a repeated zero cursor`() {
        var issues = 0
        val owner =
            object : SourceReadContinuationPort {
                override fun admit(
                    context: SourceReadContext,
                    request: SourceReadRequest,
                ): Refinement<SourceReadEntityCursor, SourceReadRejection> =
                    Refinement.Refined(SourceReadEntityCursor.First)

                override fun issue(
                    proof: SourceReadCursorProof
                ): Refinement<SourceReadContinuation, SourceReadCursorRetentionFailure> {
                    issues += 1
                    error("No advancing detached work exists")
                }
            }
        val result =
            read(owner) { _, _, _ ->
                IntellijSourceEntityPage.Complete(emptyList(), 0, setOf(SourceReadLimitation.WORK_LIMIT_REACHED))
            }
        val qualified = assertInstanceOf(SourceReadResult.Qualified::class.java, result)
        assertEquals(emptyList<io.github.amichne.kast.source.contract.SourceEntity>(), qualified.entities)
        assertEquals(listOf(SourceReadLimitation.WORK_LIMIT_REACHED), qualified.qualification.limitations)
        assertEquals(SourceReadContinuationState.Unavailable, qualified.qualification.continuation)
        assertEquals(0, issues)
    }

    private fun read(
        owner: SourceReadContinuationPort,
        page:
            (
                List<io.github.amichne.kast.source.contract.SourceEntity>,
                SourceReadRequest,
                IntellijSourceEntityCursor,
            ) -> IntellijSourceEntityPage,
    ): SourceReadResult {
        val fixture = fixture()
        val request =
            SourceReadRequest(
                SourceReadAnchor.Source(fixture.region),
                RegionSelection.Anchor,
                matching(Containment.DESCENDANTS, declarations(setOf(DeclarationKind.FUNCTION), null)),
                TextProjection.None,
                SourceEntityLimit.parse(1).refined(),
                SourceTextByteLimit.parse(1_000).refined(),
                SourceReadPage.First,
                io.github.amichne.kast.kernel.ResourceBudget(
                    io.github.amichne.kast.kernel.ResultLimit.parse(1).refined(),
                    io.github.amichne.kast.kernel.WorkUnitLimit.parse(10).refined(),
                    io.github.amichne.kast.kernel.ElapsedTimeLimitMillis.parse(2_000).refined(),
                ),
            )
        val port =
            IntellijSourceReadPort(
                IntellijSourceRegionAccess { _, admitted, cursor ->
                    IntellijSourceRegionAccessResult.Selected(
                        IntellijSelectedSourceCapture.create(
                                fixture.snapshot,
                                fixture.region,
                                fixture.region,
                                fixture.text,
                                page(fixture.entities, admitted, cursor),
                            )
                            .refined()
                    )
                },
                owner,
            )
        var completion: Result<SourceReadResult>? = null
        suspend { port.read(fixture.snapshot.context, request) }
            .startCoroutine(
                object : Continuation<SourceReadResult> {
                    override val context = EmptyCoroutineContext

                    override fun resumeWith(result: Result<SourceReadResult>) {
                        completion = result
                    }
                }
            )
        return checkNotNull(completion).getOrThrow()
    }
}
