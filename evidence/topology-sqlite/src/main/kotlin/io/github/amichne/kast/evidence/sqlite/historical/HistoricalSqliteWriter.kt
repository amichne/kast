package io.github.amichne.kast.evidence.sqlite.historical

import io.github.amichne.kast.topology.contract.historical.HistoricalCoverageStatus
import io.github.amichne.kast.topology.contract.historical.HistoricalFileProvenance
import io.github.amichne.kast.topology.contract.historical.HistoricalLimitation
import io.github.amichne.kast.topology.contract.historical.HistoricalOccurrenceOwner
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshot
import io.github.amichne.kast.topology.contract.historical.HistoricalSourceSet
import java.sql.Connection

internal fun Connection.writeHistoricalSnapshot(snapshot: HistoricalSnapshot) {
    writeHistoricalHeader(snapshot)
    writeHistoricalModules(snapshot)
    writeHistoricalDependencies(snapshot)
    writeHistoricalFiles(snapshot)
    writeHistoricalDeclarations(snapshot)
    writeHistoricalOccurrences(snapshot)
    writeHistoricalCoverage(snapshot)
}

private fun Connection.writeHistoricalHeader(snapshot: HistoricalSnapshot) {
    prepareStatement("INSERT INTO historical_header VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use { statement ->
        val header = snapshot.header
        var parameter = 1
        statement.setString(parameter++, header.revision.repository.value)
        statement.setString(parameter++, header.revision.commit.value)
        statement.setString(parameter++, header.revision.tree.value)
        statement.setString(parameter++, header.context.model.value)
        statement.setString(parameter++, header.context.resolution.value)
        statement.setString(parameter++, header.context.compiler.value)
        statement.setString(parameter++, header.profile.name)
        statement.setString(parameter++, header.producer.value)
        statement.setString(parameter++, snapshot.identity.value)
        statement.executeUpdate()
    }
}

private fun Connection.writeHistoricalModules(snapshot: HistoricalSnapshot) {
    prepareStatement("INSERT INTO historical_module VALUES (?, ?, ?, ?)").use { statement ->
        snapshot.modules.forEach { value ->
            var parameter = 1
            statement.setString(parameter++, value.id.value)
            statement.setString(parameter++, value.project.value)
            statement.setString(parameter++, value.name.value)
            statement.setString(
                parameter++,
                when (val sourceSet = value.sourceSet) {
                    is HistoricalSourceSet.Named -> sourceSet.name.value
                    HistoricalSourceSet.Rootless -> null
                },
            )
            statement.executeUpdate()
        }
    }
}

private fun Connection.writeHistoricalDependencies(snapshot: HistoricalSnapshot) {
    prepareStatement("INSERT INTO historical_dependency VALUES (?, ?)").use { statement ->
        snapshot.dependencies.forEach { value ->
            var parameter = 1
            statement.setString(parameter++, value.source.value)
            statement.setString(parameter++, value.target.value)
            statement.executeUpdate()
        }
    }
}

private fun Connection.writeHistoricalFiles(snapshot: HistoricalSnapshot) {
    prepareStatement("INSERT INTO historical_file VALUES (?, ?, ?, ?, ?, ?)").use { statement ->
        snapshot.files.forEachIndexed { ordinal, value ->
            var parameter = 1
            statement.setInt(parameter++, ordinal)
            statement.setString(parameter++, value.path.value)
            statement.setString(parameter++, value.content.value)
            statement.setString(parameter++, value.module.value)
            when (val provenance = value.provenance) {
                is HistoricalFileProvenance.Tracked -> {
                    statement.setString(parameter++, "TRACKED")
                    statement.setString(parameter++, provenance.blob.value)
                }
                is HistoricalFileProvenance.Generated -> {
                    statement.setString(parameter++, "GENERATED")
                    statement.setString(parameter++, provenance.producer.value)
                }
            }
            statement.executeUpdate()
        }
    }
}

private fun Connection.writeHistoricalDeclarations(snapshot: HistoricalSnapshot) {
    prepareStatement("INSERT INTO historical_declaration VALUES (?, ?, ?, ?, ?, ?, ?, ?)").use { statement ->
        snapshot.declarations.forEachIndexed { ordinal, value ->
            var parameter = 1
            statement.setInt(parameter++, ordinal)
            statement.setString(parameter++, value.id.compiler.value)
            statement.setString(parameter++, value.id.file.value)
            statement.setInt(parameter++, value.id.range.startInclusive)
            statement.setInt(parameter++, value.id.range.endExclusive)
            statement.setString(parameter++, value.name.value)
            statement.setString(parameter++, value.signature.value)
            statement.setString(parameter++, value.kind.name)
            statement.executeUpdate()
        }
    }
}

private fun Connection.writeHistoricalOccurrences(snapshot: HistoricalSnapshot) {
    val declarationOrdinals = snapshot.declarations.mapIndexed { ordinal, value -> value.id to ordinal }.toMap()
    prepareStatement("INSERT INTO historical_occurrence VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use { statement ->
        snapshot.occurrences.forEachIndexed { ordinal, value ->
            var parameter = 1
            statement.setInt(parameter++, ordinal)
            statement.setString(parameter++, value.sourceFile.value)
            statement.setInt(parameter++, value.range.startInclusive)
            statement.setInt(parameter++, value.range.endExclusive)
            statement.setInt(parameter++, declarationOrdinals.getValue(value.target))
            when (val owner = value.owner) {
                is HistoricalOccurrenceOwner.Declaration -> {
                    statement.setString(parameter++, "DECLARATION")
                    statement.setInt(parameter++, declarationOrdinals.getValue(owner.id))
                    statement.setNull(parameter++, java.sql.Types.VARCHAR)
                }
                is HistoricalOccurrenceOwner.FileScoped -> {
                    statement.setString(parameter++, "FILE_SCOPED")
                    statement.setNull(parameter++, java.sql.Types.INTEGER)
                    statement.setString(parameter++, owner.context.name)
                }
                is HistoricalOccurrenceOwner.Unavailable -> {
                    statement.setString(parameter++, "UNAVAILABLE")
                    statement.setNull(parameter++, java.sql.Types.INTEGER)
                    statement.setString(parameter++, owner.cause.name)
                }
            }
            statement.setString(parameter++, value.kind.name)
            statement.setString(parameter++, value.context.name)
            statement.executeUpdate()
        }
    }
}

private fun Connection.writeHistoricalCoverage(snapshot: HistoricalSnapshot) {
    prepareStatement("INSERT INTO historical_coverage VALUES (?, ?, ?)").use { statement ->
        snapshot.coverage.forEach { value ->
            var parameter = 1
            statement.setString(parameter++, value.module.value)
            statement.setString(parameter++, value.family.name)
            statement.setString(
                parameter,
                when (value.status) {
                    HistoricalCoverageStatus.Complete -> "COMPLETE"
                    HistoricalCoverageStatus.NotCollected -> "NOT_COLLECTED"
                    is HistoricalCoverageStatus.OutsideProfile -> "OUTSIDE_PROFILE"
                    is HistoricalCoverageStatus.Qualified -> "QUALIFIED"
                },
            )
            statement.executeUpdate()
        }
    }
    prepareStatement("INSERT INTO historical_limitation VALUES (?, ?, ?)").use { statement ->
        snapshot.coverage.forEach { value ->
            coverageReasons(value.status).forEach { reason ->
                var parameter = 1
                statement.setString(parameter++, value.module.value)
                statement.setString(parameter++, value.family.name)
                statement.setString(parameter, reason.name)
                statement.executeUpdate()
            }
        }
    }
}

private fun coverageReasons(status: HistoricalCoverageStatus): List<HistoricalLimitation> =
    when (status) {
        HistoricalCoverageStatus.Complete,
        HistoricalCoverageStatus.NotCollected -> emptyList()
        is HistoricalCoverageStatus.OutsideProfile -> listOf(status.reason)
        is HistoricalCoverageStatus.Qualified -> status.reasons.sorted()
    }
