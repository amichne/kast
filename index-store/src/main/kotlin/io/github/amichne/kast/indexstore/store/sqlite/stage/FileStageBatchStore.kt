package io.github.amichne.kast.indexstore.store

import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.SourceIndexFilePolicy
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.api.stage.RelationshipFileStageUpdate
import io.github.amichne.kast.indexstore.api.stage.SemanticGraphFileStageRemoval
import io.github.amichne.kast.indexstore.api.stage.SemanticGraphFileStageUpdate
import io.github.amichne.kast.indexstore.api.stage.SourceFileStageUpdate
import java.sql.Connection

internal class FileStageBatchStore(
    private val state: SqliteSourceIndexStoreState,
    private val mutations: SourceIndexFileMutations,
    private val references: SourceIndexReferenceStore,
    private val declarations: SourceIndexDeclarationStore,
    private val stages: FileStageInventoryStore,
) {
    fun commitSourceBatch(updates: List<SourceFileStageUpdate>) {
        if (updates.isEmpty()) return
        requireUniquePaths(updates.map { update -> update.work.path })
        transaction { conn ->
            updates.forEach { update ->
                stages.requireCurrentWorkInTransaction(conn, update.work, inventoryRequired = true)
            }
            mutations.internPathsInTransaction(conn, updates.map { update -> update.update.path })
            mutations.internFqNamesInTransaction(
                conn,
                updates.flatMapTo(mutableSetOf()) { update -> mutations.fqNamesFor(update.update) },
            )
            updates.forEach { update ->
                mutations.insertFileDataInTransaction(conn, update.update)
                stages.writeOutcomeInTransaction(conn, update.work, update.limitations)
            }
            stages.recomputeModuleProgressInTransaction(conn)
        }
    }

    fun commitRelationshipBatch(updates: List<RelationshipFileStageUpdate>) {
        if (updates.isEmpty()) return
        requireUniquePaths(updates.map { update -> update.work.path })
        val normalized = updates.map(::normalize)
        transaction { conn ->
            normalized.forEach { update ->
                stages.requireCurrentWorkInTransaction(conn, update.work, inventoryRequired = true)
            }
            mutations.internPathsInTransaction(
                conn,
                normalized.flatMap { update ->
                    buildList {
                        add(update.work.path)
                        update.references.mapNotNullTo(this) { reference -> reference.targetPath }
                    }
                },
            )
            mutations.internFqNamesInTransaction(
                conn,
                normalized.flatMapTo(mutableSetOf()) { update ->
                    buildList {
                        update.references.forEach { reference ->
                            add(reference.targetFqName)
                            reference.sourceFqName?.let(::add)
                        }
                        update.declarations.forEach { declaration ->
                            add(declaration.fqName)
                            addAll(declaration.supertypes)
                        }
                    }
                },
            )
            normalized.forEach { update ->
                references.clearReferencesFromFileInTransaction(conn, update.work.path)
                declarations.clearDeclarationsFromFileInTransaction(conn, update.work.path)
                update.references.forEach { reference -> insertReference(conn, reference) }
                update.declarations.forEach { declaration ->
                    declarations.insertDeclarationInTransaction(conn, declaration)
                }
                stages.writeOutcomeInTransaction(conn, update.work, update.limitations)
            }
            stages.recomputeModuleProgressInTransaction(conn)
        }
    }

    internal fun commitSemanticStageStateInTransaction(
        conn: Connection,
        updates: List<SemanticGraphFileStageUpdate>,
        removals: List<SemanticGraphFileStageRemoval>,
    ) {
        requireUniquePaths(updates.map { update -> update.work.path })
        requireUniquePaths(removals.map(SemanticGraphFileStageRemoval::outcomePath))
        val overlap = updates.mapTo(mutableSetOf()) { update -> update.work.path }
            .intersect(removals.mapTo(mutableSetOf(), SemanticGraphFileStageRemoval::outcomePath))
        require(overlap.isEmpty()) { "Semantic graph paths cannot be updated and removed in one batch" }
        updates.forEach { update ->
            stages.requireCurrentWorkInTransaction(conn, update.work, inventoryRequired = false)
            stages.writeOutcomeInTransaction(conn, update.work, update.limitations)
        }
        removals.forEach { removal ->
            stages.deleteOutcomeInTransaction(conn, removal.outcomePath, FileIndexStage.SEMANTIC_GRAPH)
        }
        stages.recomputeModuleProgressInTransaction(conn)
    }

    private fun transaction(write: (Connection) -> Unit) {
        synchronized(state.writeLock) {
            val conn = state.connection()
            state.loadInterningTables(conn)
            conn.autoCommit = false
            try {
                write(conn)
                state.incrementGenerationInTransaction(conn)
                conn.commit()
            } catch (failure: Exception) {
                state.rollbackAndReloadPrefixes(conn)
                throw failure
            } finally {
                conn.autoCommit = true
            }
        }
    }

    private fun normalize(update: RelationshipFileStageUpdate): RelationshipFileStageUpdate =
        update.copy(
            references = update.references.map { reference ->
                if (reference.targetPath?.let(SourceIndexFilePolicy::isEligible) != false) {
                    reference
                } else {
                    reference.copy(targetPath = null, targetOffset = null)
                }
            },
        )

    private fun insertReference(conn: Connection, reference: SymbolReferenceRow) {
        references.upsertSymbolReferenceInTransaction(
            conn = conn,
            sourcePath = reference.sourcePath,
            sourceOffset = reference.sourceOffset,
            sourceFqName = reference.sourceFqName,
            targetFqName = reference.targetFqName,
            targetPath = reference.targetPath,
            targetOffset = reference.targetOffset,
            edgeKind = reference.edgeKind,
        )
    }

    private fun requireUniquePaths(paths: List<String>) {
        require(paths.size == paths.toSet().size) { "A file stage batch cannot contain duplicate paths" }
    }
}
