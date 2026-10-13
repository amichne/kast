package io.github.amichne.kast.evidence.sqlite.historical.cli

import io.github.amichne.kast.evidence.sqlite.historical.HistoricalStorageFailure
import io.github.amichne.kast.evidence.sqlite.historical.SqliteHistoricalSnapshotStore
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.historical.HistoricalFactFamily
import io.github.amichne.kast.topology.contract.historical.HistoricalPageRequest
import io.github.amichne.kast.topology.contract.historical.HistoricalReadResult
import io.github.amichne.kast.topology.contract.historical.HistoricalRowPosition
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshotReader
import io.github.amichne.kast.topology.contract.historical.HistoricalUsageCoverage
import io.github.amichne.kast.topology.contract.historical.HistoricalUsagePage
import io.github.amichne.kast.topology.contract.historical.HistoricalUsagePosition
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Explicit historical read entry. The command accepts no workspace, compiler, Git, or mutation capability. */
fun main(args: Array<String>) {
    val response = historicalOfflineCommand(args.toList())
    println(historicalJson.encodeToString(OfflineResponse.serializer(), response))
}

internal fun historicalOfflineCommand(arguments: List<String>): OfflineResponse {
    val command =
        when (val decoded = decodeCommand(arguments)) {
            is Refinement.Refined -> decoded.value
            is Refinement.Rejected -> return OfflineResponse.Rejected(decoded.failure)
        }
    val path =
        try {
            Path.of(arguments[1]).toAbsolutePath().normalize()
        } catch (_: InvalidPathException) {
            return OfflineResponse.Rejected(OfflineFailureCode.PATH_REJECTED)
        }
    return when (val opened = SqliteHistoricalSnapshotStore.open(path)) {
        is Refinement.Rejected -> OfflineResponse.Rejected(opened.failure.document())
        is Refinement.Refined ->
            opened.value.use { reader ->
                when (command) {
                    is ReadCommand.Page -> page(reader, command)
                    is ReadCommand.Usage -> usage(reader, command.request)
                }
            }
    }
}

private fun decodeCommand(arguments: List<String>): Refinement<ReadCommand, OfflineFailureCode> {
    if (arguments.size !in 2..MAXIMUM_ARGUMENT_COUNT) return Refinement.Rejected(OfflineFailureCode.INVALID_ARGUMENTS)
    return when (arguments[0]) {
        "usage" ->
            if (arguments.size == MAXIMUM_ARGUMENT_COUNT) {
                decodeRequest<UsageRequestDocument>(arguments[2]).mapInput { ReadCommand.Usage(it) }
            } else Refinement.Rejected(OfflineFailureCode.INVALID_ARGUMENTS)
        "inventory" -> decodePage(arguments, PageOperation.INVENTORY)
        "files" -> decodePage(arguments, PageOperation.FILES)
        "declarations" -> decodePage(arguments, PageOperation.DECLARATIONS)
        else -> Refinement.Rejected(OfflineFailureCode.INVALID_ARGUMENTS)
    }
}

private fun decodePage(arguments: List<String>, operation: PageOperation): Refinement<ReadCommand, OfflineFailureCode> {
    val decoded =
        if (arguments.size == MAXIMUM_ARGUMENT_COUNT) decodeRequest<PageRequestDocument>(arguments[2])
        else Refinement.Refined(PageRequestDocument())
    return decoded.chain { it.admit() }.mapInput { ReadCommand.Page(operation, it) }
}

private const val MAXIMUM_ARGUMENT_COUNT = 3

private enum class PageOperation {
    INVENTORY,
    FILES,
    DECLARATIONS,
}

private sealed interface ReadCommand {
    data class Page(val operation: PageOperation, val request: HistoricalPageRequest) : ReadCommand

    data class Usage(val request: UsageRequestDocument) : ReadCommand
}

private fun page(reader: HistoricalSnapshotReader, command: ReadCommand.Page): OfflineResponse =
    when (command.operation) {
        PageOperation.INVENTORY -> inventory(reader, command.request)
        PageOperation.FILES ->
            when (val result = reader.files(command.request)) {
                is HistoricalReadResult.Read ->
                    OfflineResponse.Files(
                        reader.inventory.document(),
                        result.value.rows.map { it.document() },
                        result.value.end.document(),
                        workDocument(reader.openWork, result.value.work),
                    )
                is HistoricalReadResult.Rejected -> OfflineResponse.Rejected(result.failure.document())
            }
        PageOperation.DECLARATIONS ->
            when (val result = reader.declarations(command.request)) {
                is HistoricalReadResult.Read ->
                    OfflineResponse.Declarations(
                        reader.inventory.document(),
                        result.value.rows.map { it.document() },
                        result.value.end.document(),
                        workDocument(reader.openWork, result.value.work),
                    )
                is HistoricalReadResult.Rejected -> OfflineResponse.Rejected(result.failure.document())
            }
    }

private fun inventory(reader: HistoricalSnapshotReader, request: HistoricalPageRequest): OfflineResponse {
    val inventory = reader.inventory
    val start =
        when (val position = request.position) {
            HistoricalRowPosition.First -> 0L
            is HistoricalRowPosition.After -> {
                if (
                    position.snapshot != inventory.identity ||
                        position.family != HistoricalFactFamily.MODULES ||
                        position.ordinal.value >= inventory.modules.size
                ) {
                    return OfflineResponse.Rejected(OfflineFailureCode.CURSOR_MISMATCH)
                }
                position.ordinal.value + 1
            }
        }
    val modules =
        inventory.modules.subList(start.toInt(), minOf(start.toInt() + request.size.value, inventory.modules.size))
    val next =
        if (start + modules.size < inventory.modules.size) {
            RowCursorDocument(inventory.identity.value, OfflineFamily.MODULES, start + modules.size - 1)
        } else null
    val ids = modules.map { it.id }.toSet()
    return OfflineResponse.Inventory(
        inventory.document(),
        modules.map { it.document() },
        inventory.coverage.filter { it.module in ids }.map { it.document() },
        inventory.counts.files.value,
        inventory.counts.declarations.value,
        inventory.counts.occurrences.value,
        next,
        ReadWorkDocument(
            reader.openWork.rowsExamined.value,
            reader.openWork.artifactBytes.value,
            reader.openWork.copiedBytesRead.value,
            reader.openWork.copiedBytesWritten.value,
            0,
            modules.size.toLong(),
            modules.size.toLong(),
        ),
    )
}

private fun usage(reader: HistoricalSnapshotReader, request: UsageRequestDocument): OfflineResponse =
    when (val prepared = request.admit(reader)) {
        is Refinement.Rejected -> OfflineResponse.Rejected(prepared.failure)
        is Refinement.Refined ->
            when (val result = reader.usage(prepared.value.query)) {
                is HistoricalReadResult.Rejected -> OfflineResponse.Rejected(result.failure.document())
                is HistoricalReadResult.Read -> usageDocument(reader, result.value, prepared.value.selectionRows)
            }
    }

private fun usageDocument(
    reader: HistoricalSnapshotReader,
    value: HistoricalUsagePage,
    selectionRows: Long,
): OfflineResponse.Usage {
    val qualifications =
        when (val coverage = value.query.coverage) {
            HistoricalUsageCoverage.Complete -> emptyList()
            is HistoricalUsageCoverage.Qualified -> coverage.qualifications.map { it.document() }
        }
    val pageStart =
        when (val position = value.query.position) {
            HistoricalUsagePosition.First -> null
            is HistoricalUsagePosition.After ->
                UsageCursorDocument(
                    position.cursor.snapshot.value,
                    position.cursor.query.value,
                    position.cursor.after.value,
                )
        }
    return OfflineResponse.Usage(
        reader.inventory.document(),
        value.query.sourceModules.map { it.value },
        value.query.targets.map { it.declaration.document() },
        OfflineFamily.REFERENCES,
        when (value.query.coverage) {
            HistoricalUsageCoverage.Complete -> OfflineCoverageStatus.COMPLETE
            is HistoricalUsageCoverage.Qualified -> OfflineCoverageStatus.QUALIFIED
        },
        qualifications,
        value.rows.map { it.document() },
        value.rows.size,
        pageStart,
        value.end.document(),
        workDocument(reader.openWork, value.work, selectionRows),
    )
}

private fun HistoricalStorageFailure.document(): OfflineFailureCode =
    when (this) {
        HistoricalStorageFailure.DECLARATION_NOT_FOUND -> OfflineFailureCode.DECLARATION_NOT_FOUND
        HistoricalStorageFailure.CURSOR_MISMATCH -> OfflineFailureCode.CURSOR_MISMATCH
        HistoricalStorageFailure.PATH_REJECTED -> OfflineFailureCode.PATH_REJECTED
        HistoricalStorageFailure.SCHEMA_REJECTED -> OfflineFailureCode.SCHEMA_REJECTED
        HistoricalStorageFailure.PROFILE_REJECTED -> OfflineFailureCode.PROFILE_REJECTED
        HistoricalStorageFailure.CONTENT_REJECTED -> OfflineFailureCode.CONTENT_REJECTED
        HistoricalStorageFailure.STORAGE_UNAVAILABLE -> OfflineFailureCode.STORAGE_UNAVAILABLE
        HistoricalStorageFailure.CAPACITY_EXCEEDED -> OfflineFailureCode.CAPACITY_EXCEEDED
        HistoricalStorageFailure.ATOMIC_PUBLICATION_UNAVAILABLE -> OfflineFailureCode.ATOMIC_PUBLICATION_UNAVAILABLE
    }
