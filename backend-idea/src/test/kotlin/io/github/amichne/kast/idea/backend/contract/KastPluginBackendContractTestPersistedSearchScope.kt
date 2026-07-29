package io.github.amichne.kast.idea

import com.intellij.openapi.application.readAction
import com.intellij.testFramework.junit5.TestApplication
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.SearchScope
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.result.RelationshipResultEvidence
import io.github.amichne.kast.api.contract.result.RelationshipSearchLimitation
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileInventoryEntry
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.api.stage.RelationshipFileStageUpdate
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
internal class KastPluginBackendContractTestPersistedSearchScope : KastPluginBackendContractTestFixture() {
    @Test
    fun `pending dependent module prevents exhaustive persisted reference coverage`() = runBlocking {
        ensureInternalVisibilityProjectReady()
        val inputs = readAction {
            val declaration = sampleFileFixture.get()
            val dependent = internalDependentFileFixture.get()
            PersistedSearchScopeInputs(
                workspaceRoot = commonWorkspaceRoot(
                    declaration.virtualFile.path,
                    dependent.virtualFile.path,
                ),
                declarationPath = declaration.virtualFile.path,
                declarationOffset = declaration.text.indexOf("greet"),
                declaringModulePaths = listOf(
                    declaration.virtualFile.path,
                    sampleUsageFileFixture.get().virtualFile.path,
                    hierarchyFileFixture.get().virtualFile.path,
                    internalDeclarationFileFixture.get().virtualFile.path,
                ),
                dependentPath = dependent.virtualFile.path,
            )
        }

        SqliteSourceIndexStore(inputs.workspaceRoot).use { store ->
            store.ensureSchema()
            val declaringEntries = inputs.declaringModulePaths.mapIndexed { index, path ->
                inventory(path, hashCharacter = 'a' + index, moduleName = ":main[main]")
            }
            store.reconcileFileInventory(
                declaringEntries + inventory(
                    inputs.dependentPath,
                    hashCharacter = 'f',
                    moduleName = ":secondary[test]",
                ),
                FileStageVersions.CURRENT,
            )
            val pendingByPath = store.pendingFileStages(FileIndexStage.RELATIONSHIPS)
                .associateBy { work -> work.path }
            store.commitRelationshipBatch(
                inputs.declaringModulePaths.map { path ->
                    RelationshipFileStageUpdate(
                        work = pendingByPath.getValue(path),
                        references = emptyList(),
                        declarations = emptyList(),
                        limitations = emptyList(),
                    )
                },
            )

            val result = backend(
                workspaceRoot = inputs.workspaceRoot,
                relationshipCoverageAuthority = relationshipCoverageAuthority(sourceIndexStore = store),
            ).findReferences(
                ReferencesQuery(
                    position = FilePosition(inputs.declarationPath, inputs.declarationOffset),
                ),
            )

            val evidence = result.evidence as RelationshipResultEvidence.Limited
            assertTrue(RelationshipSearchLimitation.INDEX_NOT_READY in evidence.coverage.limitations)
            assertFalse(result.searchScope?.exhaustive ?: true)
            assertEquals(SearchScope.CandidateCoverage.PARTIAL, result.searchScope?.candidateCoverage)
        }
    }

    private fun inventory(
        path: String,
        hashCharacter: Char,
        moduleName: String,
    ): FileInventoryEntry = FileInventoryEntry(
        path = path,
        lastModifiedMillis = 1,
        contentHash = FileContentHash.parse(hashCharacter.toString().repeat(64)),
        moduleName = moduleName,
        sourceSet = "main",
    )

    private data class PersistedSearchScopeInputs(
        val workspaceRoot: java.nio.file.Path,
        val declarationPath: String,
        val declarationOffset: Int,
        val declaringModulePaths: List<String>,
        val dependentPath: String,
    )
}
