package io.github.amichne.kast.source.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.Collections

enum class SourceEntityTraversalFailure {
    INVALID_REVISION,
    EMPTY_FRONTIER,
    OUTSIDE_REGION,
    INVALID_ELEMENT_DESCRIPTOR,
}

/** Exact provider element family, detached from the request-local PSI implementation. */
@JvmInline
value class SourceEntityElementDescriptor private constructor(val value: String) {
    companion object {
        fun parse(value: String): Refinement<SourceEntityElementDescriptor, SourceEntityTraversalFailure> =
            if (value.isBlank() || value.length > MAX_ELEMENT_DESCRIPTOR_CHARACTERS || value.any(Char::isISOControl))
                Refinement.Rejected(SourceEntityTraversalFailure.INVALID_ELEMENT_DESCRIPTOR)
            else Refinement.Refined(SourceEntityElementDescriptor(value))
    }
}

data class SourceEntityElementLocator(val range: SourceRange, val descriptor: SourceEntityElementDescriptor)

data class SourceEntityStructuralParent(val selector: SourceSelector, val depth: SourceNestingDepth)

enum class SourceEntitySiblingPolicy {
    SINGLE,
    REMAINING,
}

/** Scheduling precedes projection so a bounded read cannot lose descendants or a same-node second entity. */
sealed interface SourceEntityTraversalTask {
    data class Visit(
        val element: SourceEntityElementLocator,
        val parent: SourceEntityStructuralParent,
        val classPropertyParent: SourceEntityStructuralParent?,
        val siblings: SourceEntitySiblingPolicy,
    ) : SourceEntityTraversalTask

    data class ValueParameter(
        val element: SourceEntityElementLocator,
        val parent: SourceEntityStructuralParent,
    ) : SourceEntityTraversalTask

    /** A compiler-confirmed lookahead is carried forward as its original proof, never projected twice. */
    data class ProvenEntity(val entity: SourceEntity) : SourceEntityTraversalTask
}

/** Bounded detached unfinished source work; its revision counts consumed structural and proven-fact tasks. */
class SourceEntityTraversalState
private constructor(
    val region: SourceSelector,
    val revision: Long,
    val pending: List<SourceEntityTraversalTask>,
    val limitations: Set<SourceReadLimitation>,
) {
    val retainedBytes: Long =
        SOURCE_STRUCTURAL_NODE_BYTES +
            pending.sumOf { task ->
                when (task) {
                    is SourceEntityTraversalTask.Visit ->
                        task.element.descriptor.value.length * SOURCE_RETAINED_TEXT_UNIT_BYTES +
                            SOURCE_STRUCTURAL_NODE_BYTES +
                            task.parent.selector.detachedRetentionBytes() +
                            (task.classPropertyParent?.selector?.detachedRetentionBytes() ?: 0L)
                    is SourceEntityTraversalTask.ValueParameter ->
                        task.element.descriptor.value.length * SOURCE_RETAINED_TEXT_UNIT_BYTES +
                            SOURCE_STRUCTURAL_NODE_BYTES +
                            task.parent.selector.detachedRetentionBytes()
                    is SourceEntityTraversalTask.ProvenEntity -> task.entity.detachedRetentionBytes()
                }
            }

    fun samePosition(other: SourceEntityTraversalState): Boolean =
        revision == other.revision &&
            pending == other.pending &&
            limitations == other.limitations &&
            region.fingerprint == other.region.fingerprint &&
            region.snapshot == other.region.snapshot

    companion object {
        fun create(
            region: SourceSelector,
            revision: Long,
            pending: List<SourceEntityTraversalTask>,
            limitations: Set<SourceReadLimitation> = emptySet(),
        ): Refinement<SourceEntityTraversalState, SourceEntityTraversalFailure> {
            if (revision <= 0L) return Refinement.Rejected(SourceEntityTraversalFailure.INVALID_REVISION)
            if (pending.isEmpty()) return Refinement.Rejected(SourceEntityTraversalFailure.EMPTY_FRONTIER)
            if (pending.any { !it.admittedBy(region) })
                return Refinement.Rejected(SourceEntityTraversalFailure.OUTSIDE_REGION)
            return Refinement.Refined(
                SourceEntityTraversalState(
                    region,
                    revision,
                    Collections.unmodifiableList(pending.toList()),
                    Collections.unmodifiableSet(limitations.toSet()),
                )
            )
        }
    }
}

private fun SourceEntity.detachedRetentionBytes(): Long =
    selector.detachedRetentionBytes() +
        SOURCE_STRUCTURAL_NODE_BYTES +
        when (this) {
            is SourceEntity.Declaration ->
                when (val identity = semanticIdentity) {
                    is DeclarationSemanticIdentity.Candidate ->
                        SourceReadAnchor.Candidate(identity.selector).detachedRetentionBytes()
                }
            is SourceEntity.ValueParameter -> 0L
            is SourceEntity.Call -> calleeSelector.detachedRetentionBytes() + target.detachedRetentionBytes()
            is SourceEntity.Reference -> target.detachedRetentionBytes()
        }

private fun SourceEntityTarget.detachedRetentionBytes(): Long =
    when (this) {
        is SourceEntityTarget.Candidate -> SourceReadAnchor.Candidate(selector).detachedRetentionBytes()
        is SourceEntityTarget.Local -> selector.detachedRetentionBytes()
        is SourceEntityTarget.Unresolved -> SOURCE_FIELD_STRUCTURE_BYTES
    }

private fun SourceEntityTraversalTask.admittedBy(region: SourceSelector): Boolean =
    when (this) {
        is SourceEntityTraversalTask.Visit ->
            element.range.overlaps(region) && parent.selector.range.containedBy(region) && classParentAdmitted(region)
        is SourceEntityTraversalTask.ValueParameter ->
            element.range.containedBy(region) && parent.selector.range.containedBy(region)
        is SourceEntityTraversalTask.ProvenEntity -> entity.selector.range.containedBy(region)
    }

private fun SourceEntityTraversalTask.Visit.classParentAdmitted(region: SourceSelector): Boolean =
    classPropertyParent?.selector?.range?.containedBy(region) ?: true

private fun SourceRange.containedBy(region: SourceSelector): Boolean =
    snapshot == region.snapshot &&
        startInclusive >= region.range.startInclusive &&
        endExclusive <= region.range.endExclusive

private fun SourceRange.overlaps(region: SourceSelector): Boolean =
    snapshot == region.snapshot &&
        startInclusive <= region.range.endExclusive &&
        endExclusive >= region.range.startInclusive

private const val MAX_ELEMENT_DESCRIPTOR_CHARACTERS = 512
