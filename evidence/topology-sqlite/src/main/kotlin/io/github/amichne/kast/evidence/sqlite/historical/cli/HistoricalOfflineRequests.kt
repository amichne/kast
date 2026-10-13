package io.github.amichne.kast.evidence.sqlite.historical.cli

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.topology.contract.historical.HistoricalDeclarationId
import io.github.amichne.kast.topology.contract.historical.HistoricalDeclarationReference
import io.github.amichne.kast.topology.contract.historical.HistoricalDigest
import io.github.amichne.kast.topology.contract.historical.HistoricalFactFamily
import io.github.amichne.kast.topology.contract.historical.HistoricalModuleId
import io.github.amichne.kast.topology.contract.historical.HistoricalPageRequest
import io.github.amichne.kast.topology.contract.historical.HistoricalPageSize
import io.github.amichne.kast.topology.contract.historical.HistoricalReadFailure
import io.github.amichne.kast.topology.contract.historical.HistoricalReadResult
import io.github.amichne.kast.topology.contract.historical.HistoricalRowOrdinal
import io.github.amichne.kast.topology.contract.historical.HistoricalRowPosition
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshotInventory
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshotReader
import io.github.amichne.kast.topology.contract.historical.HistoricalUsageCursor
import io.github.amichne.kast.topology.contract.historical.HistoricalUsageFailure
import io.github.amichne.kast.topology.contract.historical.HistoricalUsagePlan
import io.github.amichne.kast.topology.contract.historical.HistoricalUsagePosition
import io.github.amichne.kast.topology.contract.historical.HistoricalUsageQuery
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

@Serializable internal data class PageRequestDocument(val size: Int = 100, val cursor: RowCursorDocument? = null)

@Serializable
internal data class HistoricalReferenceDocument(val snapshot: String, val declaration: DeclarationIdentityDocument)

@Serializable
internal data class UsageRequestDocument(
    val sourceModules: List<String>,
    val targets: List<HistoricalReferenceDocument>,
    val size: Int = 100,
    val cursor: UsageCursorDocument? = null,
)

internal data class PreparedUsage(val query: HistoricalUsageQuery, val selectionRows: Long)

internal inline fun <reified T> decodeRequest(raw: String): Refinement<T, OfflineFailureCode> =
    if (raw.length > MAXIMUM_REQUEST_CHARACTERS) Refinement.Rejected(OfflineFailureCode.INVALID_REQUEST)
    else
        try {
            Refinement.Refined(historicalJson.decodeFromString<T>(raw))
        } catch (_: SerializationException) {
            Refinement.Rejected(OfflineFailureCode.INVALID_REQUEST)
        }

internal fun PageRequestDocument.admit(): Refinement<HistoricalPageRequest, OfflineFailureCode> =
    admitPageSize(size).chain { admittedSize ->
        when (val value = cursor) {
            null -> Refinement.Refined(HistoricalPageRequest(admittedSize))
            else ->
                HistoricalDigest.parse(value.snapshot).input().chain { snapshot ->
                    HistoricalRowOrdinal.parse(value.after).input().mapInput { ordinal ->
                        HistoricalPageRequest(
                            admittedSize,
                            HistoricalRowPosition.After(snapshot, value.family.domain(), ordinal),
                        )
                    }
                }
        }
    }

internal fun UsageRequestDocument.admit(
    reader: HistoricalSnapshotReader
): Refinement<PreparedUsage, OfflineFailureCode> =
    prepare(reader.inventory).chain { plan ->
        val references = linkedSetOf<HistoricalDeclarationReference>()
        var selectionRows = 0L
        for (id in plan.targetDeclarations) when (val selected = reader.select(id)) {
            is HistoricalReadResult.Rejected -> return Refinement.Rejected(selected.failure.document())
            is HistoricalReadResult.Read -> {
                selectionRows += selected.value.work.rowsExamined.value
                references += selected.value.reference
            }
        }
        when (val query = plan.complete(references)) {
            is Refinement.Refined -> Refinement.Refined(PreparedUsage(query.value, selectionRows))
            is Refinement.Rejected -> Refinement.Rejected(query.failure.document())
        }
    }

private fun UsageRequestDocument.prepare(
    inventory: HistoricalSnapshotInventory
): Refinement<HistoricalUsagePlan, OfflineFailureCode> {
    if (sourceModules.size > HistoricalUsageQuery.MAXIMUM_SOURCE_MODULES)
        return Refinement.Rejected(OfflineFailureCode.SOURCE_DOMAIN_LIMIT)
    if (targets.size > HistoricalUsageQuery.MAXIMUM_TARGET_DECLARATIONS)
        return Refinement.Rejected(OfflineFailureCode.TARGET_DOMAIN_LIMIT)
    val pageSize =
        when (val result = admitPageSize(size)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return result
        }
    val position =
        when (val result = usagePosition()) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return result
        }
    val sources =
        when (val result = sourceDomain()) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return result
        }
    val declarations =
        when (val result = targetDomain(inventory.identity)) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return result
        }
    return when (val result = HistoricalUsageQuery.prepare(inventory, sources, declarations, pageSize, position)) {
        is Refinement.Refined -> result
        is Refinement.Rejected -> Refinement.Rejected(result.failure.document())
    }
}

private fun UsageRequestDocument.sourceDomain(): Refinement<Set<HistoricalModuleId>, OfflineFailureCode> {
    val sources = linkedSetOf<HistoricalModuleId>()
    for (source in sourceModules) when (val parsed = HistoricalModuleId.parse(source)) {
        is Refinement.Refined ->
            if (!sources.add(parsed.value)) return Refinement.Rejected(OfflineFailureCode.INVALID_REQUEST)
        is Refinement.Rejected -> return Refinement.Rejected(OfflineFailureCode.INVALID_REQUEST)
    }
    return Refinement.Refined(sources)
}

private fun UsageRequestDocument.targetDomain(
    snapshot: HistoricalDigest
): Refinement<Set<HistoricalDeclarationId>, OfflineFailureCode> {
    val declarations = linkedSetOf<HistoricalDeclarationId>()
    for (target in targets) {
        if (target.snapshot != snapshot.value) return Refinement.Rejected(OfflineFailureCode.SNAPSHOT_MISMATCH)
        when (val parsed = target.declaration.admit()) {
            is Refinement.Refined ->
                if (!declarations.add(parsed.value)) return Refinement.Rejected(OfflineFailureCode.INVALID_REQUEST)
            is Refinement.Rejected -> return parsed
        }
    }
    return Refinement.Refined(declarations)
}

private fun UsageRequestDocument.usagePosition(): Refinement<HistoricalUsagePosition, OfflineFailureCode> =
    when (val value = cursor) {
        null -> Refinement.Refined(HistoricalUsagePosition.First)
        else ->
            HistoricalDigest.parse(value.snapshot).input().chain { snapshot ->
                HistoricalDigest.parse(value.query).input().chain { query ->
                    HistoricalRowOrdinal.parse(value.after).input().mapInput { ordinal ->
                        HistoricalUsagePosition.After(HistoricalUsageCursor.fromStored(snapshot, query, ordinal))
                    }
                }
            }
    }

private fun DeclarationIdentityDocument.admit(): Refinement<HistoricalDeclarationId, OfflineFailureCode> =
    CompilerSymbolIdentity.parse(compiler).input().chain { identity ->
        WorkspaceSourcePath.parse(file).input().chain { path ->
            ExactDeclarationTextRange.parse(start, end).input().mapInput { range ->
                HistoricalDeclarationId(identity, path, range)
            }
        }
    }

private fun OfflineFamily.domain(): HistoricalFactFamily =
    when (this) {
        OfflineFamily.MODULES -> HistoricalFactFamily.MODULES
        OfflineFamily.FILES -> HistoricalFactFamily.FILES
        OfflineFamily.DECLARATIONS -> HistoricalFactFamily.DECLARATIONS
        OfflineFamily.REFERENCES -> HistoricalFactFamily.REFERENCES
    }

internal fun HistoricalReadFailure.document(): OfflineFailureCode =
    when (this) {
        HistoricalReadFailure.STORAGE_UNAVAILABLE -> OfflineFailureCode.STORAGE_UNAVAILABLE
        HistoricalReadFailure.CORRUPT_ARTIFACT -> OfflineFailureCode.CONTENT_REJECTED
        HistoricalReadFailure.SCHEMA_UNSUPPORTED -> OfflineFailureCode.SCHEMA_REJECTED
        HistoricalReadFailure.PROFILE_UNSUPPORTED -> OfflineFailureCode.PROFILE_REJECTED
        HistoricalReadFailure.CAPACITY_EXCEEDED -> OfflineFailureCode.CAPACITY_EXCEEDED
        HistoricalReadFailure.SNAPSHOT_MISMATCH -> OfflineFailureCode.SNAPSHOT_MISMATCH
        HistoricalReadFailure.DECLARATION_NOT_FOUND -> OfflineFailureCode.DECLARATION_NOT_FOUND
        HistoricalReadFailure.CURSOR_MISMATCH -> OfflineFailureCode.CURSOR_MISMATCH
    }

private fun HistoricalUsageFailure.document(): OfflineFailureCode =
    when (this) {
        HistoricalUsageFailure.EMPTY_SOURCE_DOMAIN -> OfflineFailureCode.EMPTY_SOURCE_DOMAIN
        HistoricalUsageFailure.EMPTY_TARGET_DOMAIN -> OfflineFailureCode.EMPTY_TARGET_DOMAIN
        HistoricalUsageFailure.SOURCE_OUTSIDE_CAPTURE -> OfflineFailureCode.SOURCE_OUTSIDE_CAPTURE
        HistoricalUsageFailure.SNAPSHOT_MISMATCH -> OfflineFailureCode.SNAPSHOT_MISMATCH
        HistoricalUsageFailure.REFERENCES_NOT_CAPTURED -> OfflineFailureCode.REFERENCES_NOT_CAPTURED
        HistoricalUsageFailure.CURSOR_MISMATCH -> OfflineFailureCode.CURSOR_MISMATCH
        HistoricalUsageFailure.SOURCE_DOMAIN_LIMIT -> OfflineFailureCode.SOURCE_DOMAIN_LIMIT
        HistoricalUsageFailure.TARGET_DOMAIN_LIMIT -> OfflineFailureCode.TARGET_DOMAIN_LIMIT
        HistoricalUsageFailure.TARGET_SELECTION_MISMATCH -> OfflineFailureCode.TARGET_SELECTION_MISMATCH
    }

private fun admitPageSize(raw: Int): Refinement<HistoricalPageSize, OfflineFailureCode> =
    when (val result = HistoricalPageSize.parse(raw)) {
        is Refinement.Refined -> result
        is Refinement.Rejected -> Refinement.Rejected(OfflineFailureCode.PAGE_SIZE_OUTSIDE_LIMIT)
    }

private fun <T, F> Refinement<T, F>.input(): Refinement<T, OfflineFailureCode> =
    when (this) {
        is Refinement.Refined -> this
        is Refinement.Rejected -> Refinement.Rejected(OfflineFailureCode.INVALID_REQUEST)
    }

internal inline fun <T, U, F> Refinement<T, F>.chain(transform: (T) -> Refinement<U, F>): Refinement<U, F> =
    when (this) {
        is Refinement.Refined -> transform(value)
        is Refinement.Rejected -> this
    }

internal inline fun <T, U, F> Refinement<T, F>.mapInput(transform: (T) -> U): Refinement<U, F> =
    when (this) {
        is Refinement.Refined -> Refinement.Refined(transform(value))
        is Refinement.Rejected -> this
    }

internal const val MAXIMUM_REQUEST_CHARACTERS = 1_048_576
