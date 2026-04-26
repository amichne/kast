package io.github.amichne.kast.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.ReferencesQuery
import io.github.amichne.kast.api.contract.SearchScopeKind
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.SymbolQuery
import io.github.amichne.kast.api.contract.TypeHierarchyDirection
import io.github.amichne.kast.api.contract.TypeHierarchyQuery
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

@TestApplication
class KastPluginBackendContractTest {
    companion object {
        private val projectFixture: TestFixture<Project> = projectFixture()

        private val defaultLimits = ServerLimits(
            maxResults = 500,
            requestTimeoutMillis = 30_000L,
            maxConcurrentRequests = 4,
        )

        private const val sampleSource = """
            package demo

            fun greet(name: String): String = "Hello, ${'$'}name"
        """

        private const val hierarchySource = """
            package demo.hierarchy

            interface Shape

            class Circle : Shape
        """

        private const val internalDeclarationSource = """
            package demo.internalvisibility

            internal fun internalName(): String = "internal"

            fun mainUse(): String = internalName()
        """

        private const val internalDependentSource = """
            package demo.internalvisibility

            fun dependentUse(): String = internalName()
        """
    }

    private val mainModuleFixture: TestFixture<Module> = projectFixture.moduleFixture("main")
    private val secondaryModuleFixture: TestFixture<Module> = projectFixture.moduleFixture("secondary")
    private val mainSourceRootFixture: TestFixture<PsiDirectory> = mainModuleFixture.sourceRootFixture()
    private val secondarySourceRootFixture: TestFixture<PsiDirectory> =
        secondaryModuleFixture.sourceRootFixture(isTestSource = true)
    private val sampleFileFixture: TestFixture<PsiFile> = mainSourceRootFixture.psiFileFixture("Sample.kt", sampleSource)
    private val hierarchyFileFixture: TestFixture<PsiFile> = mainSourceRootFixture.psiFileFixture("Hierarchy.kt", hierarchySource)
    private val internalDeclarationFileFixture: TestFixture<PsiFile> =
        mainSourceRootFixture.psiFileFixture("InternalDeclaration.kt", internalDeclarationSource)
    private val internalDependentFileFixture: TestFixture<PsiFile> =
        secondarySourceRootFixture.psiFileFixture("InternalDependent.kt", internalDependentSource)

    private val project: Project
        get() = projectFixture.get()

    private val sampleFile: PsiFile
        get() = sampleFileFixture.get()

    private val hierarchyFile: PsiFile
        get() = hierarchyFileFixture.get()

    private fun backend(workspaceRoot: Path = Path.of(project.basePath!!)): KastPluginBackend = KastPluginBackend(
        project = project,
        workspaceRoot = workspaceRoot,
        limits = defaultLimits,
    )

    private fun ensureProjectReady() {
        mainModuleFixture.get()
        secondaryModuleFixture.get()
        sampleFileFixture.get()
        hierarchyFileFixture.get()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private suspend fun ensureInternalVisibilityProjectReady() {
        ensureProjectReady()
        internalDeclarationFileFixture.get()
        internalDependentFileFixture.get()
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                ModuleRootModificationUtil.addDependency(
                    secondaryModuleFixture.get(),
                    mainModuleFixture.get(),
                    DependencyScope.TEST,
                    false,
                    true,
                )
            }
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    @Test
    fun `runtime status lists source module names`() = runBlocking {
        ensureProjectReady()

        val status = backend().runtimeStatus()

        assertEquals(listOf("main", "secondary"), status.sourceModuleNames)
    }

    @Test
    fun `resolve symbol includes declaration scope when requested`() = runBlocking {
        ensureProjectReady()

        val (filePath, offset) = readAction {
            sampleFile.virtualFile.path to sampleFile.text.indexOf("greet")
        }
        val result = backend().resolveSymbol(
            SymbolQuery(
                position = FilePosition(
                    filePath = filePath,
                    offset = offset,
                ),
                includeDeclarationScope = true,
            ),
        )

        val declarationScope = result.symbol.declarationScope
        assertNotNull(declarationScope)
        assertTrue(declarationScope?.sourceText.orEmpty().contains("fun greet"))
    }

    @Test
    fun `find references for internal symbol searches declaring module dependents`() = runBlocking {
        ensureInternalVisibilityProjectReady()

        val (workspaceRoot, filePath, offset) = readAction {
            val declarationFile = internalDeclarationFileFixture.get()
            val dependentFile = internalDependentFileFixture.get()
            Triple(
                commonWorkspaceRoot(declarationFile.virtualFile.path, dependentFile.virtualFile.path),
                declarationFile.virtualFile.path,
                declarationFile.text.indexOf("internalName"),
            )
        }

        val result = backend(workspaceRoot).findReferences(
            ReferencesQuery(
                position = FilePosition(filePath = filePath, offset = offset),
                includeDeclaration = false,
            ),
        )

        val referenceFileNames = result.references
            .map { Path.of(it.filePath).fileName.toString() }
            .toSet()
        assertEquals(SearchScopeKind.DEPENDENT_MODULES, result.searchScope?.scope)
        assertTrue("InternalDeclaration.kt" in referenceFileNames) {
            "Expected declaring module reference, got: $referenceFileNames"
        }
        assertTrue("InternalDependent.kt" in referenceFileNames) {
            "Expected dependent module reference, got: $referenceFileNames"
        }
    }

    private fun commonWorkspaceRoot(first: String, second: String): Path {
        val firstPath = Path.of(first).toAbsolutePath().normalize()
        val secondPath = Path.of(second).toAbsolutePath().normalize()
        return generateSequence(firstPath.parent) { it.parent }
            .first { candidate -> secondPath.startsWith(candidate) }
    }

    @Test
    fun `type hierarchy returns subtypes for interface`() = runBlocking {
        ensureProjectReady()

        val (filePath, offset) = readAction {
            hierarchyFile.virtualFile.path to hierarchyFile.text.indexOf("Shape")
        }

        val result = backend().typeHierarchy(
            TypeHierarchyQuery(
                position = FilePosition(filePath = filePath, offset = offset),
                direction = TypeHierarchyDirection.SUBTYPES,
                depth = 1,
            ),
        )

        assertNotNull(result.root)
        assertTrue(result.stats.totalNodes >= 1)
        val childFqNames = result.root.children.map { it.symbol.fqName }
        assertTrue(
            childFqNames.any { it.contains("Circle") },
            "Expected Circle in subtypes but got: $childFqNames",
        )
    }

    @Test
    fun `capabilities read backend version from generated resource`() = runBlocking {
        ensureProjectReady()

        val expectedVersion = KastPluginBackend::class.java
            .getResource("/kast-backend-version.txt")
            ?.readText()
            ?.trim()

        assertNotNull(expectedVersion)
        assertEquals(expectedVersion, backend().capabilities().backendVersion)
    }
}
