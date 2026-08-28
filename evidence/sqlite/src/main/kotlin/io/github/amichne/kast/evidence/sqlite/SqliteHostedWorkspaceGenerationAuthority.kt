package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.evidence.contract.MutationDatabaseLocation
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

enum class HostedWorkspaceGenerationFailure {
    STORAGE_UNAVAILABLE,
    CORRUPT_STATE,
    EXHAUSTED,
    STALE_SOURCE_STATE,
}

sealed interface HostedWorkspaceGenerationIssuance {
    data class Issued(val generation: EvidenceGeneration) : HostedWorkspaceGenerationIssuance
    data class Rejected(val failure: HostedWorkspaceGenerationFailure) :
        HostedWorkspaceGenerationIssuance
}

sealed interface HostedWorkspaceGenerationResumption {
    data class Resumed(
        val sourceState: WorkspaceStateIdentity,
        val generation: EvidenceGeneration,
    ) : HostedWorkspaceGenerationResumption

    data class Rejected(val failure: HostedWorkspaceGenerationFailure) :
        HostedWorkspaceGenerationResumption
}

/** Durable monotonic mapping from exact source state to semantic generation. */
object SqliteHostedWorkspaceGenerationAuthority {
    fun issue(
        location: MutationDatabaseLocation,
        state: WorkspaceStateIdentity,
    ): HostedWorkspaceGenerationIssuance {
        val path = prepareHostedDatabasePath(location.valueAtSqliteBoundary())
            ?: return rejected(HostedWorkspaceGenerationFailure.STORAGE_UNAVAILABLE)
        val database = when (val admitted = SqliteMutationRecoveryDatabase.admit(path)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejected(
                HostedWorkspaceGenerationFailure.STORAGE_UNAVAILABLE,
            )
        }
        return try {
            val connections = SqliteMutationRecoveryConnections(database)
            connections.initialize()
            connections.use { connection ->
                connection.autoCommit = false
                try {
                    initializeTables(connection)
                    val generation = when (val issued = issueInside(connection, state)) {
                        is GenerationRow.Issued -> issued.generation
                        is GenerationRow.Rejected -> {
                            connection.rollback()
                            return@use rejected(issued.failure)
                        }
                    }
                    connection.commit()
                    HostedWorkspaceGenerationIssuance.Issued(generation)
                } catch (failure: Exception) {
                    runCatching { connection.rollback() }
                    throw failure
                } finally {
                    connection.autoCommit = true
                }
            }
        } catch (_: Exception) {
            rejected(HostedWorkspaceGenerationFailure.STORAGE_UNAVAILABLE)
        }
    }

    /**
     * Resumes the latest state in the exact bounded-source lineage, creating its initial durable
     * publication when no lineage exists. Repository content is never observed at this boundary.
     */
    fun resume(
        location: MutationDatabaseLocation,
        basis: WorkspaceStateIdentity,
    ): HostedWorkspaceGenerationResumption {
        val path = prepareHostedDatabasePath(location.valueAtSqliteBoundary())
            ?: return resumptionRejected(HostedWorkspaceGenerationFailure.STORAGE_UNAVAILABLE)
        val database = when (val admitted = SqliteMutationRecoveryDatabase.admit(path)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return resumptionRejected(
                HostedWorkspaceGenerationFailure.STORAGE_UNAVAILABLE,
            )
        }
        return try {
            val connections = SqliteMutationRecoveryConnections(database)
            connections.initialize()
            connections.use { connection ->
                connection.autoCommit = false
                try {
                    initializeTables(connection)
                    val publication = when (val lineage = readLineageHead(connection)) {
                        LineageRow.Empty -> {
                            val generation = when (val issued = issueInside(connection, basis)) {
                                is GenerationRow.Issued -> issued.generation
                                is GenerationRow.Rejected -> {
                                    connection.rollback()
                                    return@use resumptionRejected(issued.failure)
                                }
                            }
                            connection.prepareStatement(
                                """INSERT INTO hosted_workspace_source_lineage(
                                    basis_source_state,
                                    current_source_state,
                                    current_generation
                                ) VALUES (?, ?, ?)""",
                            ).use { statement ->
                                statement.setString(1, basis.value)
                                statement.setString(2, basis.value)
                                statement.setLong(3, generation.value)
                                statement.executeUpdate()
                            }
                            HostedWorkspaceGenerationResumption.Resumed(basis, generation)
                        }
                        is LineageRow.Rejected -> {
                            connection.rollback()
                            return@use resumptionRejected(lineage.failure)
                        }
                        is LineageRow.Current -> {
                            val head = lineage.head
                            if (head.basis == basis) {
                                HostedWorkspaceGenerationResumption.Resumed(
                                    head.current,
                                    head.generation,
                                )
                            } else {
                                val successor = transitionBasis(head.current, basis) ?: run {
                                    connection.rollback()
                                    return@use resumptionRejected(
                                        HostedWorkspaceGenerationFailure.CORRUPT_STATE,
                                    )
                                }
                                val generation = when (
                                    val issued = issueInside(connection, successor)
                                ) {
                                    is GenerationRow.Issued -> issued.generation
                                    is GenerationRow.Rejected -> {
                                        connection.rollback()
                                        return@use resumptionRejected(issued.failure)
                                    }
                                }
                                if (generation.value <= head.generation.value) {
                                    connection.rollback()
                                    return@use resumptionRejected(
                                        HostedWorkspaceGenerationFailure.CORRUPT_STATE,
                                    )
                                }
                                val updated = connection.prepareStatement(
                                    """UPDATE hosted_workspace_source_lineage
                                        SET basis_source_state = ?,
                                            current_source_state = ?,
                                            current_generation = ?
                                        WHERE basis_source_state = ?
                                          AND current_source_state = ?
                                          AND current_generation = ?""",
                                ).use { statement ->
                                    statement.setString(1, basis.value)
                                    statement.setString(2, successor.value)
                                    statement.setLong(3, generation.value)
                                    statement.setString(4, head.basis.value)
                                    statement.setString(5, head.current.value)
                                    statement.setLong(6, head.generation.value)
                                    statement.executeUpdate()
                                }
                                if (updated != 1) {
                                    connection.rollback()
                                    return@use resumptionRejected(
                                        HostedWorkspaceGenerationFailure.STALE_SOURCE_STATE,
                                    )
                                }
                                HostedWorkspaceGenerationResumption.Resumed(successor, generation)
                            }
                        }
                    }
                    connection.commit()
                    publication
                } catch (failure: Exception) {
                    runCatching { connection.rollback() }
                    throw failure
                } finally {
                    connection.autoCommit = true
                }
            }
        } catch (_: Exception) {
            resumptionRejected(HostedWorkspaceGenerationFailure.STORAGE_UNAVAILABLE)
        }
    }

    /** Advances only the lineage whose current durable state is exactly [prior]. */
    fun advance(
        location: MutationDatabaseLocation,
        prior: WorkspaceStateIdentity,
        next: WorkspaceStateIdentity,
    ): HostedWorkspaceGenerationIssuance {
        val path = prepareHostedDatabasePath(location.valueAtSqliteBoundary())
            ?: return rejected(HostedWorkspaceGenerationFailure.STORAGE_UNAVAILABLE)
        val database = when (val admitted = SqliteMutationRecoveryDatabase.admit(path)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejected(
                HostedWorkspaceGenerationFailure.STORAGE_UNAVAILABLE,
            )
        }
        return try {
            val connections = SqliteMutationRecoveryConnections(database)
            connections.initialize()
            connections.use { connection ->
                connection.autoCommit = false
                try {
                    initializeTables(connection)
                    val head = when (val lineage = readLineageHead(connection)) {
                        LineageRow.Empty -> {
                            connection.rollback()
                            return@use rejected(
                                HostedWorkspaceGenerationFailure.STALE_SOURCE_STATE,
                            )
                        }
                        is LineageRow.Rejected -> {
                            connection.rollback()
                            return@use rejected(lineage.failure)
                        }
                        is LineageRow.Current -> lineage.head
                    }
                    if (head.current != prior) {
                        connection.rollback()
                        return@use rejected(HostedWorkspaceGenerationFailure.STALE_SOURCE_STATE)
                    }
                    val generation = when (val issued = issueInside(connection, next)) {
                        is GenerationRow.Issued -> issued.generation
                        is GenerationRow.Rejected -> {
                            connection.rollback()
                            return@use rejected(issued.failure)
                        }
                    }
                    if (generation.value <= head.generation.value) {
                        connection.rollback()
                        return@use rejected(
                            HostedWorkspaceGenerationFailure.STALE_SOURCE_STATE,
                        )
                    }
                    val updated = connection.prepareStatement(
                        """UPDATE hosted_workspace_source_lineage
                            SET current_source_state = ?, current_generation = ?
                            WHERE basis_source_state = ?
                              AND current_source_state = ?
                              AND current_generation = ?""",
                    ).use { statement ->
                        statement.setString(1, next.value)
                        statement.setLong(2, generation.value)
                        statement.setString(3, head.basis.value)
                        statement.setString(4, prior.value)
                        statement.setLong(5, head.generation.value)
                        statement.executeUpdate()
                    }
                    if (updated != 1) {
                        connection.rollback()
                        return@use rejected(HostedWorkspaceGenerationFailure.STALE_SOURCE_STATE)
                    }
                    connection.commit()
                    HostedWorkspaceGenerationIssuance.Issued(generation)
                } catch (failure: Exception) {
                    runCatching { connection.rollback() }
                    throw failure
                } finally {
                    connection.autoCommit = true
                }
            }
        } catch (_: Exception) {
            rejected(HostedWorkspaceGenerationFailure.STORAGE_UNAVAILABLE)
        }
    }

    private fun initializeTables(connection: java.sql.Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """CREATE TABLE IF NOT EXISTS hosted_workspace_generation (
                    source_state TEXT PRIMARY KEY NOT NULL,
                    generation INTEGER NOT NULL UNIQUE CHECK(generation >= 0)
                ) WITHOUT ROWID""",
            )
            statement.execute(
                """CREATE TABLE IF NOT EXISTS hosted_workspace_source_lineage (
                    basis_source_state TEXT PRIMARY KEY NOT NULL,
                    current_source_state TEXT NOT NULL UNIQUE,
                    current_generation INTEGER NOT NULL UNIQUE CHECK(current_generation >= 0)
                ) WITHOUT ROWID""",
            )
        }
    }

    private sealed interface GenerationRow {
        data class Issued(val generation: EvidenceGeneration) : GenerationRow
        data class Rejected(val failure: HostedWorkspaceGenerationFailure) : GenerationRow
    }

    private data class LineageHead(
        val basis: WorkspaceStateIdentity,
        val current: WorkspaceStateIdentity,
        val generation: EvidenceGeneration,
    )

    private sealed interface LineageRow {
        data object Empty : LineageRow
        data class Current(val head: LineageHead) : LineageRow
        data class Rejected(val failure: HostedWorkspaceGenerationFailure) : LineageRow
    }

    private fun readLineageHead(connection: java.sql.Connection): LineageRow =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """SELECT basis_source_state, current_source_state, current_generation
                    FROM hosted_workspace_source_lineage
                    LIMIT 2""",
            ).use { rows ->
                if (!rows.next()) return@use LineageRow.Empty
                val rawBasis = rows.getString("basis_source_state")
                val rawCurrent = rows.getString("current_source_state")
                val rawGeneration = rows.getLong("current_generation")
                if (rows.next()) {
                    return@use LineageRow.Rejected(
                        HostedWorkspaceGenerationFailure.CORRUPT_STATE,
                    )
                }
                val basis = when (val parsed = WorkspaceStateIdentity.parse(rawBasis)) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return@use LineageRow.Rejected(
                        HostedWorkspaceGenerationFailure.CORRUPT_STATE,
                    )
                }
                val current = when (val parsed = WorkspaceStateIdentity.parse(rawCurrent)) {
                    is Refinement.Refined -> parsed.value
                    is Refinement.Rejected -> return@use LineageRow.Rejected(
                        HostedWorkspaceGenerationFailure.CORRUPT_STATE,
                    )
                }
                val generation = when (val validated = validateLineageGeneration(
                    connection,
                    current,
                    rawGeneration,
                )) {
                    is GenerationRow.Issued -> validated.generation
                    is GenerationRow.Rejected -> return@use LineageRow.Rejected(validated.failure)
                }
                LineageRow.Current(LineageHead(basis, current, generation))
            }
        }

    private fun transitionBasis(
        current: WorkspaceStateIdentity,
        basis: WorkspaceStateIdentity,
    ): WorkspaceStateIdentity? {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("kast-hosted-source-basis-transition-v1".toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update(current.value.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update(basis.value.toByteArray(StandardCharsets.UTF_8))
        return when (
            val parsed = WorkspaceStateIdentity.parse(HexFormat.of().formatHex(digest.digest()))
        ) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> null
        }
    }

    private fun issueInside(
        connection: java.sql.Connection,
        state: WorkspaceStateIdentity,
    ): GenerationRow {
        val existing = generationFor(connection, state)
        val raw = existing ?: run {
            val maximum = connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT MAX(generation) AS maximum FROM hosted_workspace_generation",
                ).use { rows ->
                    if (!rows.next() || rows.getObject("maximum") == null) -1L
                    else rows.getLong("maximum")
                }
            }
            if (maximum == Long.MAX_VALUE) {
                return GenerationRow.Rejected(HostedWorkspaceGenerationFailure.EXHAUSTED)
            }
            (maximum + 1).also { next ->
                connection.prepareStatement(
                    "INSERT INTO hosted_workspace_generation(source_state, generation) VALUES (?, ?)",
                ).use { statement ->
                    statement.setString(1, state.value)
                    statement.setLong(2, next)
                    statement.executeUpdate()
                }
            }
        }
        return when (val parsed = EvidenceGeneration.parse(raw)) {
            is Refinement.Refined -> GenerationRow.Issued(parsed.value)
            is Refinement.Rejected -> GenerationRow.Rejected(
                HostedWorkspaceGenerationFailure.CORRUPT_STATE,
            )
        }
    }

    private fun validateLineageGeneration(
        connection: java.sql.Connection,
        state: WorkspaceStateIdentity,
        currentGeneration: Long,
    ): GenerationRow {
        if (generationFor(connection, state) != currentGeneration) {
            return GenerationRow.Rejected(HostedWorkspaceGenerationFailure.CORRUPT_STATE)
        }
        return when (val parsed = EvidenceGeneration.parse(currentGeneration)) {
            is Refinement.Refined -> GenerationRow.Issued(parsed.value)
            is Refinement.Rejected -> GenerationRow.Rejected(
                HostedWorkspaceGenerationFailure.CORRUPT_STATE,
            )
        }
    }

    private fun generationFor(
        connection: java.sql.Connection,
        state: WorkspaceStateIdentity,
    ): Long? = connection.prepareStatement(
        "SELECT generation FROM hosted_workspace_generation WHERE source_state = ?",
    ).use { statement ->
        statement.setString(1, state.value)
        statement.executeQuery().use { rows ->
            if (rows.next()) rows.getLong("generation") else null
        }
    }

    private fun rejected(failure: HostedWorkspaceGenerationFailure) =
        HostedWorkspaceGenerationIssuance.Rejected(failure)

    private fun resumptionRejected(failure: HostedWorkspaceGenerationFailure) =
        HostedWorkspaceGenerationResumption.Rejected(failure)
}
