package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationProviderElementClass
import io.github.amichne.kast.relation.contract.RelationProviderItemDescriptor
import io.github.amichne.kast.relation.contract.RelationProviderLocator
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallElement

/** Detached native sites and exact offset restoration; every live value belongs to the current read attempt. */
internal class IntellijRelationLocators(
    private val project: Project,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val cancellationCheck: () -> Unit,
    private val observation: IntellijReadObservation,
) {
    fun reference(reference: PsiReference): Refinement<RelationProviderLocator.Reference, RelationLimitation> =
        site(reference.element, reference.rangeInElement, "reference:${reference.javaClass.name}").map { site ->
            RelationProviderLocator.Reference(site.file, site.range, site.descriptor)
        }

    fun definition(provider: PsiElement): Refinement<RelationProviderLocator.Definition, RelationLimitation> {
        val providerFile =
            when (val detached = detachedFile(provider)) {
                is Refinement.Refined -> detached.value
                is Refinement.Rejected -> return detached
            }
        val normalized = normalizeRelationDefinition(provider)
        val element = if (normalized is IntellijRelationDefinition.Supported) normalized.declaration else provider
        return site(element, element.relativeWholeRange(), "definition:${element.javaClass.name}").flatMap { site ->
            element.detachedClass().map { elementClass ->
                when (normalized) {
                    is IntellijRelationDefinition.Supported ->
                        RelationProviderLocator.Definition.Normalized(
                            site.file,
                            site.range,
                            site.descriptor,
                            elementClass,
                            providerFile,
                        )
                    IntellijRelationDefinition.Unsupported ->
                        RelationProviderLocator.Definition.Unsupported(
                            site.file,
                            site.range,
                            site.descriptor,
                            elementClass,
                            providerFile,
                        )
                }
            }
        }
    }

    fun callee(candidate: CalleeProviderItem): Refinement<RelationProviderLocator.Callee, RelationLimitation> =
        when (candidate) {
            is CalleeProviderItem.Reference ->
                site(
                        candidate.reference.element,
                        candidate.reference.rangeInElement,
                        "callee-reference:${candidate.reference.javaClass.name}",
                    )
                    .map { site -> RelationProviderLocator.Callee.Reference(site.file, site.range, site.descriptor) }
            is CalleeProviderItem.Unresolved ->
                site(candidate.call, candidate.call.relativeWholeRange(), "unresolved-call").flatMap { site ->
                    candidate.call.detachedClass().map { elementClass ->
                        RelationProviderLocator.Callee.UnresolvedCall(
                            site.file,
                            site.range,
                            site.descriptor,
                            elementClass,
                        )
                    }
                }
        }

    fun restoreReference(locator: RelationProviderLocator.Reference): Refinement<PsiReference, RelationLimitation> =
        restoreReferenceSite(locator, "reference")

    fun restoreDefinition(
        locator: RelationProviderLocator.Definition
    ): Refinement<IntellijRestoredDefinition, RelationLimitation> =
        restoreElement(locator, locator.elementClass) { element ->
                providerItemDescriptor(element, element.relativeWholeRange(), "definition:${element.javaClass.name}")
            }
            .flatMap { element ->
                when (locator) {
                    is RelationProviderLocator.Definition.Normalized ->
                        when (val declaration = element as? PsiNamedElement) {
                            null -> unavailable()
                            else -> Refinement.Refined(IntellijRestoredDefinition.Normalized(declaration))
                        }
                    is RelationProviderLocator.Definition.Unsupported ->
                        Refinement.Refined(IntellijRestoredDefinition.Unsupported(element))
                }
            }

    fun restoreCallee(locator: RelationProviderLocator.Callee): Refinement<CalleeProviderItem, RelationLimitation> =
        when (locator) {
            is RelationProviderLocator.Callee.Reference ->
                when (val restored = restoreReferenceSite(locator, "callee-reference")) {
                    is Refinement.Rejected -> restored
                    is Refinement.Refined ->
                        when (val reference = restored.value as? KtReference) {
                            null -> unavailable()
                            else ->
                                Refinement.Refined(
                                    CalleeProviderItem.Reference(reference, reference.element.nearestDeclaration())
                                )
                        }
                }
            is RelationProviderLocator.Callee.UnresolvedCall ->
                when (
                    val restored =
                        restoreElement(locator, locator.elementClass) { element ->
                            providerItemDescriptor(element, element.relativeWholeRange(), "unresolved-call")
                        }
                ) {
                    is Refinement.Rejected -> restored
                    is Refinement.Refined ->
                        when (val call = restored.value as? KtCallElement) {
                            null -> unavailable()
                            else -> Refinement.Refined(CalleeProviderItem.Unresolved(call, call.nearestDeclaration()))
                        }
                }
        }

    private fun site(
        element: PsiElement,
        relative: TextRange,
        discriminator: String,
    ): Refinement<DetachedSite, RelationLimitation> {
        val identity =
            when (val detached = detachedFile(element)) {
                is Refinement.Refined -> detached.value
                is Refinement.Rejected -> return detached
            }
        val range =
            when (
                val parsed =
                    ExactDeclarationTextRange.parse(
                        element.textRange.startOffset + relative.startOffset,
                        element.textRange.startOffset + relative.endOffset,
                    )
            ) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return unavailable()
            }
        return Refinement.Refined(
            DetachedSite(identity, range, providerItemDescriptor(element, relative, discriminator))
        )
    }

    private fun restoreReferenceSite(
        locator: RelationProviderLocator,
        discriminator: String,
    ): Refinement<PsiReference, RelationLimitation> {
        val leaf = leaf(locator) ?: return unavailable()
        for (element in generateSequence(leaf) { it.parent }) {
            cancellationCheck()
            for (reference in element.references) {
                if (
                    providerItemDescriptor(
                        reference.element,
                        reference.rangeInElement,
                        "$discriminator:${reference.javaClass.name}",
                    ) == locator.descriptor
                ) {
                    observation.count(IntellijReadCounter.RELATION_LOCATORS_RESTORED)
                    return Refinement.Refined(reference)
                }
            }
        }
        return unavailable()
    }

    private fun restoreElement(
        locator: RelationProviderLocator,
        elementClass: RelationProviderElementClass,
        descriptor: (PsiElement) -> RelationProviderItemDescriptor,
    ): Refinement<PsiElement, RelationLimitation> {
        val leaf = leaf(locator) ?: return unavailable()
        for (element in generateSequence(leaf) { it.parent }) {
            cancellationCheck()
            if (element.matchesNativeSite(locator, elementClass) && descriptor(element) == locator.descriptor) {
                observation.count(IntellijReadCounter.RELATION_LOCATORS_RESTORED)
                return Refinement.Refined(element)
            }
        }
        return unavailable()
    }

    private fun PsiElement.matchesNativeSite(
        locator: RelationProviderLocator,
        elementClass: RelationProviderElementClass,
    ): Boolean =
        javaClass.name == elementClass.value &&
            textRange.startOffset == locator.range.startInclusive &&
            textRange.endOffset == locator.range.endExclusive

    private fun leaf(locator: RelationProviderLocator): PsiElement? {
        val providerFile =
            when (locator) {
                is RelationProviderLocator.Definition -> locator.providerFile.restoreFile()
                else -> locator.file.restoreFile()
            } ?: return null
        if (!providerFile.isValid || !scope.nativeScope.contains(providerFile)) return null
        val file = locator.file.restoreFile() ?: return null
        if (!file.isValid) return null
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        return psi.findElementAt(locator.range.startInclusive)
    }

    private fun SymbolDiscoveryFileIdentity.restoreFile(): VirtualFile? =
        when (this) {
            is SymbolDiscoveryFileIdentity.Workspace -> LocalFileSystem.getInstance().findFileByPath(path.value)
            is SymbolDiscoveryFileIdentity.External -> VirtualFileManager.getInstance().findFileByUrl(url.value)
        }

    private data class DetachedSite(
        val file: SymbolDiscoveryFileIdentity,
        val range: ExactDeclarationTextRange,
        val descriptor: RelationProviderItemDescriptor,
    )

    private fun PsiElement.detachedClass(): Refinement<RelationProviderElementClass, RelationLimitation> =
        when (val parsed = RelationProviderElementClass.parse(javaClass.name)) {
            is Refinement.Refined -> parsed
            is Refinement.Rejected -> unavailable()
        }

    private fun PsiElement.relativeWholeRange(): TextRange = textRange.shiftLeft(textRange.startOffset)

    private fun unavailable() = Refinement.Rejected(RelationLimitation.PROVIDER_INCOMPLETE)

    private fun detachedFile(element: PsiElement): Refinement<SymbolDiscoveryFileIdentity, RelationLimitation> {
        val file = element.containingFile?.virtualFile ?: return unavailable()
        return when (val detached = projection.detach(file)) {
            is IntellijDetachedRelationFile.Found -> Refinement.Refined(detached.identity)
            IntellijDetachedRelationFile.Unsupported -> unavailable()
        }
    }

    private fun <Value, Result> Refinement<Value, RelationLimitation>.map(
        transform: (Value) -> Result
    ): Refinement<Result, RelationLimitation> =
        when (this) {
            is Refinement.Refined -> Refinement.Refined(transform(value))
            is Refinement.Rejected -> this
        }

    private fun <Value, Result> Refinement<Value, RelationLimitation>.flatMap(
        transform: (Value) -> Refinement<Result, RelationLimitation>
    ): Refinement<Result, RelationLimitation> =
        when (this) {
            is Refinement.Refined -> transform(value)
            is Refinement.Rejected -> this
        }
}

internal sealed interface IntellijRestoredDefinition {
    val element: PsiElement

    data class Normalized(val declaration: PsiNamedElement) : IntellijRestoredDefinition {
        override val element: PsiElement
            get() = declaration
    }

    data class Unsupported(override val element: PsiElement) : IntellijRestoredDefinition
}
