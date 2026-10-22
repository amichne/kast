package io.github.amichne.kast.source.intellij

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.amichne.kast.source.contract.BodyKind
import io.github.amichne.kast.source.contract.EnclosingRegionKind
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile

internal data class NativeSourceRegion(
    val range: TextRange,
    val kind: SourceRegionKind,
    val element: PsiElement,
)

internal fun selectSourceRegion(
    element: PsiElement?,
    selector: SourceSelector,
    selection: RegionSelection,
): NativeSourceRegion? =
    when (selection) {
        RegionSelection.Anchor -> {
            val range =
                TextRange(
                    selector.range.startInclusive.value,
                    selector.range.endExclusive.value,
                )
            element?.let { element ->
                NativeSourceRegion(
                    range,
                    selector.nativeRegionKind(),
                    element.exactRegionElement(range),
                )
            }
        }
        RegionSelection.File ->
            element?.containingFile?.let { file ->
                NativeSourceRegion(
                    TextRange(0, selector.snapshot.length.value),
                    SourceRegionKind.FILE,
                    file,
                )
            }
        is RegionSelection.Body -> selectBody(element, selector, selection.kind)
        is RegionSelection.Enclosing -> selectEnclosing(element, selector, selection.kind)
    }

private fun selectBody(element: PsiElement?, selector: SourceSelector, kind: BodyKind): NativeSourceRegion? =
    when (kind) {
        BodyKind.CALLABLE ->
            element.nearestCallable(selector)?.bodyExpression?.let {
                NativeSourceRegion(it.textRange, SourceRegionKind.CALLABLE_BODY, it)
            }
        BodyKind.CLASS ->
            element.nearestClass(selector)?.body?.let {
                NativeSourceRegion(it.textRange, SourceRegionKind.CLASS_BODY, it)
            }
    }

private fun selectEnclosing(
    element: PsiElement?,
    selector: SourceSelector,
    kind: EnclosingRegionKind,
): NativeSourceRegion? =
    when (kind) {
        EnclosingRegionKind.DECLARATION ->
            element.strictAncestors(selector).filterIsInstance<KtDeclaration>().firstOrNull()?.let {
                NativeSourceRegion(it.textRange, SourceRegionKind.DECLARATION, it)
            }
        EnclosingRegionKind.CALLABLE_BODY ->
            element
                .strictAncestors(selector)
                .filterIsInstance<KtExpression>()
                .firstOrNull { expression ->
                    (expression.parent as? KtDeclarationWithBody)?.bodyExpression === expression
                }
                ?.let { NativeSourceRegion(it.textRange, SourceRegionKind.CALLABLE_BODY, it) }
        EnclosingRegionKind.CLASS_BODY ->
            element.strictAncestors(selector).filterIsInstance<KtClassBody>().firstOrNull()?.let {
                NativeSourceRegion(it.textRange, SourceRegionKind.CLASS_BODY, it)
            }
    }

private fun PsiElement.exactRegionElement(range: TextRange): PsiElement =
    fileAncestors().firstOrNull { element -> element.textRange == range } ?: this

private fun PsiElement?.nearestCallable(selector: SourceSelector): KtDeclarationWithBody? =
    fileAncestors().filterIsInstance<KtDeclarationWithBody>().firstOrNull { it.containsSelection(selector) }

private fun PsiElement?.nearestClass(selector: SourceSelector): KtClassOrObject? =
    fileAncestors().filterIsInstance<KtClassOrObject>().firstOrNull { it.containsSelection(selector) }

private fun PsiElement?.strictAncestors(selector: SourceSelector): Sequence<PsiElement> =
    fileAncestors().filter { element ->
        element.containsSelection(selector) &&
            (element.textRange.startOffset < selector.range.startInclusive.value ||
                element.textRange.endOffset > selector.range.endExclusive.value)
    }

private fun PsiElement.containsSelection(selector: SourceSelector): Boolean =
    textRange.startOffset <= selector.range.startInclusive.value &&
        textRange.endOffset >= selector.range.endExclusive.value

private fun PsiElement?.fileAncestors(): Sequence<PsiElement> =
    generateSequence(this) { element -> if (element is KtFile) null else element.parent }
