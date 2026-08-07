package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileStageFailureExternalizationResult
import io.github.amichne.kast.indexstore.api.index.FileStageFailureId
import io.github.amichne.kast.indexstore.api.index.FileStageLimitation
import io.github.amichne.kast.indexstore.api.index.FileStageOutcomeStatus
import io.github.amichne.kast.indexstore.api.index.WorkspaceSourcePath
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.api.stage.RelationshipFileStageUpdate
import io.github.amichne.kast.indexstore.api.stage.FileStageFailureUpdate
import io.github.amichne.kast.indexstore.api.stage.SemanticGraphFileStageRemoval
import io.github.amichne.kast.indexstore.api.stage.SemanticGraphFileStageFailureUpdate
import io.github.amichne.kast.indexstore.api.stage.SemanticGraphFileStageUpdate
import io.github.amichne.kast.indexstore.api.stage.SourceFileStageUpdate
import java.nio.file.Path
import java.sql.Connection

internal class FileStageBatchStore(
    private val state: SqliteSourceIndexStoreState,
    private val mutations: SourceIndexFileMutations,
    private val references: SourceIndexReferenceStore,
    private val declarations: SourceIndexDeclarationStore,
    private val stages: FileStageInventoryStore,
    private val semanticGraph: SemanticGraphWriter,
) {
    private val failureStore = FileStageFailureStore(state)

    fun commitSourceBatch(updates: List<SourceFileStageUpdate>) {
        if (updates.isEmpty()) return
        requireUniquePaths(updates.map { update -> update.work.path })
        transaction { conn ->
            updates.forEach { update ->
                stages.requireCurrentWorkInTransaction(conn, update.work, inventoryRequired = true)
            }
            mutations.internPathsInTransaction(
                conn,
                updates.map { update -> update.work.path.toDatabasePath() },
            )
            mutations.internFqNamesInTransaction(
                conn,
                updates.flatMapTo(mutableSetOf()) { update -> mutations.fqNamesFor(update.update) },
            )
            updates.forEach { update ->
                mutations.insertFileDataInTransaction(
                    conn,
                    update.update.copy(path = update.work.path.toDatabasePath()),
                )
                stages.writeOutcomeInTransaction(conn, update.work, update.limitations)
            }
            stages.recomputeModuleProgressInTransaction(conn)
        }
    }

    fun commitRelationshipBatch(
        updates: List<RelationshipFileStageUpdate>,
        failures: List<FileStageFailureUpdate> = emptyList(),
    ) {
        if (updates.isEmpty() && failures.isEmpty()) return
        requireUniquePaths(
            updates.map { update -> update.work.path } + failures.map { failure -> failure.work.path },
        )
        val prepared = updates.map(::prepare)
        transaction { conn ->
            prepared.forEach { update ->
                stages.requireCurrentWorkInTransaction(conn, update.work, inventoryRequired = true)
            }
            failures.forEach { failure ->
                stages.requireCurrentWorkInTransaction(conn, failure.work, inventoryRequired = true)
            }
            mutations.internPathsInTransaction(
                conn,
                prepared.flatMap { update ->
                    buildList {
                        add(update.work.path.toDatabasePath())
                        update.references.mapNotNullTo(this) { reference ->
                            reference.targetPath?.toDatabasePath()
                        }
                    }
                } + failures.map { failure -> failure.work.path.toDatabasePath() },
            )
            mutations.internFqNamesInTransaction(
                conn,
                prepared.flatMapTo(mutableSetOf()) { update ->
                    buildList {
                        update.references.forEach { reference ->
                            add(reference.row.targetFqName)
                            reference.row.sourceFqName?.let(::add)
                        }
                        update.declarations.forEach { declaration ->
                            add(declaration.fqName)
                            addAll(declaration.supertypes)
                        }
                    }
                },
            )
            prepared.forEach { update ->
                references.clearReferencesFromFileInTransaction(conn, update.work.path)
                declarations.clearDeclarationsFromFileInTransaction(conn, update.work.path)
                update.references.forEach { reference ->
                    insertReference(conn, update.work.path, reference)
                }
                update.declarations.forEach { declaration ->
                    declarations.insertDeclarationInTransaction(conn, update.work.path, declaration)
                }
                stages.writeOutcomeInTransaction(conn, update.work, update.limitations)
            }
            failures.forEach { failure ->
                references.clearReferencesFromFileInTransaction(conn, failure.work.path)
                declarations.clearDeclarationsFromFileInTransaction(conn, failure.work.path)
                val attemptCount = stages.nextFailureAttemptInTransaction(conn, failure.work, failure.code)
                if (attemptCount.value >= 3) {
                    stages.writeOutcomeInTransaction(
                        conn,
                        failure.work,
                        listOf(FileStageLimitation.PSI_UNAVAILABLE),
                        attemptCount,
                    )
                } else {
                    failureStore.writeFailureOutcomeInTransaction(
                        conn = conn,
                        work = failure.work,
                        code = failure.code,
                        message = failure.message,
                        attemptCount = attemptCount,
                    )
                }
            }
            stages.recomputeModuleProgressInTransaction(conn)
        }
    }

    fun externalizeFileStageFailure(
        failureId: FileStageFailureId,
    ): FileStageFailureExternalizationResult = state.writeTransaction { conn ->
        state.loadInterningTables(conn)
        val current = failureStore.currentFailureByIdInTransaction(conn, failureId)
        when (current?.status) {
            null -> FileStageFailureExternalizationResult.NOT_FOUND
            FileStageOutcomeStatus.EXTERNAL_BOUNDARY ->
                FileStageFailureExternalizationResult.ALREADY_EXTERNAL
            FileStageOutcomeStatus.FAILED -> {
                check(current.stage == FileIndexStage.RELATIONSHIPS) {
                    "Only relationship-stage failures are currently externalizable"
                }
                references.clearReferencesFromFileInTransaction(conn, current.path)
                declarations.clearDeclarationsFromFileInTransaction(conn, current.path)
                failureStore.markFailureExternalInTransaction(conn, failureId)
                stages.recomputeModuleProgressInTransaction(conn)
                state.incrementGenerationInTransaction(conn)
                FileStageFailureExternalizationResult.EXTERNALIZED
            }
            FileStageOutcomeStatus.COMPLETE,
            FileStageOutcomeStatus.LIMITED,
            -> error("Failure identity points to a non-failure outcome")
        }
    }

    internal fun commitSemanticStageStateInTransaction(
        conn: Connection,
        updates: List<SemanticGraphFileStageUpdate>,
        failures: List<SemanticGraphFileStageFailureUpdate>,
        removals: List<SemanticGraphFileStageRemoval>,
    ) {
        requireUniquePaths(
            updates.map { update -> update.work.path } +
                failures.map { failure -> failure.work.path } +
                removals.map(SemanticGraphFileStageRemoval::outcomePath),
        )
        updates.forEach { update ->
            stages.requireCurrentWorkInTransaction(conn, update.work, inventoryRequired = false)
            stages.writeOutcomeInTransaction(conn, update.work, update.limitations)
        }
        failures.forEach { failure ->
            stages.requireCurrentWorkInTransaction(conn, failure.work, inventoryRequired = false)
            val attemptCount = stages.nextFailureAttemptInTransaction(conn, failure.work, failure.code)
            if (attemptCount.value >= 3) {
                stages.writeOutcomeInTransaction(
                    conn,
                    failure.work,
                    listOf(FileStageLimitation.PSI_UNAVAILABLE),
                    attemptCount,
                )
            } else {
                failureStore.writeFailureOutcomeInTransaction(
                    conn = conn,
                    work = failure.work,
                    code = failure.code,
                    message = failure.message,
                    attemptCount = attemptCount,
                )
            }
        }
        removals.forEach { removal ->
            stages.deleteOutcomeInTransaction(conn, removal.outcomePath, FileIndexStage.SEMANTIC_GRAPH)
        }
        stages.recomputeModuleProgressInTransaction(conn)
    }

    private fun transaction(write: (Connection) -> Unit) {
        state.writeTransaction { conn ->
            state.loadInterningTables(conn)
            write(conn)
            state.incrementGenerationInTransaction(conn)
        }
    }

    private fun prepare(update: RelationshipFileStageUpdate): PreparedRelationshipUpdate =
        PreparedRelationshipUpdate(
            update = update,
            references = update.references.map { reference ->
                PreparedSymbolReference(
                    row = reference,
                    targetPath = reference.targetPath?.let { path ->
                        state.sourceFilePolicy.sourcePath(Path.of(path))
                    },
                )
            },
        )

    private fun insertReference(
        conn: Connection,
        sourcePath: WorkspaceSourcePath,
        reference: PreparedSymbolReference,
    ) {
        val row = reference.row
        references.upsertSymbolReferenceInTransaction(
            conn = conn,
            sourcePath = sourcePath,
            sourceOffset = row.sourceOffset,
            sourceFqName = row.sourceFqName,
            targetFqName = row.targetFqName,
            targetPath = reference.targetPath,
            targetOffset = reference.targetPath?.let { row.targetOffset },
            edgeKind = row.edgeKind,
        )
    }

    private data class PreparedRelationshipUpdate(
        val update: RelationshipFileStageUpdate,
        val references: List<PreparedSymbolReference>,
    ) {
        val work get() = update.work
        val declarations get() = update.declarations
        val limitations get() = update.limitations
    }

    private data class PreparedSymbolReference(
        val row: SymbolReferenceRow,
        val targetPath: WorkspaceSourcePath?,
    )

    private fun requireUniquePaths(paths: List<WorkspaceSourcePath>) {
        require(paths.size == paths.toSet().size) { "A file stage batch cannot contain duplicate paths" }
    }
}
