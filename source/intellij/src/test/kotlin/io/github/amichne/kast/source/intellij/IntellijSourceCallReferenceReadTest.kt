package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.source.contract.CompilerUnresolvedReason
import io.github.amichne.kast.source.contract.Containment
import io.github.amichne.kast.source.contract.EntityFilter
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.NonEmptySourceRange
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceEntity
import io.github.amichne.kast.source.contract.SourceEntityKind
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceEntityName
import io.github.amichne.kast.source.contract.SourceEntityTarget
import io.github.amichne.kast.source.contract.SourceNestingDepth
import io.github.amichne.kast.source.contract.SourceRange
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadContext
import io.github.amichne.kast.source.contract.SourceReadContinuationState
import io.github.amichne.kast.source.contract.SourceReadLimitation
import io.github.amichne.kast.source.contract.SourceReadPage
import io.github.amichne.kast.source.contract.SourceReadRequest
import io.github.amichne.kast.source.contract.SourceReadResult
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.source.contract.SourceTextByteLimit
import io.github.amichne.kast.source.contract.SourceTextIdentity
import io.github.amichne.kast.source.contract.TextProjection
import io.github.amichne.kast.source.contract.Utf16CodeUnitCount
import io.github.amichne.kast.source.contract.Utf16CodeUnitOffset
import io.github.amichne.kast.symbol.contract.CandidateSelector
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Path
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntellijSourceCallReferenceReadTest {
    @Test
    fun `calls own exact callees and references retain closed targets without callee duplication`() {
        val fixture = fixture()
        val result = read(
            port(fixture),
            fixture,
            matching(Containment.DESCENDANTS, EntityFilter.Calls, EntityFilter.References),
        ) as SourceReadResult.Complete

        assertEquals(
            listOf("globalTarget", "consume", "local", "missing", "globalTarget"),
            result.entities.names(),
        )
        val firstCall = result.entities[0] as SourceEntity.Call
        assertEquals(firstCall.selector.fingerprint, firstCall.calleeSelector.parent.fingerprint)
        assertTrue(firstCall.selector.range.endExclusive > firstCall.calleeSelector.range.endExclusive)
        assertInstanceOf(SourceEntityTarget.Candidate::class.java, firstCall.target)

        val local = result.entities[2] as SourceEntity.Reference
        val localTarget = local.target as SourceEntityTarget.Local
        assertEquals("local", localTarget.selector.nameValue())
        assertInstanceOf(
            SourceEntityTarget.Unresolved::class.java,
            (result.entities[3] as SourceEntity.Reference).target,
        )
        assertEquals(
            CompilerUnresolvedReason.NAME_NOT_FOUND,
            ((result.entities[3] as SourceEntity.Reference).target as SourceEntityTarget.Unresolved)
                .reason,
        )
        assertTrue(
            result.entities.filterIsInstance<SourceEntity.Reference>()
                .none { it.selector.nameValue() == "consume" || it.selector.nameValue() == "globalTarget" },
        )
    }

    @Test
    fun `direct and descendant calls preserve structural depth and exact prefix continuation`() {
        val fixture = fixture()
        val port = port(fixture)
        val direct = read(
            port,
            fixture,
            matching(Containment.DIRECT, EntityFilter.Calls),
        ) as SourceReadResult.Complete
        assertEquals(listOf("globalTarget", "consume"), direct.entities.names())
        assertEquals(listOf(0, 0), direct.entities.map { it.nestingDepth.value })

        val selection = matching(Containment.DESCENDANTS, EntityFilter.Calls)
        val first = read(port, fixture, selection, limit = 2) as SourceReadResult.Qualified
        assertEquals(listOf("globalTarget", "consume"), first.entities.names())
        assertEquals(listOf(SourceReadLimitation.ENTITY_LIMIT_REACHED), first.qualification.limitations)
        val continuation =
            (first.qualification.continuation as SourceReadContinuationState.Available).continuation

        val final = read(
            port,
            fixture,
            selection,
            limit = 2,
            page = SourceReadPage.Continue(continuation),
        ) as SourceReadResult.Complete
        assertEquals(listOf("globalTarget"), final.entities.names())
        assertEquals(1, final.entities.single().nestingDepth.value)
    }

    @Test
    fun `provider-incomplete target analysis stays explicitly qualified`() {
        val fixture = fixture()
        val port = IntellijSourceReadPort(
            IntellijSourceRegionAccess { _, request, cursor ->
                val selected = IntellijSourceEntityPage.select(
                    fixture.entities.asSequence(),
                    request.entities,
                    cursor,
                    request.entityLimit,
                ) as IntellijSourceEntityPage.Complete
                IntellijSourceRegionAccessResult.Selected(
                    IntellijSelectedSourceCapture.create(
                        fixture.snapshot,
                        fixture.region,
                        fixture.region,
                        fixture.text,
                        selected.copy(
                            limitations = selected.limitations +
                                SourceReadLimitation.SEMANTIC_RESOLUTION_INCOMPLETE,
                        ),
                    ).refined(),
                )
            },
        )

        val result = read(
            port,
            fixture,
            matching(Containment.DESCENDANTS, EntityFilter.References),
        ) as SourceReadResult.Qualified

        assertEquals(
            listOf(SourceReadLimitation.SEMANTIC_RESOLUTION_INCOMPLETE),
            result.qualification.limitations,
        )
        assertEquals(SourceReadContinuationState.Unavailable, result.qualification.continuation)
        assertEquals(listOf("local", "missing"), result.entities.names())
    }

    private fun port(fixture: Fixture): IntellijSourceReadPort = IntellijSourceReadPort(
        IntellijSourceRegionAccess { _, request, cursor ->
            val page = IntellijSourceEntityPage.select(
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
                ).refined(),
            )
        },
    )

    private fun read(
        port: IntellijSourceReadPort,
        fixture: Fixture,
        entities: EntitySelection,
        limit: Int = 250,
        page: SourceReadPage = SourceReadPage.First,
    ): SourceReadResult = runSuspend {
        port.read(
            SourceReadContext(fixture.snapshot.lease, fixture.snapshot.sourceState),
            SourceReadRequest(
                SourceReadAnchor.Source(fixture.region),
                RegionSelection.Anchor,
                entities,
                TextProjection.None,
                SourceEntityLimit.parse(limit).refined(),
                SourceTextByteLimit.parse(65_536).refined(),
                page,
            ),
        )
    }

    private fun fixture(): Fixture {
        val text = """
            fun globalTarget() = Unit
            fun subject() {
                globalTarget()
                val local = 1
                consume(local)
                missing
                run {
                    globalTarget()
                }
            }
        """.trimIndent() + "\n"
        val snapshot = snapshot(text)
        val region = SourceSelector.issueRoot(range(snapshot, 0, text.length), SourceRegionKind.FILE)
        val globalSelector = globalSelector(snapshot)
        val localStart = text.indexOf("val local")
        val localDeclaration = entitySelector(
            snapshot,
            region,
            localStart,
            text.indexOf('\n', localStart),
            SourceEntityKind.DECLARATION_PROPERTY,
            "local",
        )
        val directGlobalStart = text.indexOf("globalTarget()", text.indexOf("fun subject"))
        val consumeStart = text.indexOf("consume(local)")
        val localReferenceStart = text.indexOf("local", consumeStart)
        val missingStart = text.indexOf("missing")
        val lambdaStart = text.indexOf('{', text.indexOf("run"))
        val lambdaEnd = text.indexOf("\n    }", lambdaStart) + "\n    }".length
        val lambda = SourceSelector.issueNested(
            region,
            range(snapshot, lambdaStart, lambdaEnd),
            SourceRegionKind.CALLABLE_BODY,
        ).refined()
        val nestedGlobalStart = text.indexOf("globalTarget()", directGlobalStart + 1)
        return Fixture(
            text,
            snapshot,
            region,
            listOf(
                call(snapshot, region, text, directGlobalStart, "globalTarget", 0, SourceEntityTarget.Candidate(globalSelector)),
                call(
                    snapshot,
                    region,
                    text,
                    consumeStart,
                    "consume",
                    0,
                    SourceEntityTarget.Unresolved(CompilerUnresolvedReason.NAME_NOT_FOUND),
                ),
                reference(
                    snapshot,
                    region,
                    localReferenceStart,
                    "local",
                    0,
                    SourceEntityTarget.Local(localDeclaration),
                ),
                reference(
                    snapshot,
                    region,
                    missingStart,
                    "missing",
                    0,
                    SourceEntityTarget.Unresolved(CompilerUnresolvedReason.NAME_NOT_FOUND),
                ),
                call(snapshot, lambda, text, nestedGlobalStart, "globalTarget", 1, SourceEntityTarget.Candidate(globalSelector)),
            ),
        )
    }

    private fun call(
        snapshot: SourceSnapshot,
        parent: SourceSelector,
        text: String,
        start: Int,
        name: String,
        depth: Int,
        target: SourceEntityTarget,
    ): SourceEntity.Call {
        val callSelector = entitySelector(
            snapshot,
            parent,
            start,
            text.indexOf(')', start) + 1,
            SourceEntityKind.CALL,
            name,
        )
        val calleeSelector = entitySelector(
            snapshot,
            callSelector,
            start,
            start + name.length,
            SourceEntityKind.CALLEE,
            name,
        )
        return SourceEntity.Call.create(
            callSelector,
            SourceNestingDepth.parse(depth).refined(),
            calleeSelector,
            target,
        ).refined()
    }

    private fun reference(
        snapshot: SourceSnapshot,
        parent: SourceSelector,
        start: Int,
        name: String,
        depth: Int,
        target: SourceEntityTarget,
    ): SourceEntity.Reference = SourceEntity.Reference.create(
        entitySelector(
            snapshot,
            parent,
            start,
            start + name.length,
            SourceEntityKind.REFERENCE,
            name,
        ),
        SourceNestingDepth.parse(depth).refined(),
        target,
    ).refined()

    private fun entitySelector(
        snapshot: SourceSnapshot,
        parent: SourceSelector,
        start: Int,
        end: Int,
        kind: SourceEntityKind,
        name: String,
    ): SourceSelector.Entity = SourceSelector.issueEntity(
        parent,
        NonEmptySourceRange.create(range(snapshot, start, end)).refined(),
        kind,
        SourceEntityName.present(name).refined(),
    ).refined()

    private fun globalSelector(snapshot: SourceSnapshot): CandidateSelector.Declaration {
        val candidate = SymbolDiscoveryCandidate.fromBoundary(
            SymbolDiscoveryKind.SYMBOL,
            "globalTarget",
            snapshot.lease,
            Path.of(snapshot.file.path.value),
            "file://${snapshot.file.path.value}",
            0,
        ).refined()
        val selection = SymbolDiscoverySelection.restore(
            snapshot.lease,
            SymbolSearchScope.ExactFile(
                snapshot.file.path,
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.INCLUDE,
            ),
            candidate,
        ).refined()
        return CandidateSelector.declaration(selection).refined()
    }

    private fun snapshot(text: String): SourceSnapshot {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val path = CanonicalWorkspaceFilePath.fromCanonicalPath(
            root,
            Path.of("/workspace/src/Subject.kt"),
        ).refined()
        return SourceSnapshot.create(
            SemanticReadLease(root, EvidenceGeneration.parse(42).refined()),
            WorkspaceStateIdentity.parse("workspace-state-v1|source").refined(),
            SymbolDiscoveryFileIdentity.Workspace(path),
            SourceTextIdentity.fromNormalizedCommittedText(text),
            Utf16CodeUnitCount.parse(text.length).refined(),
        )
    }

    private fun range(snapshot: SourceSnapshot, start: Int, end: Int): SourceRange =
        SourceRange.create(
            snapshot,
            Utf16CodeUnitOffset.parse(start).refined(),
            Utf16CodeUnitOffset.parse(end).refined(),
        ).refined()

    private fun matching(
        containment: Containment,
        vararg filters: EntityFilter,
    ): EntitySelection.Matching = EntitySelection.matching(containment, filters.toList()).refined()

    private fun List<SourceEntity>.names(): List<String> = map { it.selector.nameValue() }

    private fun SourceSelector.nameValue(): String =
        ((this as SourceSelector.Entity).name as SourceEntityName.Present).value

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }

    private fun <Value> runSuspend(block: suspend () -> Value): Value {
        var completion: Result<Value>? = null
        block.startCoroutine(
            object : Continuation<Value> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Value>) {
                    completion = result
                }
            },
        )
        return checkNotNull(completion).getOrThrow()
    }

    private data class Fixture(
        val text: String,
        val snapshot: SourceSnapshot,
        val region: SourceSelector,
        val entities: List<SourceEntity>,
    )
}
