package io.github.amichne.kast.idea

import com.intellij.openapi.application.readAction
import com.intellij.testFramework.junit5.TestApplication
import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.skill.KastNativeReadCompleteness
import io.github.amichne.kast.api.contract.selector.SelectorHandleAuthority
import io.github.amichne.kast.api.contract.selector.SelectorOperationFamily
import io.github.amichne.kast.idea.backend.workspace.nativePublicSymbolReader
import io.github.amichne.kast.idea.backend.semantic.CurrentRuntimeInvalidation
import io.github.amichne.kast.idea.backend.semantic.ProgressiveRuntimeAvailability
import io.github.amichne.kast.server.NativePublicSymbolReadResult
import io.github.amichne.kast.server.PublicSymbolReadMatch
import io.github.amichne.kast.server.PublicSymbolReadProjection
import io.github.amichne.kast.server.PublicSymbolReadQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

@TestApplication
internal class NativePublicSymbolReadTest : KastIndexerBackendContractTestFixture() {
    @Test
    fun `native public read serves one current compiler epoch and rejects epoch movement`() =
        runBlocking {
            ensureProjectReady()
            val sourceRoot = readAction {
                Path.of(mainSourceRootFixture.get().virtualFile.path).toAbsolutePath().normalize()
            }
            val workspaceRoot = sourceRoot.parent
            val model = workspaceModel(workspaceRoot, sourceRoot)
            val backend = backend(
                workspaceRoot = workspaceRoot,
                workspaceSemanticReadAuthority = TestWorkspaceSemanticReadAuthority(
                    currentStatus = {
                        IdeaIndexSemanticAdmission.Status.Pending(
                            "persisted source, reference, and graph evidence is still building",
                        )
                    },
                ),
                progressiveRuntimeAvailability = ProgressiveRuntimeAvailability.alreadyCurrent(),
                workspaceModelReader = { model },
            )
            val reader = backend.nativePublicSymbolReader()

            val result = reader.read(
                query(workspaceRoot),
            )

            val completed = result as NativePublicSymbolReadResult.Completed
            assertEquals(
                listOf("demo.greet"),
                completed.definitions.map { it.symbol.fqName },
                completed.evidence.toString(),
            )
            assertEquals(
                KastNativeReadCompleteness.EXACT,
                completed.evidence.completeness,
                completed.evidence.toString(),
            )
            assertTrue(completed.evidence.qualifications.isEmpty())
            val handle = completed.definitions.single().selectorHandle
            val handleResolution = backend.selectorHandles.resolve(
                handle = handle.value,
                workspaceRoot = workspaceRoot.toString(),
                family = SelectorOperationFamily.IDENTITY,
            )
            assertTrue(handleResolution is SelectorHandleAuthority.Resolution.Resolved)
            assertEquals(1L, completed.evidence.work.readActionCount)
            assertEquals(0L, completed.evidence.work.vfsRefreshCount)
            assertEquals(0L, completed.evidence.work.gradleImportCount)
            assertEquals(0L, completed.evidence.work.graphBuildCount)
            assertEquals(0L, completed.evidence.work.sqliteWriteCount)
            assertEquals(1L, completed.evidence.generation)

            val invalidatingAvailability = ProgressiveRuntimeAvailability.alreadyCurrent()
            val invalidatingBackend = backend(
                workspaceRoot = workspaceRoot,
                progressiveRuntimeAvailability = invalidatingAvailability,
                workspaceModelReader = {
                    when (val invalidation = invalidatingAvailability.invalidate()) {
                        is CurrentRuntimeInvalidation.Invalidated -> Unit
                        is CurrentRuntimeInvalidation.AlreadyUnavailable ->
                            error("Expected an available compiler epoch: ${invalidation.state}")
                    }
                    model
                },
            )

            assertEquals(
                NativePublicSymbolReadResult.Rejected(
                    io.github.amichne.kast.server.NativePublicSymbolReadFailure
                        .RUNTIME_OR_SEMANTIC_UNAVAILABLE,
                ),
                invalidatingBackend.nativePublicSymbolReader().read(query(workspaceRoot)),
            )
        }

    private fun query(workspaceRoot: Path): PublicSymbolReadQuery =
        PublicSymbolReadQuery(
            workspaceRoot = NormalizedPath.of(workspaceRoot),
            pattern = NonBlankString("greet"),
            maxResults = PositiveInt(10),
            match = PublicSymbolReadMatch.EXACT_NAME,
            projection = PublicSymbolReadProjection.DECLARATION_SCOPE_AND_DOCUMENTATION,
        )

    private fun workspaceModel(
        workspaceRoot: Path,
        sourceRoot: Path,
    ): IdeaGradleProjectLoadBridge.GradleWorkspaceModel =
        IdeaGradleProjectLoadBridge.GradleWorkspaceModel(
            listOf(workspaceRoot),
            true,
            emptyList(),
            emptyList(),
            emptyList(),
            listOf(
                IdeaGradleProjectLoadBridge.GradleModuleAssociation(
                    mainModuleFixture.get().name,
                    workspaceRoot,
                    workspaceRoot,
                    ":",
                    false,
                    false,
                    listOf(
                        IdeaGradleProjectLoadBridge.GradleSourceSetAssociation(
                            "main",
                            listOf(authoredGradleSourceRoot(sourceRoot)),
                        ),
                    ),
                ),
            ),
        )
}
