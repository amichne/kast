package io.github.amichne.kast.evidence.sqlite.historical

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.topology.contract.historical.GitObjectId
import io.github.amichne.kast.topology.contract.historical.HistoricalContext
import io.github.amichne.kast.topology.contract.historical.HistoricalCoverage
import io.github.amichne.kast.topology.contract.historical.HistoricalCoverageStatus
import io.github.amichne.kast.topology.contract.historical.HistoricalDeclaration
import io.github.amichne.kast.topology.contract.historical.HistoricalDeclarationId
import io.github.amichne.kast.topology.contract.historical.HistoricalDependency
import io.github.amichne.kast.topology.contract.historical.HistoricalDigest
import io.github.amichne.kast.topology.contract.historical.HistoricalFactFamily
import io.github.amichne.kast.topology.contract.historical.HistoricalFile
import io.github.amichne.kast.topology.contract.historical.HistoricalFileProvenance
import io.github.amichne.kast.topology.contract.historical.HistoricalHeader
import io.github.amichne.kast.topology.contract.historical.HistoricalLimitation
import io.github.amichne.kast.topology.contract.historical.HistoricalModule
import io.github.amichne.kast.topology.contract.historical.HistoricalModuleId
import io.github.amichne.kast.topology.contract.historical.HistoricalName
import io.github.amichne.kast.topology.contract.historical.HistoricalOccurrence
import io.github.amichne.kast.topology.contract.historical.HistoricalOccurrenceOwner
import io.github.amichne.kast.topology.contract.historical.HistoricalOwnershipUnavailableCause
import io.github.amichne.kast.topology.contract.historical.HistoricalProfile
import io.github.amichne.kast.topology.contract.historical.HistoricalReferenceContext
import io.github.amichne.kast.topology.contract.historical.HistoricalReferenceKind
import io.github.amichne.kast.topology.contract.historical.HistoricalRepositoryId
import io.github.amichne.kast.topology.contract.historical.HistoricalRevision
import io.github.amichne.kast.topology.contract.historical.HistoricalSourceSet
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import java.sql.Connection
import java.sql.ResultSet

internal fun Connection.readHistoricalHeader():
    Refinement<Pair<HistoricalHeader, HistoricalDigest>, HistoricalStorageFailure> =
    createStatement().use { statement ->
        statement.executeQuery("SELECT * FROM historical_header").use { rows ->
            if (!rows.next()) return Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
            val result = historicalHeader(rows)
            if (rows.next()) Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED) else result
        }
    }

private fun historicalHeader(
    rows: ResultSet
): Refinement<Pair<HistoricalHeader, HistoricalDigest>, HistoricalStorageFailure> {
    val repository =
        historicalText(rows, "repository", HistoricalRepositoryId::parse).orReturn {
            return it
        }
    val commit =
        historicalText(rows, "commit_id", GitObjectId::parse).orReturn {
            return it
        }
    val tree =
        historicalText(rows, "tree_id", GitObjectId::parse).orReturn {
            return it
        }
    val model =
        historicalText(rows, "model_digest", HistoricalDigest::parse).orReturn {
            return it
        }
    val resolution =
        historicalText(rows, "resolution_digest", HistoricalDigest::parse).orReturn {
            return it
        }
    val compiler =
        historicalText(rows, "compiler_digest", HistoricalDigest::parse).orReturn {
            return it
        }
    val profile =
        historicalEnum<HistoricalProfile>(rows, "profile", HistoricalStorageFailure.PROFILE_REJECTED).orReturn {
            return it
        }
    val producer =
        historicalText(rows, "producer", HistoricalName::parse).orReturn {
            return it
        }
    val digest =
        historicalText(rows, "logical_digest", HistoricalDigest::parse).orReturn {
            return it
        }
    return Refinement.Refined(
        HistoricalHeader(
            HistoricalRevision(repository, commit, tree),
            HistoricalContext(model, resolution, compiler),
            profile,
            producer,
        ) to digest
    )
}

internal fun Connection.readHistoricalModules(): Refinement<List<HistoricalModule>, HistoricalStorageFailure> =
    queryHistorical("SELECT * FROM historical_module ORDER BY id") { rows ->
        historicalText(rows, "id", HistoricalModuleId::parse).flatMap { id ->
            historicalText(rows, "project", HistoricalName::parse).flatMap { project ->
                historicalText(rows, "name", HistoricalName::parse).flatMap { name ->
                    val sourceSet =
                        HistoricalSqliteText.read(rows, "source_set").flatMap { stored ->
                            when (stored) {
                                HistoricalOptionalText.Absent -> Refinement.Refined(HistoricalSourceSet.Rootless)
                                is HistoricalOptionalText.Present ->
                                    HistoricalName.parse(stored.text.value).storage().map {
                                        HistoricalSourceSet.Named(it)
                                    }
                            }
                        }
                    sourceSet.map { HistoricalModule(id, project, name, it) }
                }
            }
        }
    }

internal fun Connection.readHistoricalDependencies(): Refinement<List<HistoricalDependency>, HistoricalStorageFailure> =
    queryHistorical("SELECT * FROM historical_dependency ORDER BY source, target") { rows ->
        historicalText(rows, "source", HistoricalModuleId::parse).flatMap { source ->
            historicalText(rows, "target", HistoricalModuleId::parse).map { target ->
                HistoricalDependency(source, target)
            }
        }
    }

internal fun historicalFile(rows: ResultSet): Refinement<HistoricalFile, HistoricalStorageFailure> {
    val path =
        historicalText(rows, "path", WorkspaceSourcePath::parse).orReturn {
            return it
        }
    val content =
        historicalText(rows, "content", WorkspaceSourceContentHash::parse).orReturn {
            return it
        }
    val module =
        historicalText(rows, "module", HistoricalModuleId::parse).orReturn {
            return it
        }
    val provenance =
        historicalText(rows, "provenance")
            .flatMap { kind ->
                when (kind.value) {
                    "TRACKED" ->
                        historicalText(rows, "provenance_value", GitObjectId::parse).map {
                            HistoricalFileProvenance.Tracked(it)
                        }
                    "GENERATED" ->
                        historicalText(rows, "provenance_value", HistoricalName::parse).map {
                            HistoricalFileProvenance.Generated(it)
                        }
                    else -> Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
                }
            }
            .orReturn {
                return it
            }
    return Refinement.Refined(HistoricalFile(path, content, module, provenance))
}

internal fun historicalDeclaration(
    rows: ResultSet,
    prefix: String = "",
): Refinement<HistoricalDeclaration, HistoricalStorageFailure> {
    val id =
        historicalDeclarationId(rows, prefix).orReturn {
            return it
        }
    val name =
        historicalText(rows, "${prefix}name", HistoricalName::parse).orReturn {
            return it
        }
    val signature =
        historicalText(rows, "${prefix}signature", HistoricalName::parse).orReturn {
            return it
        }
    val kind =
        historicalEnum<CompilerSymbolKind>(rows, "${prefix}kind").orReturn {
            return it
        }
    return Refinement.Refined(HistoricalDeclaration(id, name, signature, kind))
}

internal fun historicalDeclarationId(
    rows: ResultSet,
    prefix: String = "",
): Refinement<HistoricalDeclarationId, HistoricalStorageFailure> {
    val compiler =
        historicalText(rows, "${prefix}compiler", CompilerSymbolIdentity::parse).orReturn {
            return it
        }
    val file =
        historicalText(rows, "${prefix}file", WorkspaceSourcePath::parse).orReturn {
            return it
        }
    val range =
        historicalRange(rows, prefix).orReturn {
            return it
        }
    return Refinement.Refined(HistoricalDeclarationId(compiler, file, range))
}

private fun historicalRange(
    rows: ResultSet,
    prefix: String = "",
): Refinement<ExactDeclarationTextRange, HistoricalStorageFailure> =
    historicalOffset(rows, "${prefix}start_offset").flatMap { start ->
        historicalOffset(rows, "${prefix}end_offset").flatMap { end ->
            ExactDeclarationTextRange.parse(start, end).storage()
        }
    }

private fun historicalOffset(rows: ResultSet, column: String): Refinement<Int, HistoricalStorageFailure> {
    val raw =
        when (val stored = rows.getObject(column)) {
            is Int -> stored.toLong()
            is Long -> stored
            else -> return Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
        }
    return if (raw in 0..Int.MAX_VALUE.toLong()) Refinement.Refined(raw.toInt())
    else Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
}

internal const val HISTORICAL_OCCURRENCE_QUERY =
    """SELECT o.*, t.compiler AS target_compiler, t.file AS target_file,
    t.start_offset AS target_start_offset, t.end_offset AS target_end_offset,
    d.compiler AS owner_compiler, d.file AS owner_file, d.start_offset AS owner_start_offset,
    d.end_offset AS owner_end_offset
    FROM historical_occurrence o JOIN historical_declaration t ON o.target = t.ordinal
    LEFT JOIN historical_declaration d ON o.owner_declaration = d.ordinal"""

internal fun historicalOccurrence(rows: ResultSet): Refinement<HistoricalOccurrence, HistoricalStorageFailure> {
    val file =
        historicalText(rows, "source_file", WorkspaceSourcePath::parse).orReturn {
            return it
        }
    val range =
        historicalRange(rows).orReturn {
            return it
        }
    val target =
        historicalDeclarationId(rows, "target_").orReturn {
            return it
        }
    val owner =
        historicalOwner(rows).orReturn {
            return it
        }
    val kind =
        historicalEnum<HistoricalReferenceKind>(rows, "kind").orReturn {
            return it
        }
    val context =
        historicalEnum<HistoricalReferenceContext>(rows, "context").orReturn {
            return it
        }
    return Refinement.Refined(HistoricalOccurrence(file, range, target, owner, kind, context))
}

private fun historicalOwner(rows: ResultSet): Refinement<HistoricalOccurrenceOwner, HistoricalStorageFailure> =
    historicalText(rows, "owner_kind").flatMap { kind ->
        when (kind.value) {
            "DECLARATION" ->
                HistoricalSqliteText.read(rows, "owner_detail").flatMap { detail ->
                    when (detail) {
                        HistoricalOptionalText.Absent ->
                            historicalDeclarationId(rows, "owner_").map { HistoricalOccurrenceOwner.Declaration(it) }
                        is HistoricalOptionalText.Present ->
                            Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
                    }
                }
            "FILE_SCOPED" ->
                historicalEnum<HistoricalReferenceContext>(rows, "owner_detail").map {
                    HistoricalOccurrenceOwner.FileScoped(it)
                }
            "UNAVAILABLE" ->
                historicalEnum<HistoricalOwnershipUnavailableCause>(rows, "owner_detail").map {
                    HistoricalOccurrenceOwner.Unavailable(it)
                }
            else -> Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
        }
    }

internal fun Connection.readHistoricalCoverage(): Refinement<List<HistoricalCoverage>, HistoricalStorageFailure> =
    queryHistorical("SELECT * FROM historical_limitation ORDER BY module, family, reason") { rows ->
            historicalText(rows, "module", HistoricalModuleId::parse).flatMap { module ->
                historicalEnum<HistoricalFactFamily>(rows, "family").flatMap { family ->
                    historicalEnum<HistoricalLimitation>(rows, "reason").map { reason ->
                        Triple(module, family, reason)
                    }
                }
            }
        }
        .flatMap { reasons ->
            val grouped = reasons.groupBy({ it.first to it.second }, { it.third })
            queryHistorical("SELECT * FROM historical_coverage ORDER BY module, family") { rows ->
                    historicalText(rows, "module", HistoricalModuleId::parse).flatMap { module ->
                        historicalEnum<HistoricalFactFamily>(rows, "family").flatMap { family ->
                            historicalText(rows, "status").flatMap { status ->
                                coverageStatus(status.value, grouped[module to family].orEmpty()).map {
                                    HistoricalCoverage(module, family, it)
                                }
                            }
                        }
                    }
                }
                .map { it.sortedWith(compareBy({ it.module }, { it.family })) }
        }

private fun coverageStatus(
    raw: String?,
    causes: List<HistoricalLimitation>,
): Refinement<HistoricalCoverageStatus, HistoricalStorageFailure> =
    when (raw) {
        "COMPLETE" -> unqualifiedCoverage(HistoricalCoverageStatus.Complete, causes)
        "NOT_COLLECTED" -> unqualifiedCoverage(HistoricalCoverageStatus.NotCollected, causes)
        "OUTSIDE_PROFILE" ->
            if (causes.size == 1) Refinement.Refined(HistoricalCoverageStatus.OutsideProfile(causes[0]))
            else Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
        "QUALIFIED" ->
            if (causes.isNotEmpty())
                Refinement.Refined(HistoricalCoverageStatus.Qualified(causes.first(), causes.drop(1).toSet()))
            else Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
        else -> Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
    }

private fun unqualifiedCoverage(
    status: HistoricalCoverageStatus,
    causes: List<HistoricalLimitation>,
): Refinement<HistoricalCoverageStatus, HistoricalStorageFailure> =
    if (causes.isEmpty()) Refinement.Refined(status) else Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)

internal fun <T> Connection.queryHistorical(
    sql: String,
    read: (ResultSet) -> Refinement<T, HistoricalStorageFailure>,
): Refinement<List<T>, HistoricalStorageFailure> =
    createStatement().use { statement -> statement.executeQuery(sql).use { rows -> rows.readHistoricalRows(read) } }

internal fun <T> ResultSet.readHistoricalRows(
    read: (ResultSet) -> Refinement<T, HistoricalStorageFailure>
): Refinement<List<T>, HistoricalStorageFailure> {
    val values = mutableListOf<T>()
    while (next()) {
        when (val value = read(this)) {
            is Refinement.Refined -> values += value.value
            is Refinement.Rejected -> return value
        }
    }
    return Refinement.Refined(values)
}

internal inline fun <reified T : Enum<T>> historicalEnum(
    raw: String?,
    failure: HistoricalStorageFailure = HistoricalStorageFailure.CONTENT_REJECTED,
): Refinement<T, HistoricalStorageFailure> {
    val value = enumValues<T>().firstOrNull { it.name == raw }
    return if (value == null) Refinement.Rejected(failure) else Refinement.Refined(value)
}

internal fun <T, F> Refinement<T, F>.storage(): Refinement<T, HistoricalStorageFailure> =
    when (this) {
        is Refinement.Refined -> this
        is Refinement.Rejected -> Refinement.Rejected(HistoricalStorageFailure.CONTENT_REJECTED)
    }

internal inline fun <T, R, F> Refinement<T, F>.map(transform: (T) -> R): Refinement<R, F> =
    when (this) {
        is Refinement.Refined -> Refinement.Refined(transform(value))
        is Refinement.Rejected -> this
    }

internal inline fun <T, R, F> Refinement<T, F>.flatMap(transform: (T) -> Refinement<R, F>): Refinement<R, F> =
    when (this) {
        is Refinement.Refined -> transform(value)
        is Refinement.Rejected -> this
    }

internal inline fun <T, F> Refinement<T, F>.orReturn(reject: (Refinement.Rejected<F>) -> Nothing): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> reject(this)
    }
