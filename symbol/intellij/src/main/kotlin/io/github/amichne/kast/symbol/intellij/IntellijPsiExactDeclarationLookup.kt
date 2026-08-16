package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiQualifiedNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationEvidence
import io.github.amichne.kast.symbol.contract.ExactDeclarationSelector
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceOffset
import io.github.amichne.kast.symbol.contract.SymbolSelector
import java.nio.file.Path

internal data class IntellijExactDeclarationLookupKey(
    val file: SymbolDiscoveryFileIdentity,
    val offset: SymbolDiscoverySourceOffset,
    val name: SymbolDiscoveryCandidateName,
)

internal enum class IntellijExactDeclarationLookupRejection {
    STALE_LOCATION,
    OUTSIDE_SCOPE,
    AMBIGUOUS_DECLARATION,
    UNSUPPORTED_DECLARATION,
}

internal sealed interface IntellijExactDeclarationLookupResult {
    data class Found(
        val evidence: ExactDeclarationEvidence,
    ) : IntellijExactDeclarationLookupResult

    data class Rejected(
        val reason: IntellijExactDeclarationLookupRejection,
    ) : IntellijExactDeclarationLookupResult
}

internal sealed interface IntellijLiveExactDeclarationLookupResult {
    data class Found(
        val declaration: PsiNamedElement,
        val evidence: ExactDeclarationEvidence,
    ) : IntellijLiveExactDeclarationLookupResult

    data class Rejected(
        val reason: IntellijExactDeclarationLookupRejection,
    ) : IntellijLiveExactDeclarationLookupResult
}

internal fun interface IntellijExactDeclarationLookup {
    /**
     * Proof transition:
     * CompiledIntellijSearchScope + IntellijExactDeclarationLookupKey to
     * IntellijExactDeclarationLookupResult.
     *
     * Establishes one scope-contained live declaration with detached exact native evidence, or a
     * closed stale, out-of-scope, ambiguous, or unsupported state. Live files and PSI must remain
     * inside this request-local call.
     */
    fun find(
        compiledScope: CompiledIntellijSearchScope,
        key: IntellijExactDeclarationLookupKey,
    ): IntellijExactDeclarationLookupResult
}

internal class IntellijPsiExactDeclarationLookup(
    private val project: Project,
) : IntellijExactDeclarationLookup {
    /**
     * Proof transition:
     * CompiledIntellijSearchScope + IntellijExactDeclarationLookupKey to
     * IntellijExactDeclarationLookupResult.
     *
     * Establishes exactly one valid, scope-contained [PsiNamedElement] at the retained source
     * offset whose name also matches, and detaches its complete evidence. Stale files/elements,
     * scope exclusion, multiple matching declaration ancestors, and unsupported PSI are closed
     * [IntellijExactDeclarationLookupRejection] values. Live IntelliJ objects remain local.
     */
    override fun find(
        compiledScope: CompiledIntellijSearchScope,
        key: IntellijExactDeclarationLookupKey,
    ): IntellijExactDeclarationLookupResult =
        when (val live = findLive(compiledScope, key)) {
            is IntellijLiveExactDeclarationLookupResult.Found ->
                IntellijExactDeclarationLookupResult.Found(live.evidence)
            is IntellijLiveExactDeclarationLookupResult.Rejected ->
                rejected(live.reason)
        }

    /**
     * Proof transition:
     * CompiledIntellijSearchScope + IntellijExactDeclarationLookupKey to
     * IntellijLiveExactDeclarationLookupResult.
     *
     * Establishes exactly one request-local live declaration plus its detached evidence, or the
     * same closed lookup rejection as [find]. The live declaration must be consumed within the
     * current IntelliJ read and may never cross an adapter boundary.
     */
    internal fun findLive(
        compiledScope: CompiledIntellijSearchScope,
        key: IntellijExactDeclarationLookupKey,
    ): IntellijLiveExactDeclarationLookupResult {
        val file = when (val resolution = key.file.resolveVirtualFile()) {
            is IntellijExactVirtualFileResolution.Found -> resolution.file
            IntellijExactVirtualFileResolution.Stale ->
                return liveRejected(IntellijExactDeclarationLookupRejection.STALE_LOCATION)
        }
        if (!compiledScope.nativeScope.contains(file)) {
            return liveRejected(IntellijExactDeclarationLookupRejection.OUTSIDE_SCOPE)
        }
        val psiFile = PsiManager.getInstance(project).findFile(file)
                      ?: return liveRejected(IntellijExactDeclarationLookupRejection.UNSUPPORTED_DECLARATION)
        val leaf = psiFile.findElementAt(key.offset.value)
                   ?: return liveRejected(IntellijExactDeclarationLookupRejection.STALE_LOCATION)
        val matches = mutableListOf<Pair<PsiNamedElement, ExactDeclarationEvidence>>()
        var element: PsiElement? = leaf
        while (element != null) {
            val named = element as? PsiNamedElement
            if (
                named != null &&
                named.name == key.name.value &&
                element.textRange.startOffset == key.offset.value
            ) {
                val evidence = ExactDeclarationEvidence.fromBoundary(
                    file = key.file,
                    rawStartInclusive = element.textRange.startOffset,
                    rawEndExclusive = element.textRange.endOffset,
                    rawName = named.name.orEmpty(),
                    rawQualifiedIdentity = (named as? PsiQualifiedNamedElement)?.qualifiedName,
                    rawRuntimeType = named.javaClass.name,
                )
                when (evidence) {
                    is Refinement.Refined -> matches += named to evidence.value
                    is Refinement.Rejected ->
                        return liveRejected(
                            IntellijExactDeclarationLookupRejection.UNSUPPORTED_DECLARATION,
                        )
                }
            }
            element = element.parent
        }
        val declarations = matches.distinct()
        return when (declarations.size) {
            0 -> liveRejected(IntellijExactDeclarationLookupRejection.UNSUPPORTED_DECLARATION)
            1 -> {
                val (declaration, evidence) = declarations.single()
                IntellijLiveExactDeclarationLookupResult.Found(declaration, evidence)
            }
            else -> liveRejected(IntellijExactDeclarationLookupRejection.AMBIGUOUS_DECLARATION)
        }
    }
}

private sealed interface IntellijExactVirtualFileResolution {
    data class Found(
        val file: VirtualFile,
    ) : IntellijExactVirtualFileResolution

    data object Stale : IntellijExactVirtualFileResolution
}

/**
 * Proof transition:
 * SymbolDiscoveryFileIdentity to IntellijExactVirtualFileResolution.
 *
 * Establishes a current valid IntelliJ virtual file for the detached workspace path or external
 * URL, or the closed stale state. Raw path/URL extraction and the live file remain inside this
 * request-local IntelliJ lookup boundary.
 */
private fun SymbolDiscoveryFileIdentity.resolveVirtualFile(): IntellijExactVirtualFileResolution {
    val manager = VirtualFileManager.getInstance()
    val file = when (this) {
        is SymbolDiscoveryFileIdentity.Workspace ->
            manager.findFileByNioPath(Path.of(path.value))
        is SymbolDiscoveryFileIdentity.External ->
            manager.findFileByUrl(url.value)
    }
    return if (file?.isValid == true) {
        IntellijExactVirtualFileResolution.Found(file)
    } else {
        IntellijExactVirtualFileResolution.Stale
    }
}

/**
 * Proof transition:
 * SymbolDiscoverySelection to IntellijExactDeclarationLookupKey.
 *
 * Establishes a lookup key containing only the declaration file, refined source offset, and name
 * retained by the batch-owned selection. Raw values may be extracted only by
 * [IntellijExactDeclarationLookup.find].
 */
internal fun SymbolDiscoverySelection.lookupKey(): IntellijExactDeclarationLookupKey {
    val location = candidate.location as SymbolDiscoveryCandidateLocation.Declaration
    return IntellijExactDeclarationLookupKey(
        file = location.file,
        offset = location.offset,
        name = candidate.name,
    )
}

/**
 * Proof transition:
 * ExactDeclarationSelector to IntellijExactDeclarationLookupKey.
 *
 * Establishes a lookup key from the selector's exact file, invariant-preserving range start, and
 * name. The range invariant makes offset refinement total; raw values may be extracted only by
 * [IntellijExactDeclarationLookup.find].
 */
internal fun ExactDeclarationSelector.lookupKey(): IntellijExactDeclarationLookupKey {
    val offset = when (
        val parsed = SymbolDiscoverySourceOffset.parse(range.startInclusive)
    ) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("exact selector contains an invalid source offset")
    }
    return IntellijExactDeclarationLookupKey(
        file = file,
        offset = offset,
        name = name,
    )
}

/**
 * Proof transition: `SymbolSelector -> IntellijExactDeclarationLookupKey`.
 *
 * Establishes a request-local lookup key from the exact selector's retained file, invariant-safe
 * range start, and name. Raw offset extraction is permitted only at this native lookup boundary.
 */
internal fun SymbolSelector.lookupKey(): IntellijExactDeclarationLookupKey {
    val offset = when (val parsed = SymbolDiscoverySourceOffset.parse(range.startInclusive)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("symbol selector contains an invalid source offset")
    }
    return IntellijExactDeclarationLookupKey(
        file = file,
        offset = offset,
        name = name,
    )
}

private fun rejected(
    reason: IntellijExactDeclarationLookupRejection,
): IntellijExactDeclarationLookupResult.Rejected =
    IntellijExactDeclarationLookupResult.Rejected(reason)

private fun liveRejected(
    reason: IntellijExactDeclarationLookupRejection,
): IntellijLiveExactDeclarationLookupResult.Rejected =
    IntellijLiveExactDeclarationLookupResult.Rejected(reason)

internal fun IntellijExactDeclarationLookupRejection.toPublicRejection():
    IntellijExactSelectorRejection = when (this) {
    IntellijExactDeclarationLookupRejection.STALE_LOCATION ->
        IntellijExactSelectorRejection.STALE_LOCATION
    IntellijExactDeclarationLookupRejection.OUTSIDE_SCOPE ->
        IntellijExactSelectorRejection.OUTSIDE_SCOPE
    IntellijExactDeclarationLookupRejection.AMBIGUOUS_DECLARATION ->
        IntellijExactSelectorRejection.AMBIGUOUS_DECLARATION
    IntellijExactDeclarationLookupRejection.UNSUPPORTED_DECLARATION ->
        IntellijExactSelectorRejection.UNSUPPORTED_DECLARATION
}
