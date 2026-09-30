package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.source.contract.Containment
import io.github.amichne.kast.source.contract.DeclarationKind
import io.github.amichne.kast.source.contract.DeclarationVisibility
import io.github.amichne.kast.source.contract.EntityFilter
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.SourceEntity
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceEntityTraversalState
import io.github.amichne.kast.source.contract.SourceEntityTraversalTask
import io.github.amichne.kast.source.contract.SourceReadEntityCursor
import io.github.amichne.kast.source.contract.SourceReadLimitation
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.VisibilitySelection

internal enum class SourceEntityCollectionAdmission {
    ACCEPTING,
    STOPPED,
}

/** The existing ordered page owner, fed incrementally within a single read attempt. */
internal class IntellijSourceEntityPageCollector(
    private val selection: EntitySelection.Matching,
    private val cursor: IntellijSourceEntityCursor,
    private val limit: SourceEntityLimit,
) {
    private sealed interface State {
        data object Collecting : State

        data object PageFull : State

        data class Stopped(val page: IntellijSourceEntityPage) : State
    }

    private var state: State = State.Collecting
    private val page = ArrayList<SourceEntity>(limit.value)
    private var previous: SourceEntity? = null
    var lookahead: SourceEntity? = null
        private set

    val admission: SourceEntityCollectionAdmission
        get() =
            when (state) {
                State.Collecting -> SourceEntityCollectionAdmission.ACCEPTING
                State.PageFull -> SourceEntityCollectionAdmission.STOPPED
                is State.Stopped -> SourceEntityCollectionAdmission.STOPPED
            }

    /** Keeps structural traversal outside deferred compiler projection. */
    fun projectDeclaration(kind: DeclarationKind, depth: Int = 0, project: () -> Unit) {
        if (selection.containment != Containment.DESCENDANTS && depth != 0) return
        if (selection.filters.any { it is EntityFilter.Declarations && kind in it.kinds.values }) project()
    }

    fun offer(entity: SourceEntity): SourceEntityCollectionAdmission {
        if (state != State.Collecting) return SourceEntityCollectionAdmission.STOPPED
        val prior = previous
        if (prior != null && SOURCE_ENTITY_ORDER.compare(prior, entity) > 0) {
            return stop(IntellijSourceEntityPage.Rejected(IntellijSourceReadRejection.CONTRACT_VIOLATION))
        }
        previous = entity
        if (!entity.matches(selection)) return SourceEntityCollectionAdmission.ACCEPTING
        if (page.size == limit.value) {
            lookahead = entity
            state = State.PageFull
            return SourceEntityCollectionAdmission.STOPPED
        }
        page += entity
        return SourceEntityCollectionAdmission.ACCEPTING
    }

    fun finish(traversal: SourceEntityTraversalState? = null): IntellijSourceEntityPage =
        when (val current = state) {
            State.Collecting -> {
                val completed =
                    if (traversal == null)
                        IntellijSourceEntityPage.Complete(page.toList(), cursor.startOrdinal + page.size, emptySet())
                    else prefix(traversal)
                stop(completed)
                completed
            }
            State.PageFull -> {
                val retained = traversal ?: lookaheadPosition(checkNotNull(lookahead))
                val result = prefix(retained)
                stop(result)
                result
            }
            is State.Stopped -> current.page
        }

    private fun prefix(traversal: SourceEntityTraversalState): IntellijSourceEntityPage.Prefix =
        IntellijSourceEntityPage.Prefix(
            page.toList(),
            cursor.startOrdinal + page.size + if (lookahead == null) 0 else 1,
            traversal.limitations +
                if (lookahead == null) emptySet() else setOf(SourceReadLimitation.ENTITY_LIMIT_REACHED),
            cursor.startOrdinal + page.size,
            traversal,
        )

    private fun lookaheadPosition(entity: SourceEntity): SourceEntityTraversalState {
        var region: SourceSelector = entity.parentSelector
        while (true) region =
            when (val selector = region) {
                is SourceSelector.RootRegion -> break
                is SourceSelector.NestedRegion -> selector.parent
                is SourceSelector.Entity -> selector.parent
            }
        val revision = (cursor.evidence as? SourceReadEntityCursor.Continued)?.proof?.traversal?.revision ?: 0L
        return (SourceEntityTraversalState.create(
                region,
                revision + 1,
                listOf(SourceEntityTraversalTask.ProvenEntity(entity)),
            ) as Refinement.Refined)
            .value
    }

    private fun stop(result: IntellijSourceEntityPage): SourceEntityCollectionAdmission {
        state = State.Stopped(result)
        return SourceEntityCollectionAdmission.STOPPED
    }
}

private val SOURCE_ENTITY_ORDER: Comparator<SourceEntity> = Comparator { left, right ->
    compareValues(left.selector.range.startInclusive, right.selector.range.startInclusive).takeIf { it != 0 }
        ?: compareValues(right.selector.range.endExclusive, left.selector.range.endExclusive).takeIf { it != 0 }
        ?: compareValues(left.selector.kind.ordinal, right.selector.kind.ordinal).takeIf { it != 0 }
        ?: compareValues(left.selector.name.sortValue(), right.selector.name.sortValue())
}

private fun SourceEntity.matches(selection: EntitySelection.Matching): Boolean {
    when (selection.containment) {
        Containment.SELF -> if (nestingDepth.value != 0 || selector.range != parentSelector.range) return false
        Containment.DIRECT -> if (nestingDepth.value != 0) return false
        Containment.DESCENDANTS -> Unit
    }
    return selection.filters.any { filter ->
        when (filter) {
            is EntityFilter.Declarations ->
                this is SourceEntity.Declaration && kind in filter.kinds.values && visibility.matches(filter.visibility)
            EntityFilter.Parameters -> this is SourceEntity.ValueParameter
            EntityFilter.Calls -> this is SourceEntity.Call
            EntityFilter.References -> this is SourceEntity.Reference
        }
    }
}

private fun DeclarationVisibility.matches(selection: VisibilitySelection): Boolean =
    when (selection) {
        VisibilitySelection.Any -> true
        is VisibilitySelection.Exact -> this in selection.values
    }

private fun io.github.amichne.kast.source.contract.SourceEntityName.sortValue(): String =
    when (this) {
        io.github.amichne.kast.source.contract.SourceEntityName.Unavailable -> ""
        is io.github.amichne.kast.source.contract.SourceEntityName.Present -> value
    }
