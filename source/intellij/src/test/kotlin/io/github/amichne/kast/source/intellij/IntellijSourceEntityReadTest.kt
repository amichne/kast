package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.source.contract.Containment
import io.github.amichne.kast.source.contract.DeclarationKind
import io.github.amichne.kast.source.contract.DeclarationKindSelection
import io.github.amichne.kast.source.contract.DeclarationSemanticIdentity
import io.github.amichne.kast.source.contract.DeclarationVisibility
import io.github.amichne.kast.source.contract.EntityFilter
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.NonEmptySourceRange
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceEntity
import io.github.amichne.kast.source.contract.SourceEntityKind
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceEntityName
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
import io.github.amichne.kast.source.contract.VisibilitySelection
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

class IntellijSourceEntityReadTest {
    @Test
    fun `declaration filters use semantic visibility and direct descendant depth`() {
        val fixture = fixture()
        val port = port(fixture)

        val direct = read(
            port,
            fixture,
            matching(
                Containment.DIRECT,
                declarations(setOf(DeclarationKind.FUNCTION), setOf(DeclarationVisibility.PUBLIC)),
            ),
        ) as SourceReadResult.Complete
        assertEquals(listOf("direct"), direct.entities.names())
        assertEquals(listOf(0), direct.entities.map { it.nestingDepth.value })

        val descendants = read(
            port,
            fixture,
            matching(
                Containment.DESCENDANTS,
                declarations(setOf(DeclarationKind.FUNCTION), setOf(DeclarationVisibility.PUBLIC)),
            ),
        ) as SourceReadResult.Complete
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
        val result = read(
            port(fixture),
            fixture,
            matching(Containment.DESCENDANTS, EntityFilter.Parameters),
        ) as SourceReadResult.Complete

        assertEquals(listOf("p", "q", "item"), result.entities.names())
        assertEquals(listOf(1, 2, 2), result.entities.map { it.nestingDepth.value })
        result.entities.forEach { assertInstanceOf(SourceEntity.ValueParameter::class.java, it) }

        val properties = read(
            port(fixture),
            fixture,
            matching(
                Containment.DESCENDANTS,
                declarations(setOf(DeclarationKind.PROPERTY), setOf(DeclarationVisibility.PUBLIC)),
            ),
        ) as SourceReadResult.Complete
        assertEquals(listOf("item"), properties.entities.names())
        assertEquals(
            result.entities.last().selector.range,
            properties.entities.single().selector.range,
        )
        assertTrue(
            result.entities.last().selector.fingerprint !=
                properties.entities.single().selector.fingerprint,
        )
    }

    @Test
    fun `complete empty entities are a trustworthy structural negative`() {
        val fixture = fixture()
        val result = read(
            port(fixture),
            fixture,
            matching(
                Containment.DESCENDANTS,
                declarations(setOf(DeclarationKind.TYPE_ALIAS), null),
            ),
        ) as SourceReadResult.Complete

        assertTrue(result.entities.isEmpty())
    }

    @Test
    fun `entity bound returns exact ordered prefix and continuation re-enters final page`() {
        val fixture = fixture()
        val port = port(fixture)
        val selection = matching(
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
        val continuation =
            (first.qualification.continuation as SourceReadContinuationState.Available).continuation

        val final = read(
            port,
            fixture,
            selection,
            limit = 1,
            page = SourceReadPage.Continue(continuation),
        ) as SourceReadResult.Complete
        assertEquals(listOf("nested"), final.entities.names())

        assertEquals(
            SourceReadResult.Rejected(
                io.github.amichne.kast.source.contract.SourceReadRejection.CONTRACT_VIOLATION,
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
    fun `supported call filters return a complete empty structural negative`() {
        val fixture = fixture()
        val result = read(
            port(fixture),
            fixture,
            matching(Containment.DESCENDANTS, EntityFilter.Calls),
        ) as SourceReadResult.Complete

        assertTrue(result.entities.isEmpty())
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
            context(fixture.snapshot),
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

    private fun matching(
        containment: Containment,
        vararg filters: EntityFilter,
    ): EntitySelection.Matching = EntitySelection.matching(containment, filters.toList()).refined()

    private fun declarations(
        kinds: Set<DeclarationKind>,
        visibility: Set<DeclarationVisibility>?,
    ): EntityFilter.Declarations = EntityFilter.Declarations(
        DeclarationKindSelection.from(kinds).refined(),
        visibility?.let { VisibilitySelection.exact(it).refined() } ?: VisibilitySelection.Any,
    )

    private fun fixture(): Fixture {
        val text = """
            public fun direct(p: Int) {
                fun nested(q: Int) = q
            }
            private val hidden = 1
            class Holder(val item: Int)
        """.trimIndent() + "\n"
        val snapshot = snapshot(text)
        val region = SourceSelector.issueRoot(range(snapshot, 0, text.length), SourceRegionKind.FILE)
        val directStart = text.indexOf("public fun direct")
        val directEnd = text.indexOf("\n}") + 2
        val direct = declarationAt(
            snapshot,
            region,
            directStart,
            "direct",
            DeclarationKind.FUNCTION,
            DeclarationVisibility.PUBLIC,
            0,
            range(snapshot, directStart, directEnd),
        )
        val directParameter = parameter(snapshot, direct.selector, text, "p", 1)
        val nestedStart = text.indexOf("fun nested")
        val nestedEnd = text.indexOf('\n', nestedStart)
        val nested = declarationAt(
            snapshot,
            direct.selector,
            nestedStart,
            "nested",
            DeclarationKind.FUNCTION,
            DeclarationVisibility.PUBLIC,
            1,
            range(snapshot, nestedStart, nestedEnd),
        )
        val nestedParameter = parameter(snapshot, nested.selector, text, "q", 2)
        val hiddenStart = text.indexOf("private val hidden")
        val hidden = declarationAt(
            snapshot,
            region,
            hiddenStart,
            "hidden",
            DeclarationKind.PROPERTY,
            DeclarationVisibility.PRIVATE,
            0,
            range(snapshot, hiddenStart, text.indexOf('\n', hiddenStart)),
        )
        val holderStart = text.indexOf("class Holder")
        val holderEnd = text.indexOf('\n', holderStart)
        val holder = declarationAt(
            snapshot,
            region,
            holderStart,
            "Holder",
            DeclarationKind.CLASSLIKE,
            DeclarationVisibility.PUBLIC,
            0,
            range(snapshot, holderStart, holderEnd),
        )
        val constructorStart = text.indexOf('(', holderStart)
        val constructorEnd = text.indexOf(')', constructorStart) + 1
        val constructor = declarationAt(
            snapshot,
            holder.selector,
            constructorStart,
            "Holder",
            DeclarationKind.CONSTRUCTOR,
            DeclarationVisibility.PUBLIC,
            1,
            range(snapshot, constructorStart, constructorEnd),
        )
        val itemStart = text.indexOf("item")
        val itemRange = range(snapshot, itemStart, itemStart + "item".length)
        val itemProperty = declarationAt(
            snapshot,
            holder.selector,
            itemStart,
            "item",
            DeclarationKind.PROPERTY,
            DeclarationVisibility.PUBLIC,
            1,
            itemRange,
        )
        val itemParameter = parameterAt(
            constructor.selector,
            itemRange,
            "item",
            2,
        )
        return Fixture(
            text,
            snapshot,
            region,
            listOf(
                direct,
                directParameter,
                nested,
                nestedParameter,
                hidden,
                holder,
                constructor,
                itemProperty,
                itemParameter,
            ),
        )
    }

    private fun declarationAt(
        snapshot: SourceSnapshot,
        parent: SourceSelector,
        start: Int,
        name: String,
        kind: DeclarationKind,
        visibility: DeclarationVisibility,
        depth: Int,
        entityRange: SourceRange = range(snapshot, start, start + name.length),
    ): SourceEntity.Declaration {
        val selector = SourceSelector.issueEntity(
            parent,
            NonEmptySourceRange.create(entityRange).refined(),
            kind.entityKind(),
            SourceEntityName.present(name).refined(),
        ).refined()
        val candidate = candidate(snapshot, name, start, kind)
        return SourceEntity.Declaration.create(
            selector,
            SourceNestingDepth.parse(depth).refined(),
            kind,
            visibility,
            DeclarationSemanticIdentity.Candidate(candidate),
        ).refined()
    }

    private fun parameter(
        snapshot: SourceSnapshot,
        parent: SourceSelector,
        text: String,
        name: String,
        depth: Int,
    ): SourceEntity.ValueParameter {
        val start = text.indexOf("$name:")
        return parameterAt(parent, range(snapshot, start, start + name.length), name, depth)
    }

    private fun parameterAt(
        parent: SourceSelector,
        entityRange: SourceRange,
        name: String,
        depth: Int,
    ): SourceEntity.ValueParameter {
        val selector = SourceSelector.issueEntity(
            parent,
            NonEmptySourceRange.create(entityRange).refined(),
            SourceEntityKind.VALUE_PARAMETER,
            SourceEntityName.present(name).refined(),
        ).refined()
        return SourceEntity.ValueParameter.create(
            selector,
            SourceNestingDepth.parse(depth).refined(),
        ).refined()
    }

    private fun candidate(
        snapshot: SourceSnapshot,
        name: String,
        offset: Int,
        kind: DeclarationKind,
    ): CandidateSelector.Declaration {
        val candidate = SymbolDiscoveryCandidate.fromBoundary(
            if (kind == DeclarationKind.CLASSLIKE) {
                SymbolDiscoveryKind.CLASS
            } else {
                SymbolDiscoveryKind.SYMBOL
            },
            name,
            snapshot.lease,
            Path.of(snapshot.file.path.value),
            Path.of(snapshot.file.path.value).toUri().toString(),
            offset,
        ).refined()
        val scope = SymbolSearchScope.ExactFile(
            snapshot.file.path,
            SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
            SymbolGeneratedSourcePolicy.INCLUDE,
        )
        return CandidateSelector.declaration(
            SymbolDiscoverySelection.restore(snapshot.lease, scope, candidate).refined(),
        ).refined()
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

    private fun context(snapshot: SourceSnapshot): SourceReadContext =
        SourceReadContext(snapshot.lease, snapshot.sourceState)

    private fun List<SourceEntity>.names(): List<String> = map { entity ->
        (entity.selector.name as SourceEntityName.Present).value
    }

    private fun DeclarationKind.entityKind(): SourceEntityKind = when (this) {
        DeclarationKind.CLASSLIKE -> SourceEntityKind.DECLARATION_CLASSLIKE
        DeclarationKind.CONSTRUCTOR -> SourceEntityKind.DECLARATION_CONSTRUCTOR
        DeclarationKind.FUNCTION -> SourceEntityKind.DECLARATION_FUNCTION
        DeclarationKind.PROPERTY -> SourceEntityKind.DECLARATION_PROPERTY
        DeclarationKind.TYPE_ALIAS -> SourceEntityKind.DECLARATION_TYPE_ALIAS
    }

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
