package io.github.amichne.kast.source.intellij

import com.intellij.psi.PsiElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.source.contract.SourceEntityElementDescriptor
import io.github.amichne.kast.source.contract.SourceEntityElementLocator
import io.github.amichne.kast.source.contract.SourceEntitySiblingPolicy
import io.github.amichne.kast.source.contract.SourceEntityStructuralParent
import io.github.amichne.kast.source.contract.SourceEntityTraversalState
import io.github.amichne.kast.source.contract.SourceEntityTraversalTask
import io.github.amichne.kast.source.contract.SourceNestingDepth
import io.github.amichne.kast.source.contract.SourceRange
import io.github.amichne.kast.source.contract.SourceReadEntityCursor
import io.github.amichne.kast.source.contract.SourceReadLimitation
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.Utf16CodeUnitOffset

/** The request-local driver restores exact next nodes; the retained frontier contains no native object. */
internal class IntellijSourceTraversal(
    private val document: LiveSourceDocument,
    private val region: SourceSelector,
    private val attempt: IntellijSourceEntityAttempt,
) {
    private val previous = (attempt.cursor.evidence as? SourceReadEntityCursor.Continued)?.proof?.traversal
    private val pending = ArrayDeque<SourceEntityTraversalTask>(previous?.pending.orEmpty())
    private var revision = previous?.revision ?: 0L
    val limitations = previous?.limitations.orEmpty().toMutableSet()

    val hasWork: Boolean
        get() = pending.isNotEmpty()

    val resumed: Boolean
        get() = previous != null

    fun next(): SourceEntityTraversalTask {
        revision += 1L
        return pending.removeFirst()
    }

    fun restore(locator: SourceEntityElementLocator): PsiElement? {
        val range = locator.range
        val offset = range.startInclusive.value
        val seed =
            document.psiFile.findElementAt(offset)
                ?: document.psiFile.findElementAt((offset - 1).coerceAtLeast(0))
                ?: document.psiFile
        return generateSequence(seed) { it.parent }
            .firstOrNull {
                it.textRange.startOffset == range.startInclusive.value &&
                    it.textRange.endOffset == range.endExclusive.value &&
                    it.javaClass.name == locator.descriptor.value
            }
    }

    fun visit(
        element: PsiElement,
        parent: NativeStructuralParent,
        classParent: NativeStructuralParent?,
        siblings: SourceEntitySiblingPolicy,
    ) {
        var selected: PsiElement? = element
        while (
            selected != null &&
                (selected.textRange.isEmpty ||
                    !selected.textRange.intersects(
                        com.intellij.openapi.util.TextRange(
                            region.range.startInclusive.value,
                            region.range.endExclusive.value,
                        )
                    ))
        ) {
            com.intellij.openapi.progress.ProgressManager.checkCanceled()
            selected = if (siblings == SourceEntitySiblingPolicy.REMAINING) selected.nextSibling else null
        }
        if (selected == null) return
        pending.addFirst(
            SourceEntityTraversalTask.Visit(
                locator(selected),
                parent.detached(),
                classParent?.detached(),
                siblings,
            )
        )
    }

    fun children(element: PsiElement, parent: NativeStructuralParent, classParent: NativeStructuralParent?) {
        element.firstChild?.let { visit(it, parent, classParent, SourceEntitySiblingPolicy.REMAINING) }
    }

    fun parameter(element: PsiElement, parent: NativeStructuralParent) {
        pending.addFirst(SourceEntityTraversalTask.ValueParameter(locator(element), parent.detached()))
    }

    fun finish(additional: SourceReadLimitation?): IntellijSourceEntityPage {
        if (
            additional != null &&
                additional !in
                    setOf(
                        SourceReadLimitation.WORK_LIMIT_REACHED,
                        SourceReadLimitation.TIME_LIMIT_REACHED,
                    )
        )
            limitations += additional
        attempt.lookahead?.let { pending.addFirst(SourceEntityTraversalTask.ProvenEntity(it)) }
        val next =
            if (pending.isEmpty() || revision == 0L) null
            else
                when (
                    val created = SourceEntityTraversalState.create(region, revision, pending.toList(), limitations)
                ) {
                    is Refinement.Refined -> created.value
                    is Refinement.Rejected ->
                        return IntellijSourceEntityPage.Rejected(IntellijSourceReadRejection.CONTRACT_VIOLATION)
                }
        val page = attempt.finish(next)
        val qualified = limitations.fold(page) { current, limitation -> current.withLimitation(limitation) }
        return if (additional == null) qualified else qualified.withLimitation(additional)
    }

    private fun locator(element: PsiElement): SourceEntityElementLocator {
        val range =
            SourceRange.create(
                    document.snapshot,
                    Utf16CodeUnitOffset.parse(element.textRange.startOffset).value(),
                    Utf16CodeUnitOffset.parse(element.textRange.endOffset).value(),
                )
                .value()
        return SourceEntityElementLocator(range, SourceEntityElementDescriptor.parse(element.javaClass.name).value())
    }

    private fun NativeStructuralParent.detached() =
        SourceEntityStructuralParent(selector, SourceNestingDepth.parse(depth).value())

    private fun <T> Refinement<T, *>.value(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Request-local PSI traversal violated its admitted source range")
        }
}

internal fun SourceEntityStructuralParent.nativeParent() = NativeStructuralParent(selector, depth.value)
