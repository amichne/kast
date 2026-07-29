package io.github.amichne.kast.idea

import com.intellij.openapi.application.readAction
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.TypeHierarchyDirection
import io.github.amichne.kast.api.contract.result.HierarchyRelationsResult
import io.github.amichne.kast.api.contract.result.RelationshipSearchLimitation
import io.github.amichne.kast.api.contract.skill.KastExactSymbolSelector
import io.github.amichne.kast.api.contract.skill.KastHierarchyQuery
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileInventoryEntry
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.api.stage.RelationshipFileStageUpdate
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
internal class KastPluginBackendContractTestPersistedJavaScope : KastPluginBackendContractTestFixture() {
    private val uncoveredJavaSubtypeFixture = mainSourceRootFixture.psiFileFixture(
        "UncoveredShape.java",
        """
        package demo.hierarchy;

        public final class UncoveredShape implements Shape {}
        """,
    )

    @Test
    fun `unindexed Java subtype prevents exact hierarchy admission`() = runBlocking {
        ensureInternalVisibilityProjectReady()
        val inputs = readAction {
            val declaration = hierarchyFileFixture.get()
            val javaSubtype = uncoveredJavaSubtypeFixture.get()
            val kotlinPaths = listOf(
                sampleFileFixture.get().virtualFile.path,
                sampleUsageFileFixture.get().virtualFile.path,
                declaration.virtualFile.path,
                internalDeclarationFileFixture.get().virtualFile.path,
                internalDependentFileFixture.get().virtualFile.path,
            )
            JavaScopeInputs(
                workspaceRoot = commonWorkspaceRoot(declaration.virtualFile.path, javaSubtype.virtualFile.path),
                selector = KastExactSymbolSelector(
                    fqName = "demo.hierarchy.Shape",
                    declarationFile = declaration.virtualFile.path,
                    declarationStartOffset = declaration.text.indexOf("Shape"),
                    kind = SymbolKind.INTERFACE,
                ),
                kotlinPaths = kotlinPaths,
            )
        }

        SqliteSourceIndexStore(inputs.workspaceRoot).use { store ->
            store.ensureSchema()
            store.reconcileFileInventory(
                inputs.kotlinPaths.mapIndexed { index, path ->
                    FileInventoryEntry(
                        path = path,
                        lastModifiedMillis = 1,
                        contentHash = FileContentHash.parse(('a' + index).toString().repeat(64)),
                        moduleName = ":main[main]",
                        sourceSet = "main",
                    )
                },
                FileStageVersions.CURRENT,
            )
            val pending = store.pendingFileStages(FileIndexStage.RELATIONSHIPS)
                .associateBy { work -> work.path }
            store.commitRelationshipBatch(
                inputs.kotlinPaths.map { path ->
                    RelationshipFileStageUpdate(
                        work = pending.getValue(path),
                        references = emptyList(),
                        declarations = emptyList(),
                    )
                },
            )

            val result = backend(
                workspaceRoot = inputs.workspaceRoot,
                relationshipCoverageAuthority = relationshipCoverageAuthority(sourceIndexStore = store),
            ).hierarchyRelations(
                KastHierarchyQuery(
                    workspaceRoot = inputs.workspaceRoot.toString(),
                    selector = inputs.selector,
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

    private data class JavaScopeInputs(
        val workspaceRoot: java.nio.file.Path,
        val selector: KastExactSymbolSelector,
        val kotlinPaths: List<String>,
    )
}
