package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.source.contract.Containment
import io.github.amichne.kast.source.contract.DeclarationVisibility
import io.github.amichne.kast.source.contract.EntityFilter
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceEntity
import io.github.amichne.kast.source.contract.SourceEntityCount
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceRange
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadContext
import io.github.amichne.kast.source.contract.SourceReadContinuation
import io.github.amichne.kast.source.contract.SourceReadContinuationState
import io.github.amichne.kast.source.contract.SourceReadLimitation
import io.github.amichne.kast.source.contract.SourceReadPage
import io.github.amichne.kast.source.contract.SourceReadPort
import io.github.amichne.kast.source.contract.SourceReadQualification
import io.github.amichne.kast.source.contract.SourceReadRejection
import io.github.amichne.kast.source.contract.SourceReadRequest
import io.github.amichne.kast.source.contract.SourceReadResult
import io.github.amichne.kast.source.contract.SourceRegion
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.source.contract.SourceTextIdentity
import io.github.amichne.kast.source.contract.SourceTextProjection
import io.github.amichne.kast.source.contract.SourceTextWithheldReason
import io.github.amichne.kast.source.contract.TextProjection
import io.github.amichne.kast.source.contract.Utf16CodeUnitCount
import io.github.amichne.kast.source.contract.Utf16CodeUnitOffset
import io.github.amichne.kast.source.contract.VisibilitySelection
import io.github.amichne.kast.symbol.contract.CandidateSelector
import io.github.amichne.kast.symbol.contract.RevalidatedSymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolSelector
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

private const val MAX_INTELLIJ_SOURCE_ENTITY_WORK = 10_000
private const val MAX_INTELLIJ_SOURCE_CONTINUATIONS = 1_024

internal enum class IntellijSourceReadRejection {
    WORKSPACE_ROOT_MISMATCH,
    STALE_GENERATION,
    SOURCE_STATE_MISMATCH,
    SOURCE_UNAVAILABLE,
    DOCUMENT_DIRTY,
    PSI_DOCUMENT_UNCOMMITTED,
    OUTSIDE_SOURCE_SCOPE,
    ANCHOR_NOT_FOUND,
    AMBIGUOUS_ANCHOR,
    COMPILER_ANALYSIS_UNAVAILABLE,
    DECLARATION_MOVED_OR_CHANGED,
    REGION_ABSENT,
    UNSUPPORTED_REQUEST,
    PROVIDER_FAILURE,
    CONTRACT_VIOLATION,
}

internal sealed interface IntellijSourceReadAccessResult {
    data class Captured(
        val capture: IntellijCommittedSourceCapture,
    ) : IntellijSourceReadAccessResult

    data class Rejected(
        val reason: IntellijSourceReadRejection,
    ) : IntellijSourceReadAccessResult
}

internal fun interface IntellijSourceReadAccess {
    suspend fun capture(
        context: SourceReadContext,
        selector: SymbolSelector,
    ): IntellijSourceReadAccessResult
}

internal sealed interface IntellijSourceRegionAccessResult {
    data class Selected(
        val capture: IntellijSelectedSourceCapture,
    ) : IntellijSourceRegionAccessResult

    data class Rejected(
        val reason: IntellijSourceReadRejection,
    ) : IntellijSourceRegionAccessResult
}

internal fun interface IntellijSourceRegionAccess {
    suspend fun select(
        context: SourceReadContext,
        request: SourceReadRequest,
        cursor: IntellijSourceEntityCursor,
    ): IntellijSourceRegionAccessResult
}

/** Typed position in the deterministic matching-entity order. */
internal data class IntellijSourceEntityCursor internal constructor(
    val startOrdinal: Int,
    internal val expectedSnapshot: SourceSnapshot? = null,
    internal val expectedRegionFingerprint: String? = null,
) {
    init {
        require(startOrdinal >= 0)
        require((expectedSnapshot == null) == (expectedRegionFingerprint == null))
    }
}

/** One exact, bounded prefix of the supported structural entity stream. */
internal sealed interface IntellijSourceEntityPage {
    val entities: List<SourceEntity>
    val knownMinimumEntityCount: Int
    val limitations: Set<SourceReadLimitation>
    val nextOrdinal: Int?

    data class Complete internal constructor(
        override val entities: List<SourceEntity>,
        override val knownMinimumEntityCount: Int,
        override val limitations: Set<SourceReadLimitation>,
    ) : IntellijSourceEntityPage {
        override val nextOrdinal: Int? = null
    }

    data class Prefix internal constructor(
        override val entities: List<SourceEntity>,
        override val knownMinimumEntityCount: Int,
        override val limitations: Set<SourceReadLimitation>,
        override val nextOrdinal: Int,
    ) : IntellijSourceEntityPage

    data class Rejected internal constructor(
        val reason: IntellijSourceReadRejection,
    ) : IntellijSourceEntityPage {
        override val entities: List<SourceEntity> = emptyList()
        override val knownMinimumEntityCount: Int = 0
        override val limitations: Set<SourceReadLimitation> = emptySet()
        override val nextOrdinal: Int? = null
    }

    companion object {
        fun empty(): IntellijSourceEntityPage = Complete(emptyList(), 0, emptySet())

        /**
         * Consumes at most one exact page plus lookahead, while a separate fixed work ceiling
         * prevents an adversarial non-matching PSI stream from becoming unbounded.
         */
        fun select(
            source: Sequence<SourceEntity>,
            selection: EntitySelection,
            cursor: IntellijSourceEntityCursor,
            limit: SourceEntityLimit,
        ): IntellijSourceEntityPage {
            if (selection == EntitySelection.None) {
                return if (cursor.startOrdinal == 0) {
                    empty()
                } else {
                    Rejected(IntellijSourceReadRejection.CONTRACT_VIOLATION)
                }
            }
            selection as EntitySelection.Matching
            val baseLimitations = emptySet<SourceReadLimitation>()
            val iterator = source.iterator()
            val page = ArrayList<SourceEntity>(limit.value)
            var examined = 0
            var matched = 0
            var previous: SourceEntity? = null
            while (iterator.hasNext()) {
                if (examined == MAX_INTELLIJ_SOURCE_ENTITY_WORK) {
                    return Complete(
                        page.toList(),
                        cursor.startOrdinal + page.size,
                        baseLimitations + SourceReadLimitation.WORK_LIMIT_REACHED,
                    )
                }
                val entity = iterator.next()
                examined += 1
                if (previous != null && SOURCE_ENTITY_ORDER.compare(previous, entity) > 0) {
                    return Rejected(IntellijSourceReadRejection.CONTRACT_VIOLATION)
                }
                previous = entity
                if (!entity.matches(selection)) continue
                if (matched < cursor.startOrdinal) {
                    matched += 1
                    continue
                }
                if (page.size == limit.value) {
                    val next = cursor.startOrdinal + page.size
                    return Prefix(
                        page.toList(),
                        next + 1,
                        baseLimitations + SourceReadLimitation.ENTITY_LIMIT_REACHED,
                        next,
                    )
                }
                page += entity
                matched += 1
            }
            return Complete(
                page.toList(),
                cursor.startOrdinal + page.size,
                baseLimitations,
            )
        }
    }
}

/** One selected structural region detached from a single committed-document read. */
internal class IntellijSelectedSourceCapture private constructor(
    val snapshot: SourceSnapshot,
    val anchorSelector: SourceSelector,
    val regionSelector: SourceSelector,
    val normalizedDocumentText: String,
    val entityPage: IntellijSourceEntityPage,
) {
    companion object {
        fun create(
            snapshot: SourceSnapshot,
            anchorSelector: SourceSelector,
            regionSelector: SourceSelector,
            normalizedDocumentText: String,
            entityPage: IntellijSourceEntityPage = IntellijSourceEntityPage.empty(),
        ): Refinement<IntellijSelectedSourceCapture, IntellijSourceReadRejection> {
            if (
                anchorSelector.snapshot != snapshot ||
                regionSelector.snapshot != snapshot ||
                '\r' in normalizedDocumentText ||
                normalizedDocumentText.length != snapshot.length.value ||
                SourceTextIdentity.fromNormalizedCommittedText(normalizedDocumentText) !=
                snapshot.textIdentity
            ) {
                return Refinement.Rejected(IntellijSourceReadRejection.CONTRACT_VIOLATION)
            }
            val overlaps = anchorSelector.range.startInclusive <= regionSelector.range.endExclusive &&
                anchorSelector.range.endExclusive >= regionSelector.range.startInclusive
            if (!overlaps) {
                return Refinement.Rejected(IntellijSourceReadRejection.CONTRACT_VIOLATION)
            }
            if (entityPage is IntellijSourceEntityPage.Rejected) {
                return Refinement.Rejected(entityPage.reason)
            }
            if (
                entityPage.knownMinimumEntityCount < entityPage.entities.size ||
                entityPage.entities.any { entity ->
                    entity.selector.snapshot != snapshot ||
                        entity.selector.range.startInclusive < regionSelector.range.startInclusive ||
                        entity.selector.range.endExclusive > regionSelector.range.endExclusive
                } ||
                (
                    entityPage.nextOrdinal == null &&
                        SourceReadLimitation.ENTITY_LIMIT_REACHED in entityPage.limitations
                ) ||
                (
                    entityPage.nextOrdinal != null &&
                        SourceReadLimitation.ENTITY_LIMIT_REACHED !in entityPage.limitations
                )
            ) {
                return Refinement.Rejected(IntellijSourceReadRejection.CONTRACT_VIOLATION)
            }
            return Refinement.Refined(
                IntellijSelectedSourceCapture(
                    snapshot,
                    anchorSelector,
                    regionSelector,
                    normalizedDocumentText,
                    entityPage,
                ),
            )
        }
    }
}

/** Detached result of one K2-revalidated declaration in one committed IntelliJ document. */
internal class IntellijCommittedSourceCapture private constructor(
    val revalidated: RevalidatedSymbolSelector,
    val snapshot: SourceSnapshot,
    val declarationRange: SourceRange,
    val declarationText: String,
) {
    companion object {
        fun create(
            context: SourceReadContext,
            revalidated: RevalidatedSymbolSelector,
            normalizedDocumentText: String,
        ): Refinement<IntellijCommittedSourceCapture, IntellijSourceReadRejection> {
            if ('\r' in normalizedDocumentText) {
                return Refinement.Rejected(IntellijSourceReadRejection.CONTRACT_VIOLATION)
            }
            val selector = revalidated.selector
            if (selector.lease != context.lease) {
                return Refinement.Rejected(IntellijSourceReadRejection.STALE_GENERATION)
            }
            val file = selector.file as? SymbolDiscoveryFileIdentity.Workspace
                ?: return Refinement.Rejected(IntellijSourceReadRejection.OUTSIDE_SOURCE_SCOPE)
            if (selector.range.endExclusive > normalizedDocumentText.length) {
                return Refinement.Rejected(IntellijSourceReadRejection.DECLARATION_MOVED_OR_CHANGED)
            }
            val snapshot = SourceSnapshot.create(
                context.lease,
                context.sourceState,
                file,
                SourceTextIdentity.fromNormalizedCommittedText(normalizedDocumentText),
                when (val length = Utf16CodeUnitCount.parse(normalizedDocumentText.length)) {
                    is Refinement.Refined -> length.value
                    is Refinement.Rejected ->
                        return Refinement.Rejected(IntellijSourceReadRejection.CONTRACT_VIOLATION)
                },
            )
            val start = when (val parsed = Utf16CodeUnitOffset.parse(selector.range.startInclusive)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(IntellijSourceReadRejection.CONTRACT_VIOLATION)
            }
            val end = when (val parsed = Utf16CodeUnitOffset.parse(selector.range.endExclusive)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(IntellijSourceReadRejection.CONTRACT_VIOLATION)
            }
            val range = when (val admitted = SourceRange.create(snapshot, start, end)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected ->
                    return Refinement.Rejected(IntellijSourceReadRejection.CONTRACT_VIOLATION)
            }
            return Refinement.Refined(
                IntellijCommittedSourceCapture(
                    revalidated,
                    snapshot,
                    range,
                    normalizedDocumentText.substring(start.value, end.value),
                ),
            )
        }
    }
}

/** First native slice: exact symbol anchor to complete committed declaration text. */
internal class IntellijSourceReadPort(
    private val regions: IntellijSourceRegionAccess,
) : SourceReadPort {
    private val continuations = IntellijSourceContinuationAuthority()

    constructor(access: IntellijSourceReadAccess) : this(
        IntellijSourceRegionAccess { context, request, cursor ->
            val selector = (request.anchor as? SourceReadAnchor.Symbol)?.selector
                ?: return@IntellijSourceRegionAccess IntellijSourceRegionAccessResult.Rejected(
                    IntellijSourceReadRejection.UNSUPPORTED_REQUEST,
                )
            if (
                request.region != RegionSelection.Anchor ||
                request.entities != EntitySelection.None ||
                request.text != TextProjection.Complete ||
                request.page != SourceReadPage.First ||
                cursor.startOrdinal != 0
            ) {
                return@IntellijSourceRegionAccess IntellijSourceRegionAccessResult.Rejected(
                    IntellijSourceReadRejection.UNSUPPORTED_REQUEST,
                )
            }
            when (val result = access.capture(context, selector)) {
                is IntellijSourceReadAccessResult.Rejected ->
                    IntellijSourceRegionAccessResult.Rejected(result.reason)
                is IntellijSourceReadAccessResult.Captured -> {
                    val capture = result.capture
                    val sourceSelector = SourceSelector.issueRoot(
                        capture.declarationRange,
                        SourceRegionKind.DECLARATION,
                    )
                    when (
                        val selected = IntellijSelectedSourceCapture.create(
                            capture.snapshot,
                            sourceSelector,
                            sourceSelector,
                            capture.snapshotText(capture.declarationText),
                        )
                    ) {
                        is Refinement.Refined ->
                            IntellijSourceRegionAccessResult.Selected(selected.value)
                        is Refinement.Rejected ->
                            IntellijSourceRegionAccessResult.Rejected(selected.failure)
                    }
                }
            }
        },
    )

    override suspend fun read(
        context: SourceReadContext,
        request: SourceReadRequest,
    ): SourceReadResult {
        val cursor = when (val admission = continuations.admit(request)) {
            is IntellijSourceContinuationAdmission.Admitted -> admission.cursor
            IntellijSourceContinuationAdmission.Rejected ->
                return rejected(SourceReadRejection.CONTRACT_VIOLATION)
        }
        val capture = when (val result = regions.select(context, request, cursor)) {
            is IntellijSourceRegionAccessResult.Selected -> result.capture
            is IntellijSourceRegionAccessResult.Rejected ->
                return rejected(result.reason.publicReason())
        }
        if (!cursor.admits(capture)) {
            return rejected(SourceReadRejection.CONTRACT_VIOLATION)
        }
        val regionKind = capture.regionSelector.regionKind()
            ?: return rejected(SourceReadRejection.CONTRACT_VIOLATION)
        val region = when (
            val admitted = SourceRegion.create(regionKind, capture.regionSelector)
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejected(SourceReadRejection.CONTRACT_VIOLATION)
        }
        val text = when (val projection = request.text) {
            TextProjection.None -> SourceTextProjection.NotRequested
            TextProjection.Complete -> when (
                val admitted = SourceTextProjection.returned(
                    capture.regionSelector,
                    capture.normalizedDocumentText,
                )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return rejected(SourceReadRejection.CONTRACT_VIOLATION)
            }
            is TextProjection.Window -> {
                val windowRange = capture.windowRange(projection)
                    ?: return rejected(SourceReadRejection.CONTRACT_VIOLATION)
                val windowSelector = when (
                    val issued = SourceSelector.issueNested(
                        capture.regionSelector,
                        windowRange,
                        SourceRegionKind.WINDOW,
                    )
                ) {
                    is Refinement.Refined -> issued.value
                    is Refinement.Rejected ->
                        return rejected(SourceReadRejection.CONTRACT_VIOLATION)
                }
                when (
                    val admitted = SourceTextProjection.returned(
                        windowSelector,
                        capture.normalizedDocumentText,
                    )
                ) {
                    is Refinement.Refined -> admitted.value
                    is Refinement.Rejected ->
                        return rejected(SourceReadRejection.CONTRACT_VIOLATION)
                }
            }
        }
        val returnedBytes = when (text) {
            is SourceTextProjection.Returned ->
                text.text.toByteArray(StandardCharsets.UTF_8).size.toLong()
            SourceTextProjection.NotRequested,
            is SourceTextProjection.Withheld,
                -> 0L
        }
        val page = capture.entityPage
        val limitations = buildSet {
            addAll(page.limitations)
            if (returnedBytes > request.textByteLimit.value) {
                add(SourceReadLimitation.TEXT_BYTE_LIMIT_REACHED)
            }
        }
        val projectedText = if (returnedBytes > request.textByteLimit.value) {
            SourceTextProjection.Withheld(SourceTextWithheldReason.BYTE_LIMIT_REACHED)
        } else {
            text
        }
        if (limitations.isNotEmpty()) {
            val continuation = when (val next = page.nextOrdinal) {
                null -> SourceReadContinuationState.Unavailable
                else -> SourceReadContinuationState.Available(
                    continuations.issue(request, capture, next),
                )
            }
            val qualification = when (
                val admitted = SourceReadQualification.create(
                    knownMinimumEntityCount = sourceEntityCount(page.knownMinimumEntityCount),
                    limitations = limitations,
                    continuation = continuation,
                )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return rejected(SourceReadRejection.CONTRACT_VIOLATION)
            }
            return when (
                val admitted = SourceReadResult.Qualified.create(
                    capture.snapshot,
                    region,
                    page.entities,
                    projectedText,
                    qualification,
                )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> rejected(SourceReadRejection.CONTRACT_VIOLATION)
            }
        }
        return when (
            val admitted = SourceReadResult.Complete.create(
                capture.snapshot,
                region,
                page.entities,
                projectedText,
            )
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> rejected(SourceReadRejection.CONTRACT_VIOLATION)
        }
    }
}

private val SOURCE_ENTITY_ORDER: Comparator<SourceEntity> = Comparator { left, right ->
    compareValues(left.selector.range.startInclusive, right.selector.range.startInclusive)
        .takeIf { it != 0 }
        ?: compareValues(right.selector.range.endExclusive, left.selector.range.endExclusive)
            .takeIf { it != 0 }
        ?: compareValues(left.selector.kind.ordinal, right.selector.kind.ordinal)
            .takeIf { it != 0 }
        ?: compareValues(left.selector.name.sortValue(), right.selector.name.sortValue())
}

private fun SourceEntity.matches(selection: EntitySelection.Matching): Boolean {
    if (selection.containment == Containment.DIRECT && nestingDepth.value != 0) return false
    return selection.filters.any { filter ->
        when (filter) {
            is EntityFilter.Declarations -> this is SourceEntity.Declaration &&
                kind in filter.kinds.values && visibility.matches(filter.visibility)
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

private sealed interface IntellijSourceContinuationAdmission {
    data class Admitted(val cursor: IntellijSourceEntityCursor) :
        IntellijSourceContinuationAdmission

    data object Rejected : IntellijSourceContinuationAdmission
}

/** Runtime-local authority for opaque, exact-request-bound entity continuations. */
private class IntellijSourceContinuationAuthority {
    private val sequence = AtomicLong()
    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Entry>?,
        ): Boolean = size > MAX_INTELLIJ_SOURCE_CONTINUATIONS
    }

    fun admit(request: SourceReadRequest): IntellijSourceContinuationAdmission =
        when (val page = request.page) {
            SourceReadPage.First -> IntellijSourceContinuationAdmission.Admitted(
                IntellijSourceEntityCursor(0),
            )
            is SourceReadPage.Continue -> synchronized(entries) {
                val entry = entries[page.continuation.value]
                    ?: return@synchronized IntellijSourceContinuationAdmission.Rejected
                if (entry.request != request.binding()) {
                    IntellijSourceContinuationAdmission.Rejected
                } else {
                    IntellijSourceContinuationAdmission.Admitted(
                        IntellijSourceEntityCursor(
                            entry.nextOrdinal,
                            entry.snapshot,
                            entry.regionFingerprint,
                        ),
                    )
                }
            }
        }

    fun issue(
        request: SourceReadRequest,
        capture: IntellijSelectedSourceCapture,
        nextOrdinal: Int,
    ): SourceReadContinuation {
        val binding = request.binding()
        val predecessor = (request.page as? SourceReadPage.Continue)?.continuation?.value.orEmpty()
        val canonical = buildString {
            appendBoundedField("intellij-source-entity-page-v1")
            appendBoundedField(sequence.incrementAndGet().toString())
            appendBoundedField(binding.toString())
            appendBoundedField(capture.snapshot.toString())
            appendBoundedField(capture.regionSelector.fingerprint.value)
            appendBoundedField(nextOrdinal.toString())
            appendBoundedField(predecessor)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        val continuation = when (
            val parsed = SourceReadContinuation.parse("source-read-continuation-v1|$digest")
        ) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> error("SHA-256 continuation construction must be valid")
        }
        synchronized(entries) {
            entries[continuation.value] = Entry(
                binding,
                capture.snapshot,
                capture.regionSelector.fingerprint.value,
                nextOrdinal,
            )
        }
        return continuation
    }

    private data class Entry(
        val request: SourceRequestBinding,
        val snapshot: SourceSnapshot,
        val regionFingerprint: String,
        val nextOrdinal: Int,
    )
}

private data class SourceRequestBinding(
    val anchor: SourceAnchorBinding,
    val region: RegionSelection,
    val entities: EntitySelectionBinding,
    val text: TextProjection,
    val entityLimit: Int,
    val textByteLimit: Long,
)

private sealed interface SourceAnchorBinding {
    data class Candidate(val canonical: String) : SourceAnchorBinding
    data class Symbol(val fingerprint: String) : SourceAnchorBinding
    data class Source(val fingerprint: String) : SourceAnchorBinding
}

private sealed interface EntitySelectionBinding {
    data object None : EntitySelectionBinding
    data class Matching(
        val containment: Containment,
        val filters: List<EntityFilterBinding>,
    ) : EntitySelectionBinding
}

private sealed interface EntityFilterBinding {
    data class Declarations(
        val kinds: List<io.github.amichne.kast.source.contract.DeclarationKind>,
        val visibility: List<DeclarationVisibility>?,
    ) : EntityFilterBinding

    data object Parameters : EntityFilterBinding
    data object Calls : EntityFilterBinding
    data object References : EntityFilterBinding
}

private fun SourceReadRequest.binding(): SourceRequestBinding = SourceRequestBinding(
    anchor = when (val value = anchor) {
        is SourceReadAnchor.Candidate -> SourceAnchorBinding.Candidate(value.selector.canonical())
        is SourceReadAnchor.Symbol -> SourceAnchorBinding.Symbol(value.selector.fingerprint.value)
        is SourceReadAnchor.Source -> SourceAnchorBinding.Source(value.selector.fingerprint.value)
    },
    region = region,
    entities = entities.binding(),
    text = text,
    entityLimit = entityLimit.value,
    textByteLimit = textByteLimit.value,
)

private fun EntitySelection.binding(): EntitySelectionBinding = when (this) {
    EntitySelection.None -> EntitySelectionBinding.None
    is EntitySelection.Matching -> EntitySelectionBinding.Matching(
        containment,
        filters.map { filter ->
            when (filter) {
                is EntityFilter.Declarations -> EntityFilterBinding.Declarations(
                    filter.kinds.values,
                    when (val visibility = filter.visibility) {
                        VisibilitySelection.Any -> null
                        is VisibilitySelection.Exact -> visibility.values
                    },
                )
                EntityFilter.Parameters -> EntityFilterBinding.Parameters
                EntityFilter.Calls -> EntityFilterBinding.Calls
                EntityFilter.References -> EntityFilterBinding.References
            }
        },
    )
}

private fun CandidateSelector.canonical(): String = when (this) {
    is CandidateSelector.Declaration -> buildString {
        appendCandidateLease(lease.workspaceRoot.value, lease.generation.value)
        appendBoundedField("declaration")
        appendBoundedField(SymbolSearchScope.snapshot(selection.scope).toString())
        appendBoundedField(selection.candidate.toString())
    }
    is CandidateSelector.File -> buildString {
        appendCandidateLease(lease.workspaceRoot.value, lease.generation.value)
        appendBoundedField("file")
        appendBoundedField(file.path.value)
    }
    is CandidateSelector.Range -> buildString {
        appendCandidateLease(lease.workspaceRoot.value, lease.generation.value)
        appendBoundedField("range")
        appendBoundedField(file.path.value)
        appendBoundedField(startInclusive.value.toString())
        appendBoundedField(endExclusive.value.toString())
    }
}

private fun StringBuilder.appendCandidateLease(root: String, generation: Long) {
    appendBoundedField(root)
    appendBoundedField(generation.toString())
}

private fun StringBuilder.appendBoundedField(value: String) {
    append(value.length)
    append(':')
    append(value)
    append(';')
}

private fun IntellijSourceEntityCursor.admits(capture: IntellijSelectedSourceCapture): Boolean =
    expectedSnapshot == null ||
        (
            expectedSnapshot == capture.snapshot &&
                expectedRegionFingerprint == capture.regionSelector.fingerprint.value
        )

private fun SourceSelector.regionKind(): SourceRegionKind? = when (this) {
    is SourceSelector.RootRegion -> kind
    is SourceSelector.NestedRegion -> kind
    is SourceSelector.Entity -> null
}

private fun IntellijCommittedSourceCapture.snapshotText(declarationText: String): String {
    if (
        declarationRange.startInclusive.value == 0 &&
        declarationRange.endExclusive.value == snapshot.length.value
    ) {
        return declarationText
    }
    return buildString(snapshot.length.value) {
        repeat(declarationRange.startInclusive.value) { append(' ') }
        append(declarationText)
        repeat(snapshot.length.value - declarationRange.endExclusive.value) { append(' ') }
    }
}

private fun IntellijSelectedSourceCapture.windowRange(
    projection: TextProjection.Window,
): SourceRange? {
    val regionStart = regionSelector.range.startInclusive.value
    val regionEnd = regionSelector.range.endExclusive.value
    val seedStart = maxOf(anchorSelector.range.startInclusive.value, regionStart)
    val seedEnd = minOf(anchorSelector.range.endExclusive.value, regionEnd)
    if (seedStart > seedEnd) return null

    var start = lineStart(normalizedDocumentText, seedStart).coerceAtLeast(regionStart)
    repeat(projection.beforeLines.value) {
        if (start > regionStart) {
            start = lineStart(normalizedDocumentText, (start - 1).coerceAtLeast(regionStart))
                .coerceAtLeast(regionStart)
        }
    }
    var end = lineEnd(normalizedDocumentText, seedEnd).coerceAtMost(regionEnd)
    repeat(projection.afterLines.value) {
        if (end < regionEnd) {
            end = lineEnd(
                normalizedDocumentText,
                (end + 1).coerceAtMost(regionEnd),
            ).coerceAtMost(regionEnd)
        }
    }
    val startOffset = when (val parsed = Utf16CodeUnitOffset.parse(start)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return null
    }
    val endOffset = when (val parsed = Utf16CodeUnitOffset.parse(end)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> return null
    }
    return when (val admitted = SourceRange.create(snapshot, startOffset, endOffset)) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> null
    }
}

private fun lineStart(text: String, offset: Int): Int {
    val searchFrom = (offset - 1).coerceAtMost(text.lastIndex)
    return if (searchFrom < 0) 0 else text.lastIndexOf('\n', searchFrom) + 1
}

private fun lineEnd(text: String, offset: Int): Int {
    if (offset <= 0 && text.isEmpty()) return 0
    if (offset > 0 && offset <= text.length && text[offset - 1] == '\n') return offset
    val newline = text.indexOf('\n', offset.coerceAtMost(text.length))
    return if (newline < 0) text.length else newline + 1
}

private fun sourceEntityCount(raw: Int): SourceEntityCount = when (
    val parsed = SourceEntityCount.parse(raw)
) {
    is Refinement.Refined -> parsed.value
    is Refinement.Rejected -> error("Non-negative source entity count literal rejected")
}

private fun rejected(reason: SourceReadRejection): SourceReadResult.Rejected =
    SourceReadResult.Rejected(reason)

private fun IntellijSourceReadRejection.publicReason(): SourceReadRejection = when (this) {
    IntellijSourceReadRejection.WORKSPACE_ROOT_MISMATCH ->
        SourceReadRejection.WORKSPACE_ROOT_MISMATCH
    IntellijSourceReadRejection.STALE_GENERATION -> SourceReadRejection.STALE_GENERATION
    IntellijSourceReadRejection.SOURCE_STATE_MISMATCH -> SourceReadRejection.SOURCE_STATE_MISMATCH
    IntellijSourceReadRejection.SOURCE_UNAVAILABLE -> SourceReadRejection.SOURCE_UNAVAILABLE
    IntellijSourceReadRejection.DOCUMENT_DIRTY -> SourceReadRejection.DOCUMENT_DIRTY
    IntellijSourceReadRejection.PSI_DOCUMENT_UNCOMMITTED ->
        SourceReadRejection.PSI_DOCUMENT_UNCOMMITTED
    IntellijSourceReadRejection.OUTSIDE_SOURCE_SCOPE -> SourceReadRejection.OUTSIDE_SOURCE_SCOPE
    IntellijSourceReadRejection.ANCHOR_NOT_FOUND -> SourceReadRejection.ANCHOR_NOT_FOUND
    IntellijSourceReadRejection.AMBIGUOUS_ANCHOR -> SourceReadRejection.AMBIGUOUS_ANCHOR
    IntellijSourceReadRejection.COMPILER_ANALYSIS_UNAVAILABLE ->
        SourceReadRejection.COMPILER_ANALYSIS_UNAVAILABLE
    IntellijSourceReadRejection.DECLARATION_MOVED_OR_CHANGED ->
        SourceReadRejection.SOURCE_SELECTOR_STALE
    IntellijSourceReadRejection.REGION_ABSENT -> SourceReadRejection.REGION_ABSENT
    IntellijSourceReadRejection.UNSUPPORTED_REQUEST -> SourceReadRejection.REGION_NOT_APPLICABLE
    IntellijSourceReadRejection.PROVIDER_FAILURE,
    IntellijSourceReadRejection.CONTRACT_VIOLATION,
        -> SourceReadRejection.CONTRACT_VIOLATION
}
