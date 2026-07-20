@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.idea.backend.references

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.api.continuation.ContinuationOwnedState
import io.github.amichne.kast.api.continuation.ContinuationProjection
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.result.ContainingSymbolEvidence
import io.github.amichne.kast.api.contract.result.ReferenceOccurrence
import io.github.amichne.kast.api.contract.result.RelationshipSearchLimitation
import io.github.amichne.kast.api.contract.SearchScopeKind
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.SymbolVisibility
import io.github.amichne.kast.shared.analysis.visibility
import io.github.amichne.kast.indexstore.api.reference.ExactReferenceTarget
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import java.nio.file.Files
import java.nio.file.Path
import io.github.amichne.kast.idea.*
import io.github.amichne.kast.idea.edit.*
import io.github.amichne.kast.idea.backend.references.*
import io.github.amichne.kast.idea.backend.relationships.*
import io.github.amichne.kast.idea.backend.diagnostics.*
import io.github.amichne.kast.idea.backend.mutation.*
import io.github.amichne.kast.idea.backend.workspace.*
import io.github.amichne.kast.idea.backend.*

internal data class ReferenceResolvedTarget(
    val pointer: SmartPsiElementPointer<PsiElement>,
    val targetFqName: String?,
    val exactTarget: ExactReferenceTarget?,
    val declaration: Symbol?,
    val visibility: SymbolVisibility,
)

internal data class ReferenceScopePlan(
    val searchScope: GlobalSearchScope,
    val scopeKind: SearchScopeKind,
)

internal data class ReferenceSearchPlan(
    val target: SmartPsiElementPointer<PsiElement>,
    val targetFqName: String?,
    val exactTarget: ExactReferenceTarget?,
    val declaration: Symbol?,
    val visibility: SymbolVisibility,
    val searchScope: GlobalSearchScope,
    val scopeKind: SearchScopeKind,
)

internal data class ReferenceSearchOutcome(
    val source: ReferenceSearchSource,
    val references: List<ReferenceOccurrence>,
    val consumedEvidence: Int,
    val observedEvidence: Int,
    val nextPosition: ReferenceContinuationPosition?,
    val candidateFileCount: Int,
    val searchedFileCount: Int,
    val completion: ReferenceSearchCompletion,
) {
    val hasMoreEvidence: Boolean
        get() = nextPosition != null
}

internal data class ReferenceQueryIdentity(
    val filePath: String,
    val offset: Int,
    val fqName: String?,
    val kind: String?,
    val containingType: String?,
    val includeDeclaration: Boolean,
    val includeUsageSiteScope: Boolean,
    val maxResults: Int,
) {
    companion object {
        fun from(query: ParsedReferencesQuery): ReferenceQueryIdentity = ReferenceQueryIdentity(
            filePath = query.position.filePath.value,
            offset = query.position.offset.value,
            fqName = query.selector?.fqName,
            kind = query.selector?.kind?.name,
            containingType = query.selector?.containingType,
            includeDeclaration = query.includeDeclaration,
            includeUsageSiteScope = query.includeUsageSiteScope,
            maxResults = query.maxResults.value,
        )
    }
}

internal class ReferenceContinuationState(
    val plan: ReferenceSearchPlan,
    returnedBefore: Int,
    position: ReferenceContinuationPosition,
) : ContinuationOwnedState() {
    var returnedBefore: Int = returnedBefore
        private set
    var position: ReferenceContinuationPosition = position
        private set

    fun advanceTo(returnedBefore: Int, position: ReferenceContinuationPosition) {
        require(returnedBefore >= this.returnedBefore) { "Reference continuation cardinality must not regress" }
        this.returnedBefore = returnedBefore
        this.position = position
    }

    fun close() {
        (position as? ReferenceContinuationPosition.Idea)?.traversal?.close()
    }
}

internal data class ReferenceContinuationProjection(
    val plan: ReferenceSearchPlan,
    val outcome: ReferenceSearchOutcome,
    val knownCount: Int,
) : ContinuationProjection()

internal sealed interface ReferenceContinuationPosition {
    data class Index(
        val offset: io.github.amichne.kast.api.contract.NonNegativeInt,
        val generation: SourceIndexGeneration,
        val candidateFilePaths: Set<String>,
        val searchedFilePaths: Set<String>,
    ) : ReferenceContinuationPosition

    data class Idea(
        val traversal: IdeaReferenceTraversal,
        val pending: ReferenceOccurrence?,
        val generation: Long,
        val candidateFilePaths: MutableSet<String>,
        val searchedFilePaths: MutableSet<String>,
        val seenLocations: MutableSet<ReferenceLocationKey>,
    ) : ReferenceContinuationPosition
}

internal class IdeaReferenceTraversal(
    searchRoots: List<Path>,
    private val observer: ReferenceTraversalObserver,
) : AutoCloseable {
    private var closed: Boolean = false
    val paths = WorkspacePathTraversal(searchRoots)
    var currentFile: VirtualFile? = null
    var nextOffset: Int = 0
    var nextReferenceIndex: Int = 0
    var exhausted: Boolean = false

    override fun close() {
        if (!closed) {
            closed = true
            paths.close()
            observer.closed()
        }
    }
}

internal class WorkspacePathTraversal(searchRoots: List<Path>) : Iterator<Path>, AutoCloseable {
    private val roots = searchRoots.iterator()
    private var currentStream: java.util.stream.Stream<Path>? = null
    private var currentPaths: Iterator<Path>? = null

    override fun hasNext(): Boolean {
        while (true) {
            if (currentPaths?.hasNext() == true) return true
            currentStream?.close()
            currentStream = null
            currentPaths = null
            if (!roots.hasNext()) return false
            currentStream = Files.walk(roots.next())
            currentPaths = currentStream?.iterator()
        }
    }

    override fun next(): Path {
        if (!hasNext()) throw NoSuchElementException("No source path remains")
        return requireNotNull(currentPaths).next()
    }

    override fun close() {
        currentStream?.close()
        currentStream = null
        currentPaths = null
    }
}

internal data class ReferenceLocationKey(
    val filePath: String,
    val startOffset: Int,
    val endOffset: Int,
)

internal fun Location.key(): ReferenceLocationKey = ReferenceLocationKey(
    filePath = filePath,
    startOffset = startOffset,
    endOffset = endOffset,
)

internal val referenceOccurrenceOrder = compareBy<ReferenceOccurrence>(
    { it.location.filePath },
    { it.location.startOffset },
    { it.location.endOffset },
    {
        when (val evidence = it.containingSymbol) {
            is ContainingSymbolEvidence.Known -> evidence.symbol.fqName
            ContainingSymbolEvidence.TopLevel -> ""
            is ContainingSymbolEvidence.Unavailable -> evidence.reason.name
        }
    },
)

internal enum class ReferenceSearchSource {
    INDEX,
    IDEA,
}

internal sealed interface ReferenceSearchCompletion {
    val exhaustive: Boolean
    val partialReason: String?

    object Exhaustive : ReferenceSearchCompletion {
        override val exhaustive: Boolean = true
        override val partialReason: String? = null
    }

    data class Partial(
        val reason: ReferencePartialReason,
    ) : ReferenceSearchCompletion {
        override val exhaustive: Boolean = false
        override val partialReason: String = reason.name.lowercase()
    }
}

internal enum class ReferencePartialReason {
    REQUEST_BUDGET_EXHAUSTED {
        override val limitation: RelationshipSearchLimitation = RelationshipSearchLimitation.TIMED_OUT
    },
    PSI_RESOLUTION_FAILED {
        override val limitation: RelationshipSearchLimitation = RelationshipSearchLimitation.BACKEND_INCOMPLETE
    },
    COMPILER_PROVIDER_LIMIT_EXHAUSTED {
        override val limitation: RelationshipSearchLimitation =
            RelationshipSearchLimitation.CANDIDATE_BUDGET_REACHED
    },
    FILE_BUDGET_EXHAUSTED {
        override val limitation: RelationshipSearchLimitation = RelationshipSearchLimitation.TIMED_OUT
    },
    TARGET_INVALIDATED {
        override val limitation: RelationshipSearchLimitation = RelationshipSearchLimitation.GENERATION_CHANGED
    },
    INDEX_LOCATION_UNRESOLVED {
        override val limitation: RelationshipSearchLimitation = RelationshipSearchLimitation.INDEX_STALE
    },
    ;

    abstract val limitation: RelationshipSearchLimitation
}

internal class ReferenceSearchBudget(
    private val requestStartedNanos: Long,
    private val requestBudgetNanos: Long,
    private val perFileBudgetNanos: Long,
    private val clock: ReferenceSearchClock,
) {
    fun requestExhausted(): Boolean =
        clock.nanoTime() - requestStartedNanos >= requestBudgetNanos

    fun fileStarted(): Long = clock.nanoTime()

    fun fileExhausted(fileStartedNanos: Long): Boolean =
        clock.nanoTime() - fileStartedNanos >= perFileBudgetNanos

    companion object {
        fun start(
            limits: ServerLimits,
            clock: ReferenceSearchClock,
        ): ReferenceSearchBudget = ReferenceSearchBudget(
            requestStartedNanos = clock.nanoTime(),
            requestBudgetNanos = limits.requestTimeoutMillis.toBudgetNanos(),
            perFileBudgetNanos = limits.perFileScanBudgetMillis.toBudgetNanos(),
            clock = clock,
        )
    }
}

internal fun Long.toBudgetNanos(): Long {
    val millis = coerceAtLeast(1L)
    return if (millis > Long.MAX_VALUE / NANOS_PER_MILLI) {
        Long.MAX_VALUE
    } else {
        millis * NANOS_PER_MILLI
    }
}

internal fun PsiElement.referenceAtOffset(offset: Int): PsiReference? =
    generateSequence(this as PsiElement?) { element -> element.parent }
        .flatMap { element -> element.references.asSequence() }
        .filter { reference -> reference.absoluteTextRange().containsOffset(offset) }
        .minByOrNull { reference -> reference.absoluteTextRange().length }

internal fun referencesAtLeaf(
    file: PsiFile,
    leaf: PsiElement,
    leafStart: Int,
): List<PsiReference> = buildList {
    file.findReferenceAt(leafStart)?.let(::add)
    generateSequence(leaf as PsiElement?) { element -> element.parent }
        .takeWhile { element -> element != file }
        .forEach { element -> addAll(element.references) }
}.distinctBy { reference ->
    ReferenceProbeKey(
        elementStartOffset = reference.element.textRange.startOffset,
        rangeStartOffset = reference.rangeInElement.startOffset,
        rangeEndOffset = reference.rangeInElement.endOffset,
        implementationName = reference.javaClass.name,
    )
}

internal data class ReferenceProbeKey(
    val elementStartOffset: Int,
    val rangeStartOffset: Int,
    val rangeEndOffset: Int,
    val implementationName: String,
)

internal fun PsiReference.absoluteTextRange(): TextRange =
    rangeInElement.shiftRight(element.textRange.startOffset)

internal const val NANOS_PER_MILLI = 1_000_000L
internal const val READ_ACTION_BATCH_SIZE = 50
internal const val REFERENCE_DISCOVERY_PATH_LIMIT = 64

internal inline fun <S, T, R : Any> collectInShortReadActions(
    crossinline collectSnapshot: () -> Pair<S, Collection<T>>,
    crossinline processItem: (T) -> R?,
    crossinline runInitialReadAction: (() -> Pair<S, Collection<T>>) -> Pair<S, Collection<T>>,
    crossinline runBatchReadAction: (() -> List<R>) -> List<R>,
): Pair<S, List<R>> {
    val (snapshot, items) = runInitialReadAction { collectSnapshot() }
    val itemList = items.toList()
    val results = mutableListOf<R>()
    for (batch in itemList.chunked(READ_ACTION_BATCH_SIZE)) {
        val batchResults = runBatchReadAction {
            batch.mapNotNull { item -> processItem(item) }
        }
        results.addAll(batchResults)
    }
    return snapshot to results
}
