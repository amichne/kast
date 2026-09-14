package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.source.contract.Containment
import io.github.amichne.kast.source.contract.DeclarationKind
import io.github.amichne.kast.source.contract.DeclarationVisibility
import io.github.amichne.kast.source.contract.EntityFilter
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.NonEmptySourceRange
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceEntity
import io.github.amichne.kast.source.contract.SourceEntityKind
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceEntityName
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadContext
import io.github.amichne.kast.source.contract.SourceReadContinuationState
import io.github.amichne.kast.source.contract.SourceReadLimitation
import io.github.amichne.kast.source.contract.SourceReadPage
import io.github.amichne.kast.source.contract.SourceReadRejection
import io.github.amichne.kast.source.contract.SourceReadRequest
import io.github.amichne.kast.source.contract.SourceReadResult
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceTextByteLimit
import io.github.amichne.kast.source.contract.TextProjection
import io.github.amichne.kast.source.intellij.IntellijSourceEntityFixture.Fixture
import io.github.amichne.kast.source.intellij.IntellijSourceEntityFixture.context
import io.github.amichne.kast.source.intellij.IntellijSourceEntityFixture.declarationAt
import io.github.amichne.kast.source.intellij.IntellijSourceEntityFixture.declarations
import io.github.amichne.kast.source.intellij.IntellijSourceEntityFixture.fixture
import io.github.amichne.kast.source.intellij.IntellijSourceEntityFixture.matching
import io.github.amichne.kast.source.intellij.IntellijSourceEntityFixture.names
import io.github.amichne.kast.source.intellij.IntellijSourceEntityFixture.range
import io.github.amichne.kast.source.intellij.IntellijSourceEntityFixture.refined
import io.github.amichne.kast.source.intellij.IntellijSourceEntityFixture.snapshot
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntellijSourceEntityReadTest {
    @Test
    fun `excluded class and constructor property avoid compiler projection while nested function fills page`() {
        val text = "class Excluded(val property: Int) { fun retained() = Unit }"
        val snapshot = snapshot(text)
        val region = SourceSelector.issueRoot(range(snapshot, 0, text.length), SourceRegionKind.FILE)
        val parent =
            SourceSelector.issueEntity(
                    region,
                    NonEmptySourceRange.create(range(snapshot, 0, text.length)).refined(),
                    SourceEntityKind.DECLARATION_CLASSLIKE,
                    SourceEntityName.present("Excluded").refined(),
                )
                .refined()
        val collector =
            IntellijSourceEntityPageCollector(
                matching(Containment.DESCENDANTS, declarations(setOf(DeclarationKind.FUNCTION), null)),
                IntellijSourceEntityCursor(0),
                SourceEntityLimit.parse(1).refined(),
            )
        val projected = mutableListOf<DeclarationKind>()
        // Native traversal retains this structural selector even when its declaration is not emitted.
        collector.projectDeclaration(DeclarationKind.CLASSLIKE) { projected += DeclarationKind.CLASSLIKE }
        repeat(100) {
            collector.projectDeclaration(DeclarationKind.PROPERTY) { projected += DeclarationKind.PROPERTY }
        }
        val start = text.indexOf("fun retained")
        collector.projectDeclaration(DeclarationKind.FUNCTION) {
            projected += DeclarationKind.FUNCTION
            collector.offer(
                declarationAt(
                    snapshot,
                    parent,
                    start,
                    "retained",
                    DeclarationKind.FUNCTION,
                    DeclarationVisibility.PUBLIC,
                    1,
                    range(snapshot, start, text.length - 2),
                )
            )
        }
        val page = collector.finish() as IntellijSourceEntityPage.Complete
        assertEquals(listOf(DeclarationKind.FUNCTION), projected)
        assertEquals(listOf("retained"), page.entities.names())
        assertEquals(1, page.knownMinimumEntityCount)
        val retained = page.entities.single()
        assertEquals(parent, retained.parentSelector)
        assertEquals(1, retained.nestingDepth.value)
        assertEquals(start, retained.selector.range.startInclusive.value)
        assertEquals(text.length - 2, retained.selector.range.endExclusive.value)
    }

    @Test
    fun `declaration projection respects every admitted kind and never broadens parameter-only requests`() {
        for (requested in DeclarationKind.entries) {
            val collector =
                IntellijSourceEntityPageCollector(
                    matching(Containment.DESCENDANTS, declarations(setOf(requested), null)),
                    IntellijSourceEntityCursor(0),
                    SourceEntityLimit.parse(1).refined(),
                )
            val projected = mutableListOf<DeclarationKind>()
            for (candidate in DeclarationKind.entries) {
                collector.projectDeclaration(candidate) { projected += candidate }
            }
            assertEquals(listOf(requested), projected)
        }
        val parameters =
            IntellijSourceEntityPageCollector(
                matching(Containment.DESCENDANTS, EntityFilter.Parameters),
                IntellijSourceEntityCursor(0),
                SourceEntityLimit.parse(1).refined(),
            )
        for (kind in DeclarationKind.entries) {
            parameters.projectDeclaration(kind) { error("Parameter request must not project declarations") }
        }
    }

    @Test
    fun `declaration filters use semantic visibility and direct descendant depth`() {
        val fixture = fixture()
        val port = port(fixture)

        val direct =
            read(
                port,
                fixture,
                matching(
                    Containment.DIRECT,
                    declarations(setOf(DeclarationKind.FUNCTION), setOf(DeclarationVisibility.PUBLIC)),
                ),
            )
                as SourceReadResult.Complete
        assertEquals(listOf("direct"), direct.entities.names())
        assertEquals(listOf(0), direct.entities.map { it.nestingDepth.value })

        val descendants =
            read(
                port,
                fixture,
                matching(
                    Containment.DESCENDANTS,
                    declarations(setOf(DeclarationKind.FUNCTION), setOf(DeclarationVisibility.PUBLIC)),
                ),
            )
                as SourceReadResult.Complete
        assertEquals(listOf("direct", "nested"), descendants.entities.names())
        assertEquals(listOf(0, 1), descendants.entities.map { it.nestingDepth.value })
        assertEquals(
            descendants.entities.first().selector.fingerprint,
            descendants.entities.last().parentSelector.fingerprint,
        )
    }

    @Test
    fun `value parameters retain declaration ownership and constructor properties stay distinct`() {
        val fixture = fixture()
        val result =
            read(
                port(fixture),
                fixture,
                matching(Containment.DESCENDANTS, EntityFilter.Parameters),
            )
                as SourceReadResult.Complete

        assertEquals(listOf("p", "q", "item"), result.entities.names())
        assertEquals(listOf(1, 2, 2), result.entities.map { it.nestingDepth.value })
        result.entities.forEach { assertInstanceOf(SourceEntity.ValueParameter::class.java, it) }

        val properties =
            read(
                port(fixture),
                fixture,
                matching(
                    Containment.DESCENDANTS,
                    declarations(setOf(DeclarationKind.PROPERTY), setOf(DeclarationVisibility.PUBLIC)),
                ),
            )
                as SourceReadResult.Complete
        assertEquals(listOf("item"), properties.entities.names())
        assertEquals(
            result.entities.last().selector.range,
            properties.entities.single().selector.range,
        )
        assertTrue(result.entities.last().selector.fingerprint != properties.entities.single().selector.fingerprint)
    }

    @Test
    fun `complete empty entities are a trustworthy structural negative`() {
        val fixture = fixture()
        val result =
            read(
                port(fixture),
                fixture,
                matching(
                    Containment.DESCENDANTS,
                    declarations(setOf(DeclarationKind.TYPE_ALIAS), null),
                ),
            )
                as SourceReadResult.Complete

        assertTrue(result.entities.isEmpty())
    }

    @Test
    fun `entity bound returns exact ordered prefix and continuation re-enters final page`() {
        val fixture = fixture()
        val port = port(fixture)
        val selection =
            matching(
                Containment.DESCENDANTS,
                declarations(setOf(DeclarationKind.FUNCTION), setOf(DeclarationVisibility.PUBLIC)),
            )
        val first = read(port, fixture, selection, limit = 1) as SourceReadResult.Qualified

        assertEquals(listOf("direct"), first.entities.names())
        assertEquals(
            listOf(SourceReadLimitation.ENTITY_LIMIT_REACHED),
            first.qualification.limitations,
        )
        assertEquals(2, first.qualification.knownMinimumEntityCount.value)
        val continuation = (first.qualification.continuation as SourceReadContinuationState.Available).continuation

        val final =
            org.junit.jupiter.api.Assertions.assertInstanceOf(
                SourceReadResult.Complete::class.java,
                read(
                    port,
                    fixture,
                    selection,
                    limit = 4,
                    page = SourceReadPage.Continue(continuation),
                ),
            )
        assertEquals(listOf("nested"), final.entities.names())

        val reference = read(port, fixture, selection, limit = 4) as SourceReadResult.Complete
        assertEquals(reference.entities, first.entities + final.entities)
        val replay = read(port, fixture, selection, limit = 1) as SourceReadResult.Qualified
        assertEquals(first.qualification.continuation, replay.qualification.continuation)

        assertEquals(
            SourceReadResult.Rejected(
                io.github.amichne.kast.source.contract.SourceReadRejection.CONTINUATION_REQUEST_MISMATCH
            ),
            read(
                port,
                fixture,
                matching(Containment.DESCENDANTS, EntityFilter.Parameters),
                limit = 1,
                page = SourceReadPage.Continue(continuation),
            ),
        )
    }

    @Test
    fun `project continuation owner survives rebinding and rejects changed context and retirement`() {
        val fixture = fixture()
        val continuations = IntellijSourceReadContinuations()
        val selection =
            matching(
                Containment.DESCENDANTS,
                declarations(setOf(DeclarationKind.FUNCTION), setOf(DeclarationVisibility.PUBLIC)),
            )
        val first = read(port(fixture, continuations), fixture, selection, limit = 1) as SourceReadResult.Qualified
        val token = (first.qualification.continuation as SourceReadContinuationState.Available).continuation
        val page = SourceReadPage.Continue(token)
        val rebound = port(fixture, continuations)
        val final = read(rebound, fixture, selection, limit = 1, page = page) as SourceReadResult.Complete
        assertEquals(listOf("nested"), final.entities.names())

        val original = fixture.snapshot.context as SourceReadContext.Published
        val changed = original.copy(sourceState = WorkspaceStateIdentity.parse("workspace-state-v1|changed").refined())
        assertTrue(
            read(rebound, fixture, selection, limit = 1, page = page, readContext = changed)
                is SourceReadResult.Rejected
        )
        assertTrue(read(port(fixture), fixture, selection, limit = 1, page = page) is SourceReadResult.Rejected)
        continuations.retire()
        assertTrue(read(rebound, fixture, selection, limit = 1, page = page) is SourceReadResult.Rejected)
        assertTrue(read(rebound, fixture, selection, limit = 1) is SourceReadResult.Rejected)
    }

    @Test
    fun `continuation refusal preserves cause before invoking source provider`() {
        var now = 0L
        var invocations = 0
        val fixture = fixture()
        val owner =
            IntellijSourceReadContinuations(
                io.github.amichne.kast.kernel.ReadLimits.resolve(
                        environment =
                            mapOf(
                                io.github.amichne.kast.kernel.ReadLimitParameter.SOURCE_CONTINUATION_TTL_MILLIS
                                    .environmentKey to "10"
                            )
                    )
                    .refined()
            ) {
                now
            }
        val port = port(fixture, owner) { invocations += 1 }
        val selection = matching(Containment.DESCENDANTS, declarations(setOf(DeclarationKind.FUNCTION), null))
        val first = read(port, fixture, selection, limit = 1) as SourceReadResult.Qualified
        val token = (first.qualification.continuation as SourceReadContinuationState.Available).continuation
        val page = SourceReadPage.Continue(token)
        val original = fixture.snapshot.context as SourceReadContext.Published
        val changed = original.copy(sourceState = WorkspaceStateIdentity.parse("workspace-state-v1|changed").refined())
        fun reason(result: SourceReadResult) = assertInstanceOf(SourceReadResult.Rejected::class.java, result).reason
        assertEquals(
            SourceReadRejection.SOURCE_SNAPSHOT_MISMATCH,
            reason(read(port, fixture, selection, page = page, readContext = changed)),
        )
        assertEquals(
            SourceReadRejection.CONTINUATION_REQUEST_MISMATCH,
            reason(read(port, fixture, EntitySelection.None, page = page)),
        )
        val unknownOwner = port(fixture) { invocations += 1 }
        assertEquals(
            SourceReadRejection.CONTINUATION_UNAVAILABLE,
            reason(read(unknownOwner, fixture, selection, page = page)),
        )
        assertEquals(1, invocations)
        now = 10_000_000L
        assertEquals(SourceReadRejection.CONTINUATION_UNAVAILABLE, reason(read(port, fixture, selection, page = page)))
        owner.retire()
        assertEquals(SourceReadRejection.CONTINUATION_UNAVAILABLE, reason(read(port, fixture, selection, page = page)))
        assertEquals(SourceReadRejection.CONTINUATION_UNAVAILABLE, reason(read(port, fixture, selection)))
        assertEquals(1, invocations)
    }

    @Test
    fun `supported call filters return a complete empty structural negative`() {
        val fixture = fixture()
        val result =
            read(
                port(fixture),
                fixture,
                matching(Containment.DESCENDANTS, EntityFilter.Calls),
            )
                as SourceReadResult.Complete

        assertTrue(result.entities.isEmpty())
    }

    private fun port(
        fixture: Fixture,
        continuations: IntellijSourceReadContinuations = IntellijSourceReadContinuations(),
        onSelect: () -> Unit = {},
    ): IntellijSourceReadPort =
        IntellijSourceReadPort(
            IntellijSourceRegionAccess { _, request, cursor ->
                onSelect()
                val page =
                    IntellijSourceEntityPage.select(
                        fixture.entities.asSequence(),
                        request.entities,
                        cursor,
                        request.entityLimit,
                    )
                IntellijSourceRegionAccessResult.Selected(
                    IntellijSelectedSourceCapture.create(
                            fixture.snapshot,
                            fixture.region,
                            fixture.region,
                            fixture.text,
                            page,
                        )
                        .refined()
                )
            },
            continuations,
        )

    private fun read(
        port: IntellijSourceReadPort,
        fixture: Fixture,
        entities: EntitySelection,
        limit: Int = 250,
        page: SourceReadPage = SourceReadPage.First,
        readContext: SourceReadContext = context(fixture.snapshot),
    ): SourceReadResult = runSuspend {
        port.read(
            readContext,
            SourceReadRequest(
                SourceReadAnchor.Source(fixture.region),
                RegionSelection.Anchor,
                entities,
                TextProjection.None,
                SourceEntityLimit.parse(limit).refined(),
                SourceTextByteLimit.parse(65_536).refined(),
                page,
                resources = sourceTestResources(),
            ),
        )
    }

    private fun <Value> runSuspend(block: suspend () -> Value): Value {
        var completion: Result<Value>? = null
        block.startCoroutine(
            object : Continuation<Value> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<Value>) {
                    completion = result
                }
            }
        )
        return checkNotNull(completion).getOrThrow()
    }
}

private fun sourceTestResources(): io.github.amichne.kast.kernel.ResourceBudget =
    io.github.amichne.kast.kernel.ResourceBudget(
        (io.github.amichne.kast.kernel.ResultLimit.parse(1000) as Refinement.Refined).value,
        (io.github.amichne.kast.kernel.WorkUnitLimit.parse(10000) as Refinement.Refined).value,
        (io.github.amichne.kast.kernel.ElapsedTimeLimitMillis.parse(2000) as Refinement.Refined).value,
    )
