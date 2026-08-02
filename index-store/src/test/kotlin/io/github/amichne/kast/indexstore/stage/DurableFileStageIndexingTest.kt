package io.github.amichne.kast.indexstore

import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileStageScopeCoverage
import io.github.amichne.kast.indexstore.api.reference.DeclarationKind
import io.github.amichne.kast.indexstore.api.reference.DeclarationRow
import io.github.amichne.kast.indexstore.api.reference.DeclarationVisibility
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.api.stage.RelationshipFileStageUpdate
import io.github.amichne.kast.indexstore.api.stage.SourceFileStageUpdate
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DurableFileStageIndexingTest : DurableFileStageTestFixture() {
    @Test
    fun `restart reuses unchanged outcomes and invalidates only affected file stages`() {
        val paths = listOf(file("src/A.kt"), file("src/B.kt"))
        val entries = paths.mapIndexed { index, path -> inventory(path, hash('a' + index), ":app[main]") }
        val versions = versions("1")

        val committedGeneration = SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            store.reconcileFileInventory(entries, versions)
            commitSources(store, FileIndexStage.SOURCE, paths)
            commitRelationships(store, paths)
            store.readGeneration()
        }

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.reconcileFileInventory(entries, versions)
            assertEquals(committedGeneration, store.readGeneration())
            assertTrue(store.pendingFileStages(FileIndexStage.SOURCE).isEmpty())
            assertTrue(store.pendingFileStages(FileIndexStage.RELATIONSHIPS).isEmpty())

            val changed = entries.map { entry ->
                if (entry.path.rawPath == paths.first()) entry.copy(contentHash = hash('c')) else entry
            }
            store.reconcileFileInventory(changed, versions)
            assertEquals(
                listOf(paths.first()),
                store.pendingFileStages(FileIndexStage.SOURCE).map { work -> work.path.rawPath },
            )
            assertEquals(
                listOf(paths.first()),
                store.pendingFileStages(FileIndexStage.RELATIONSHIPS).map { work -> work.path.rawPath },
            )

            commitSources(store, FileIndexStage.SOURCE, listOf(paths.first()))
            store.reconcileFileInventory(changed, versions.copy(relationships = version("2")))
            assertTrue(store.pendingFileStages(FileIndexStage.SOURCE).isEmpty())
            assertEquals(
                paths,
                store.pendingFileStages(FileIndexStage.RELATIONSHIPS).map { work -> work.path.rawPath },
            )
        }
    }

    @Test
    fun `file stages accept canonical source identity through a symlink alias`() {
        val normalized = workspaceRoot.toAbsolutePath().normalize()
        val canonicalDirectory = normalized.resolve("canonical").also(Files::createDirectories)
        val canonicalCaller = writeKotlinFile(canonicalDirectory.resolve("Caller.kt"))
        val canonicalTarget = writeKotlinFile(canonicalDirectory.resolve("Target.kt"))
        val aliasDirectory = normalized.resolve("alias")
            .also { alias -> Files.createSymbolicLink(alias, canonicalDirectory) }
        val aliasCaller = aliasDirectory.resolve(canonicalCaller.fileName)
        val aliasTarget = aliasDirectory.resolve(canonicalTarget.fileName)
        val callerPath = workspaceSourceRawPath(normalized, canonicalCaller.toString())
        val targetPath = workspaceSourceRawPath(normalized, canonicalTarget.toString())
        val declaration = DeclarationRow(
            fqName = "demo.Caller",
            kind = DeclarationKind.CLASS,
            visibility = DeclarationVisibility.INTERNAL,
            filePath = aliasCaller.toString(),
            declarationOffset = 1,
            modulePath = ":app",
            sourceSet = "main",
        )

        SqliteSourceIndexStore(normalized).use { store ->
            store.ensureSchema()
            store.reconcileFileInventory(
                listOf(inventory(normalized, callerPath, hash('a'), ":app[main]")),
                versions("1"),
            )
            val sourceWork = store.pendingFileStages(FileIndexStage.SOURCE).single()
            store.commitSourceBatch(
                listOf(
                    SourceFileStageUpdate(
                        work = sourceWork,
                        scannedContentHash = sourceWork.contentHash,
                        update = sourceUpdate(aliasCaller.toString()),
                    ),
                ),
            )
            val work = store.pendingFileStages(FileIndexStage.RELATIONSHIPS).single()

            store.commitRelationshipBatch(
                listOf(
                    RelationshipFileStageUpdate(
                        work = work,
                        scannedContentHash = work.contentHash,
                        references = listOf(reference(aliasCaller.toString(), "demo.Target", aliasTarget.toString())),
                        declarations = listOf(declaration),
                    ),
                ),
            )

            assertEquals(
                listOf(reference(callerPath, "demo.Target", targetPath)),
                store.referencesFromFile(callerPath),
            )
            assertEquals(
                listOf(declaration.copy(filePath = callerPath)),
                store.searchDeclarations(NonBlankString("demo.Caller"), PositiveInt(10)),
            )
        }
    }

    @Test
    fun `independent sessions complete only the scopes backed by persisted outcomes`() {
        val app = listOf(
            file("app/src/main/kotlin/App.kt"),
            file("app/src/main/kotlin/AppSibling.kt"),
        )
        val lib = file("lib/src/main/kotlin/Lib.kt")
        val entries = listOf(
            inventory(app.first(), hash('a'), ":app[main]"),
            inventory(app.last(), hash('b'), ":app[main]"),
            inventory(lib, hash('c'), ":lib[main]"),
        )

        SqliteSourceIndexStore(workspaceRoot).use { firstSession ->
            firstSession.ensureSchema()
            firstSession.reconcileFileInventory(entries, versions("1"))
            commitSources(firstSession, FileIndexStage.SOURCE, app + lib)
            commitRelationships(firstSession, listOf(app.first()))
            app.forEach { path ->
                assertTrue(
                    firstSession.fileStageScopeCoverage(FileIndexStage.RELATIONSHIPS, path) is
                        FileStageScopeCoverage.Limited,
                )
            }
            assertTrue(
                firstSession.fileStageScopeCoverage(FileIndexStage.RELATIONSHIPS, lib) is
                    FileStageScopeCoverage.Limited,
            )
            assertTrue(firstSession.completedModules().isEmpty())
        }

        SqliteSourceIndexStore(workspaceRoot).use { secondSession ->
            assertEquals(
                listOf(app.last(), lib),
                secondSession.pendingFileStages(FileIndexStage.RELATIONSHIPS).map { work -> work.path.rawPath },
            )
            commitRelationships(secondSession, listOf(app.last()))
            app.forEach { path ->
                assertTrue(
                    secondSession.fileStageScopeCoverage(FileIndexStage.RELATIONSHIPS, path) is
                        FileStageScopeCoverage.Complete,
                )
            }
            assertTrue(
                secondSession.fileStageScopeCoverage(FileIndexStage.RELATIONSHIPS, lib) is
                    FileStageScopeCoverage.Limited,
            )
            assertEquals(setOf(":app[main]"), secondSession.completedModules())
        }
    }

    @Test
    fun `interrupted multi session batches converge with uninterrupted batches`() {
        val resumedRoot = workspaceRoot.resolve("resumed")
        val uninterruptedRoot = workspaceRoot.resolve("uninterrupted")
        val relativePaths = listOf("src/A.kt", "src/B.kt", "src/C.kt")

        val resumedFacts = indexInBatches(resumedRoot, relativePaths, reopenAfterFirstBatch = true)
        val uninterruptedFacts = indexInBatches(uninterruptedRoot, relativePaths, reopenAfterFirstBatch = false)

        assertEquals(uninterruptedFacts, resumedFacts)
    }

    @Test
    fun `changed and removed targets preserve fq edges while invalidating inbound relationship outcomes`() {
        listOf(false, true).forEach { removeTarget ->
            val scenario = if (removeTarget) "removed" else "changed"
            val scenarioRoot = Files.createDirectories(workspaceRoot.resolve(scenario))
            val caller = file("$scenario/src/Caller.kt")
            val target = file("$scenario/src/Target.kt")
            val entries = listOf(
                inventory(scenarioRoot, caller, hash('a'), ":app[main]"),
                inventory(scenarioRoot, target, hash('b'), ":app[main]"),
            )
            SqliteSourceIndexStore(scenarioRoot).use { store ->
                store.ensureSchema()
                store.reconcileFileInventory(entries, versions("1"))
                val work = store.pendingFileStages(FileIndexStage.RELATIONSHIPS)
                    .associateBy { pending -> pending.path.rawPath }
                store.commitRelationshipBatch(
                    listOf(
                        RelationshipFileStageUpdate(
                            work = work.getValue(caller), scannedContentHash = work.getValue(caller).contentHash,
                            references = listOf(reference(caller, "demo.Target", target)),
                            declarations = emptyList(),
                        ),
                        RelationshipFileStageUpdate(work.getValue(target), work.getValue(target).contentHash, emptyList(), emptyList()),
                    ),
                )

                val nextEntries = if (removeTarget) {
                    listOf(entries.first())
                } else {
                    listOf(entries.first(), entries.last().copy(contentHash = hash('c')))
                }
                store.reconcileFileInventory(nextEntries, versions("1"))

                val preserved = store.referencesToSymbol("demo.Target").single()
                assertEquals(caller, preserved.sourcePath)
                assertNull(preserved.targetPath)
                assertNull(preserved.targetOffset)
                val expectedPending = if (removeTarget) listOf(caller) else listOf(caller, target)
                assertEquals(
                    expectedPending,
                    store.pendingFileStages(FileIndexStage.RELATIONSHIPS).map { pending -> pending.path.rawPath },
                )
                assertNull(store.fileStageOutcome(caller, FileIndexStage.RELATIONSHIPS))
            }
        }
    }

    private fun indexInBatches(
        root: Path,
        relativePaths: List<String>,
        reopenAfterFirstBatch: Boolean,
    ): PersistedFacts {
        val paths = relativePaths.map { relative -> workspaceSourceRawPath(root, root.resolve(relative).toString()) }
        val entries = paths.mapIndexed { index, path -> inventory(root, path, hash('a' + index), ":app[main]") }
        SqliteSourceIndexStore(root).use { store ->
            store.ensureSchema()
            store.reconcileFileInventory(entries, versions("1"))
            commitSources(store, FileIndexStage.SOURCE, paths)
            commitRelationships(store, paths.take(1))
            if (!reopenAfterFirstBatch) commitRelationships(store, paths.drop(1))
        }
        SqliteSourceIndexStore(root).use { store ->
            if (reopenAfterFirstBatch) commitRelationships(store, paths.drop(1))
            return PersistedFacts(
                references = relativePaths.zip(paths).associate { (relativePath, path) ->
                    relativePath to store.referencesFromFile(path).map { reference ->
                        reference.copy(sourcePath = relativePath)
                    }
                },
                completedModules = store.completedModules(),
                generation = store.readGeneration().value,
            )
        }
    }

    private data class PersistedFacts(
        val references: Map<String, List<SymbolReferenceRow>>,
        val completedModules: Set<String>,
        val generation: Long,
    )
}
