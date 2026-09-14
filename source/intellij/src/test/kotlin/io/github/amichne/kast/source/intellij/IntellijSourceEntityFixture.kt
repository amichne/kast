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
import io.github.amichne.kast.source.contract.SourceEntity
import io.github.amichne.kast.source.contract.SourceEntityKind
import io.github.amichne.kast.source.contract.SourceEntityName
import io.github.amichne.kast.source.contract.SourceNestingDepth
import io.github.amichne.kast.source.contract.SourceRange
import io.github.amichne.kast.source.contract.SourceReadContext
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.source.contract.SourceTextIdentity
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

internal object IntellijSourceEntityFixture {
    fun matching(
        containment: Containment,
        vararg filters: EntityFilter,
    ): EntitySelection.Matching = EntitySelection.matching(containment, filters.toList()).refined()

    fun declarations(
        kinds: Set<DeclarationKind>,
        visibility: Set<DeclarationVisibility>?,
    ): EntityFilter.Declarations =
        EntityFilter.Declarations(
            DeclarationKindSelection.from(kinds).refined(),
            visibility?.let { VisibilitySelection.exact(it).refined() } ?: VisibilitySelection.Any,
        )

    fun fixture(): Fixture {
        val text =
            """
            public fun direct(p: Int) {
                fun nested(q: Int) = q
            }
            private val hidden = 1
            class Holder(val item: Int)
            """
                .trimIndent() + "\n"
        val snapshot = snapshot(text)
        val region = SourceSelector.issueRoot(range(snapshot, 0, text.length), SourceRegionKind.FILE)
        return Fixture(
            text,
            snapshot,
            region,
            functionEntities(text, snapshot, region) +
                hiddenEntity(text, snapshot, region) +
                holderEntities(text, snapshot, region),
        )
    }

    private fun functionEntities(text: String, snapshot: SourceSnapshot, region: SourceSelector): List<SourceEntity> {
        val directStart = text.indexOf("public fun direct")
        val directEnd = text.indexOf("\n}") + 2
        val direct =
            declarationAt(
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
        val nested =
            declarationAt(
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
        return listOf(direct, directParameter, nested, nestedParameter)
    }

    private fun hiddenEntity(text: String, snapshot: SourceSnapshot, region: SourceSelector): SourceEntity {
        val hiddenStart = text.indexOf("private val hidden")
        val hidden =
            declarationAt(
                snapshot,
                region,
                hiddenStart,
                "hidden",
                DeclarationKind.PROPERTY,
                DeclarationVisibility.PRIVATE,
                0,
                range(snapshot, hiddenStart, text.indexOf('\n', hiddenStart)),
            )
        return hidden
    }

    private fun holderEntities(text: String, snapshot: SourceSnapshot, region: SourceSelector): List<SourceEntity> {
        val holderStart = text.indexOf("class Holder")
        val holderEnd = text.indexOf('\n', holderStart)
        val holder =
            declarationAt(
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
        val constructor =
            declarationAt(
                snapshot,
                holder.selector,
                constructorStart,
                "Holder",
                DeclarationKind.CONSTRUCTOR,
                DeclarationVisibility.PUBLIC,
                1,
                range(snapshot, constructorStart, constructorEnd),
            )
        return listOf(holder, constructor) + constructorPropertyEntities(text, snapshot, holder, constructor)
    }

    private fun constructorPropertyEntities(
        text: String,
        snapshot: SourceSnapshot,
        holder: SourceEntity.Declaration,
        constructor: SourceEntity.Declaration,
    ): List<SourceEntity> {
        val itemStart = text.indexOf("item")
        val itemRange = range(snapshot, itemStart, itemStart + "item".length)
        val itemProperty =
            declarationAt(
                snapshot,
                holder.selector,
                itemStart,
                "item",
                DeclarationKind.PROPERTY,
                DeclarationVisibility.PUBLIC,
                1,
                itemRange,
            )
        val itemParameter =
            parameterAt(
                constructor.selector,
                itemRange,
                "item",
                2,
            )
        return listOf(itemProperty, itemParameter)
    }

    fun declarationAt(
        snapshot: SourceSnapshot,
        parent: SourceSelector,
        start: Int,
        name: String,
        kind: DeclarationKind,
        visibility: DeclarationVisibility,
        depth: Int,
        entityRange: SourceRange = range(snapshot, start, start + name.length),
    ): SourceEntity.Declaration {
        val selector =
            SourceSelector.issueEntity(
                    parent,
                    NonEmptySourceRange.create(entityRange).refined(),
                    kind.entityKind(),
                    SourceEntityName.present(name).refined(),
                )
                .refined()
        val candidate = candidate(snapshot, name, start, kind)
        return SourceEntity.Declaration.create(
                selector,
                SourceNestingDepth.parse(depth).refined(),
                kind,
                visibility,
                DeclarationSemanticIdentity.Candidate(candidate),
            )
            .refined()
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
        val selector =
            SourceSelector.issueEntity(
                    parent,
                    NonEmptySourceRange.create(entityRange).refined(),
                    SourceEntityKind.VALUE_PARAMETER,
                    SourceEntityName.present(name).refined(),
                )
                .refined()
        return SourceEntity.ValueParameter.create(
                selector,
                SourceNestingDepth.parse(depth).refined(),
            )
            .refined()
    }

    private fun candidate(
        snapshot: SourceSnapshot,
        name: String,
        offset: Int,
        kind: DeclarationKind,
    ): CandidateSelector.Declaration {
        val candidate =
            SymbolDiscoveryCandidate.fromBoundary(
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
                )
                .refined()
        val scope =
            SymbolSearchScope.ExactFile(
                snapshot.file.path,
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.INCLUDE,
            )
        return CandidateSelector.declaration(
                SymbolDiscoverySelection.restore(snapshot.lease, scope, candidate).refined()
            )
            .refined()
    }

    fun snapshot(text: String): SourceSnapshot {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()
        val path =
            CanonicalWorkspaceFilePath.fromCanonicalPath(
                    root,
                    Path.of("/workspace/src/Subject.kt"),
                )
                .refined()
        return SourceSnapshot.create(
            SemanticReadLease(root, EvidenceGeneration.parse(42).refined()),
            WorkspaceStateIdentity.parse("workspace-state-v1|source").refined(),
            SymbolDiscoveryFileIdentity.Workspace(path),
            SourceTextIdentity.fromNormalizedCommittedText(text),
            Utf16CodeUnitCount.parse(text.length).refined(),
        )
    }

    fun range(snapshot: SourceSnapshot, start: Int, end: Int): SourceRange =
        SourceRange.create(
                snapshot,
                Utf16CodeUnitOffset.parse(start).refined(),
                Utf16CodeUnitOffset.parse(end).refined(),
            )
            .refined()

    fun context(snapshot: SourceSnapshot): SourceReadContext = snapshot.context

    fun List<SourceEntity>.names(): List<String> = map { entity ->
        (entity.selector.name as SourceEntityName.Present).value
    }

    private fun DeclarationKind.entityKind(): SourceEntityKind =
        when (this) {
            DeclarationKind.CLASSLIKE -> SourceEntityKind.DECLARATION_CLASSLIKE
            DeclarationKind.CONSTRUCTOR -> SourceEntityKind.DECLARATION_CONSTRUCTOR
            DeclarationKind.FUNCTION -> SourceEntityKind.DECLARATION_FUNCTION
            DeclarationKind.PROPERTY -> SourceEntityKind.DECLARATION_PROPERTY
            DeclarationKind.TYPE_ALIAS -> SourceEntityKind.DECLARATION_TYPE_ALIAS
        }

    fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Expected refined value, got $failure")
        }

    data class Fixture(
        val text: String,
        val snapshot: SourceSnapshot,
        val region: SourceSelector,
        val entities: List<SourceEntity>,
    )
}
