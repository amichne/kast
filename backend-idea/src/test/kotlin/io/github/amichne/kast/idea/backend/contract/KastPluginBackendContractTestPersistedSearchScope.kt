package io.github.amichne.kast.idea

import com.intellij.openapi.application.readAction
import com.intellij.testFramework.junit5.TestApplication
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.SearchScope
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.TypeHierarchyDirection
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.result.CallRelationsResult
import io.github.amichne.kast.api.contract.result.HierarchyRelationsResult
import io.github.amichne.kast.api.contract.result.RelationshipResultEvidence
import io.github.amichne.kast.api.contract.result.RelationshipSearchLimitation
import io.github.amichne.kast.api.contract.skill.KastCallersQuery
import io.github.amichne.kast.api.contract.skill.KastExactSymbolSelector
import io.github.amichne.kast.api.contract.skill.KastHierarchyQuery
import io.github.amichne.kast.api.contract.skill.WrapperCallDirection
import io.github.amichne.kast.idea.backend.KastPluginBackend
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
        withPersistedScope { inputs, backend ->
            val result = backend.findReferences(
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

    @Test
    fun `pending dependent module prevents exact call admission`() = runBlocking {
        withPersistedScope { inputs, backend ->
            val result = backend.callRelations(
                KastCallersQuery(
                    workspaceRoot = inputs.workspaceRoot.toString(),
                    selector = inputs.callableSelector,
                    direction = WrapperCallDirection.INCOMING,
                    depth = 1,
                    maxResults = 4,
                ),
            )

            val limited = result as CallRelationsResult.Limited
            assertTrue(RelationshipSearchLimitation.INDEX_NOT_READY in limited.evidence.coverage.limitations)
        }
    }

    @Test
    fun `pending dependent module prevents exact hierarchy admission`() = runBlocking {
        withPersistedScope { inputs, backend ->
            val result = backend.hierarchyRelations(
                KastHierarchyQuery(
                    workspaceRoot = inputs.workspaceRoot.toString(),
                    selector = inputs.typeSelector,
                    direction = TypeHierarchyDirection.SUBTYPES,
                    depth = 1,
                    maxResults = 4,
                ),
            )

            val limited = result as HierarchyRelationsResult.Limited
            assertTrue(RelationshipSearchLimitation.INDEX_NOT_READY in limited.evidence.coverage.limitations)
        }
    }

    private suspend fun withPersistedScope(
        block: suspend (PersistedSearchScopeInputs, KastPluginBackend) -> Unit,
    ) {
        ensureInternalVisibilityProjectReady()
        val inputs = readAction {
            val declaration = sampleFileFixture.get()
            val typeDeclaration = hierarchyFileFixture.get()
            val dependent = internalDependentFileFixture.get()
            val declarationPath = declaration.virtualFile.path
            val declarationOffset = declaration.text.indexOf("greet")
            PersistedSearchScopeInputs(
                workspaceRoot = commonWorkspaceRoot(declarationPath, dependent.virtualFile.path),
                declarationPath = declarationPath,
                declarationOffset = declarationOffset,
                callableSelector = KastExactSymbolSelector(
                    fqName = "demo.greet",
                    declarationFile = declarationPath,
                    declarationStartOffset = declarationOffset,
                    kind = SymbolKind.FUNCTION,
                ),
                typeSelector = KastExactSymbolSelector(
                    fqName = "demo.hierarchy.Shape",
                    declarationFile = typeDeclaration.virtualFile.path,
                    declarationStartOffset = typeDeclaration.text.indexOf("Shape"),
                    kind = SymbolKind.INTERFACE,
                ),
                declaringModulePaths = listOf(
                    declarationPath,
                    sampleUsageFileFixture.get().virtualFile.path,
                    typeDeclaration.virtualFile.path,
                    internalDeclarationFileFixture.get().virtualFile.path,
                ),
                dependentPath = dependent.virtualFile.path,
            )
        }
        val store = SqliteSourceIndexStore(inputs.workspaceRoot)
        try {
            store.ensureSchema()
            val declaringEntries = inputs.declaringModulePaths.mapIndexed { index, path ->
                inventory(
                    workspaceRoot = inputs.workspaceRoot,
                    path = path,
                    hashCharacter = 'a' + index,
                    moduleName = ":main[main]",
                    sourceSet = "main",
                )
            }
            store.reconcileFileInventory(
                declaringEntries + inventory(
                    workspaceRoot = inputs.workspaceRoot,
                    path = inputs.dependentPath,
                    hashCharacter = 'f',
                    moduleName = ":secondary[test]",
                    sourceSet = "test",
                ),
                FileStageVersions.CURRENT,
            )
            val pendingByPath = store.pendingFileStages(FileIndexStage.RELATIONSHIPS)
                .associateBy { work -> work.path }
            store.commitRelationshipBatch(
                declaringEntries.map { entry ->
                    RelationshipFileStageUpdate(
                        work = pendingByPath.getValue(entry.path),
                        scannedContentHash = pendingByPath.getValue(entry.path).contentHash,
                        references = emptyList(),
                        declarations = emptyList(),
                        limitations = emptyList(),
                    )
                },
            )
            block(
                inputs,
                backend(
                    workspaceRoot = inputs.workspaceRoot,
                    relationshipCoverageAuthority = relationshipCoverageAuthority(sourceIndexStore = store),
                ),
            )
        } finally {
            store.close()
        }
    }

    private fun inventory(
        workspaceRoot: java.nio.file.Path,
        path: String,
        hashCharacter: Char,
        moduleName: String,
        sourceSet: String,
    ): FileInventoryEntry = fileInventoryEntry(
        workspaceRoot = workspaceRoot,
        path = path,
        lastModifiedMillis = 1,
        contentHash = FileContentHash.parse(hashCharacter.toString().repeat(64)),
        moduleName = moduleName,
        sourceSet = sourceSet,
    )

    private data class PersistedSearchScopeInputs(
        val workspaceRoot: java.nio.file.Path,
        val declarationPath: String,
        val declarationOffset: Int,
        val callableSelector: KastExactSymbolSelector,
        val typeSelector: KastExactSymbolSelector,
        val declaringModulePaths: List<String>,
        val dependentPath: String,
    )
}
