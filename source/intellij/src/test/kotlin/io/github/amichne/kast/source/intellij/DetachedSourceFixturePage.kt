package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.SourceEntity
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceEntityTraversalState
import io.github.amichne.kast.source.contract.SourceEntityTraversalTask
import io.github.amichne.kast.source.contract.SourceReadEntityCursor
import io.github.amichne.kast.source.contract.SourceSelector

/** Explicit detached observations for adapter rule tests; no native or compiler authority is inferred. */
internal fun selectDetachedSourceFixture(
    source: Sequence<SourceEntity>,
    selection: EntitySelection,
    cursor: IntellijSourceEntityCursor,
    limit: SourceEntityLimit,
): IntellijSourceEntityPage {
    if (selection == EntitySelection.None) return IntellijSourceEntityPage.empty()
    val retained = (cursor.evidence as? SourceReadEntityCursor.Continued)?.proof?.traversal
    val pending = ArrayDeque(retained?.pending ?: source.map(SourceEntityTraversalTask::ProvenEntity).toList())
    val collector = IntellijSourceEntityPageCollector(selection as EntitySelection.Matching, cursor, limit)
    var revision = retained?.revision ?: 0L
    var parent: SourceSelector? = retained?.region
    while (pending.isNotEmpty() && collector.admission == SourceEntityCollectionAdmission.ACCEPTING) {
        val entity = (pending.removeFirst() as SourceEntityTraversalTask.ProvenEntity).entity
        revision += 1
        parent = parent ?: entity.parentSelector
        collector.offer(entity)
    }
    collector.lookahead?.let { pending.addFirst(SourceEntityTraversalTask.ProvenEntity(it)) }
    if (pending.isEmpty()) return collector.finish()
    var region = checkNotNull(parent)
    while (true) region =
        when (val current = region) {
            is SourceSelector.RootRegion -> break
            is SourceSelector.Entity -> current.parent
            is SourceSelector.NestedRegion -> current.parent
        }
    val traversal = (SourceEntityTraversalState.create(region, revision, pending.toList()) as Refinement.Refined).value
    return collector.finish(traversal)
}
