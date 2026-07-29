package io.github.amichne.kast.idea

import com.intellij.openapi.application.readAction
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.psiFileFixture
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
    private val uncoveredJavaSubtypeFixture = mainSourceRootFixture.psiFileFixture(
        "UncoveredShape.java",
        """
        package demo.hierarchy;

        public final class UncoveredShape implements Shape {}
        """,
    )

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

    @Test
    fun `unindexed Java subtype prevents exact hierarchy admission`() = runBlocking {
        uncoveredJavaSubtypeFixture.get()
        withPersistedScope(completeDependent = true) { inputs, backend ->
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
            assertTrue(RelationshipSearchLimitation.SOURCE_SET_EXCLUDED in limited.evidence.coverage.limitations)
            assertTrue(RelationshipSearchLimitation.FAMILY_SEARCH_INCOMPLETE in limited.evidence.coverage.limitations)
        }
    }

    private suspend fun withPersistedScope(
        completeDependent: Boolean = false,
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
                (inputs.declaringModulePaths + if (completeDependent) listOf(inputs.dependentPath) else emptyList())
                    .map { path ->
                        RelationshipFileStageUpdate(
                            work = pendingByPath.getValue(path),
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
        val callableSelector: KastExactSymbolSelector,
        val typeSelector: KastExactSymbolSelector,
        val declaringModulePaths: List<String>,
        val dependentPath: String,
    )
}
