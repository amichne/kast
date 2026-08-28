package io.github.amichne.kast.evidence.sqlite

import io.github.amichne.kast.evidence.contract.MutationDatabaseLocation
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity

enum class HostedWorkspaceGenerationFailure {
    STORAGE_UNAVAILABLE,
    CORRUPT_STATE,
    EXHAUSTED,
}

sealed interface HostedWorkspaceGenerationIssuance {
    data class Issued(val generation: EvidenceGeneration) : HostedWorkspaceGenerationIssuance
    data class Rejected(val failure: HostedWorkspaceGenerationFailure) :
        HostedWorkspaceGenerationIssuance
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
                    connection.createStatement().use { statement ->
                        statement.execute(
                            """CREATE TABLE IF NOT EXISTS hosted_workspace_generation (
                                source_state TEXT PRIMARY KEY NOT NULL,
                                generation INTEGER NOT NULL UNIQUE CHECK(generation >= 0)
                            ) WITHOUT ROWID""",
                        )
                    }
                    val existing = connection.prepareStatement(
                        "SELECT generation FROM hosted_workspace_generation WHERE source_state = ?",
                    ).use { statement ->
                        statement.setString(1, state.value)
                        statement.executeQuery().use { rows ->
                            if (rows.next()) rows.getLong("generation") else null
                        }
                    }
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
                            connection.rollback()
                            return@use rejected(HostedWorkspaceGenerationFailure.EXHAUSTED)
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
                    val generation = when (val parsed = EvidenceGeneration.parse(raw)) {
                        is Refinement.Refined -> parsed.value
                        is Refinement.Rejected -> {
                            connection.rollback()
                            return@use rejected(HostedWorkspaceGenerationFailure.CORRUPT_STATE)
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

    private fun rejected(failure: HostedWorkspaceGenerationFailure) =
        HostedWorkspaceGenerationIssuance.Rejected(failure)
}
