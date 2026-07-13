package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.SearchScopeKind
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.TypeHierarchyDirection
import io.github.amichne.kast.api.contract.query.ImplementationsQuery
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.query.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.query.WorkspaceFilesQuery
import io.github.amichne.kast.api.contract.query.WorkspaceSearchQuery
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
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

        private const val sampleUsageSource = """
            package demo

            fun useGreeting(): String = greet("idea")
        """

        private const val memberSource = """
            package demo

            class Parser {
                fun parse(input: String): String = input
            }
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
    private val sampleUsageFileFixture: TestFixture<PsiFile> =
        mainSourceRootFixture.psiFileFixture("SampleUsage.kt", sampleUsageSource)
    private val memberFileFixture: TestFixture<PsiFile> =
        mainSourceRootFixture.psiFileFixture("Parser.kt", memberSource)
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

    private fun backend(
        workspaceRoot: Path = Path.of(project.basePath!!),
        limits: ServerLimits = defaultLimits,
        telemetry: IdeaBackendTelemetry = IdeaBackendTelemetry.disabled(),
        referenceIndexLookup: ReferenceIndexLookup = ReferenceIndexLookup.Unavailable,
        referenceSearchClock: ReferenceSearchClock = ReferenceSearchClock.System,
    ): KastPluginBackend = KastPluginBackend(
        project = project,
        workspaceRoot = workspaceRoot,
        limits = limits,
        telemetry = telemetry,
        referenceIndexLookup = referenceIndexLookup,
        referenceSearchClock = referenceSearchClock,
    )

    private fun ensureProjectReady() {
        mainModuleFixture.get()
        secondaryModuleFixture.get()
        sampleFileFixture.get()
        sampleUsageFileFixture.get()
        hierarchyFileFixture.get()
        waitUntilIndexesAreReady(project)
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
        waitUntilIndexesAreReady(project)
    }

    @Test
    fun `runtime status lists source module names`() = runBlocking {
        ensureProjectReady()

        val status = backend().runtimeStatus()

        assertEquals(listOf("main", "secondary"), status.sourceModuleNames)
    }

    @Test
    fun `workspace files caps included files per module and reports truncation`() = runBlocking {
        ensureProjectReady()
        val workspaceRoot = readAction {
            commonWorkspaceRoot(sampleFile.virtualFile.path, hierarchyFile.virtualFile.path)
        }

        val result = backend(workspaceRoot).workspaceFiles(
            WorkspaceFilesQuery(
                moduleName = "main",
                includeFiles = true,
                maxFilesPerModule = 1,
            ),
        )

        val module = result.modules.single()
        assertEquals("main", module.name)
        assertEquals(1, module.files.size)
        assertTrue(module.fileCount > module.files.size)
        assertTrue(module.filesTruncated)
    }

    @Test
    fun `workspace files exclude project module files outside canonical workspace root`() = runBlocking {
        ensureInternalVisibilityProjectReady()
        val workspaceRoot = readAction {
            Path.of(sampleFile.virtualFile.path).parent.toAbsolutePath().normalize()
        }

        val result = backend(workspaceRoot).workspaceFiles(
            WorkspaceFilesQuery(
                includeFiles = true,
            ),
        )

        val mainModule = result.modules.single { it.name == "main" }
        val secondaryModule = result.modules.single { it.name == "secondary" }
        assertTrue(mainModule.fileCount > 0)
        assertEquals(0, secondaryModule.fileCount)
        assertTrue(result.modules.flatMap { it.files }.all { filePath -> Path.of(filePath).startsWith(workspaceRoot) })
    }

    @Test
    fun `workspace search returns content matches from project files`() = runBlocking {
        ensureProjectReady()
        val workspaceRoot = readAction {
            commonWorkspaceRoot(sampleFile.virtualFile.path, sampleUsageFileFixture.get().virtualFile.path)
        }

        val result = backend(workspaceRoot).workspaceSearch(
            WorkspaceSearchQuery(
                pattern = "greet",
            ),
        )

        assertTrue(result.matches.isNotEmpty())
        assertTrue(result.matches.any { match -> match.preview.contains("fun greet") })
        assertTrue(result.matches.all { match -> match.filePath.endsWith(".kt") })
    }

    @Test
    fun `resolve symbol includes declaration scope when requested`() = runBlocking {
        ensureProjectReady()

        val (filePath, offset) = readAction {
            sampleFile.virtualFile.path to sampleFile.text.indexOf("greet")
        }
        val result = backend(Path.of(filePath).parent).resolveSymbol(
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
    fun `resolve symbol includes compiler enclosing declaration identity`() = runBlocking {
        ensureProjectReady()

        val memberFile = memberFileFixture.get()
        val (filePath, offset) = readAction {
            memberFile.virtualFile.path to memberFile.text.indexOf("parse")
        }
        val result = backend(Path.of(filePath).parent).resolveSymbol(
            SymbolQuery(
                position = FilePosition(
                    filePath = filePath,
                    offset = offset,
                ),
            ),
        )

        assertEquals("demo.Parser", result.symbol.containingDeclaration)
    }

    @Test
    fun `find references includes usage site scope when requested`() = runBlocking {
        ensureProjectReady()

        val (workspaceRoot, filePath, offset) = readAction {
            val usageFile = sampleUsageFileFixture.get()
            Triple(
                commonWorkspaceRoot(sampleFile.virtualFile.path, usageFile.virtualFile.path),
                sampleFile.virtualFile.path,
                sampleFile.text.indexOf("greet"),
            )
        }

        val result = backend(workspaceRoot).findReferences(
            ReferencesQuery(
                position = FilePosition(filePath = filePath, offset = offset),
                includeUsageSiteScope = true,
            ),
        )

        val usageScope = result.references
            .single { reference -> reference.preview.contains("greet(\"idea\")") }
            .usageSiteScope
        assertNotNull(usageScope)
        assertTrue(usageScope?.sourceText.orEmpty().contains("fun useGreeting"))
    }

    @Test
    fun `fallback candidate discovery uses text index before reference resolution`() = runBlocking {
        ensureProjectReady()
        createIrrelevantKotlinFiles(count = 25)

        val (workspaceRoot, filePath, offset) = readAction {
            val usageFile = sampleUsageFileFixture.get()
            Triple(
                commonWorkspaceRoot(sampleFile.virtualFile.path, usageFile.virtualFile.path),
                sampleFile.virtualFile.path,
                sampleFile.text.indexOf("greet"),
            )
        }

        val result = backend(workspaceRoot).findReferences(
            ReferencesQuery(
                position = FilePosition(filePath = filePath, offset = offset),
                includeDeclaration = false,
            ),
        )

        val searchScope = checkNotNull(result.searchScope)
        assertTrue(searchScope.candidateFileCount <= 2) {
            "Expected text-index candidate discovery to skip irrelevant Kotlin files, got ${searchScope.candidateFileCount}"
        }
        assertTrue(searchScope.searchedFileCount <= searchScope.candidateFileCount)
        assertTrue(result.references.any { reference -> reference.preview.contains("greet(\"idea\")") })
    }

    @Test
    fun `find references trace includes fallback candidate and resolution spans`() = runBlocking {
        ensureProjectReady()

        val traceFile = Files.createTempFile("kast-references-trace", ".jsonl")
        val telemetry = IdeaBackendTelemetry.create(
            IdeaTelemetryConfig(
                enabled = true,
                scopes = setOf(IdeaTelemetryScope.REFERENCES),
                detail = IdeaTelemetryDetail.BASIC,
                outputFile = traceFile,
            ),
        )
        val (workspaceRoot, filePath, offset) = readAction {
            val usageFile = sampleUsageFileFixture.get()
            Triple(
                commonWorkspaceRoot(sampleFile.virtualFile.path, usageFile.virtualFile.path),
                sampleFile.virtualFile.path,
                sampleFile.text.indexOf("greet"),
            )
        }

        backend(
            workspaceRoot = workspaceRoot,
            telemetry = telemetry,
        ).findReferences(
            ReferencesQuery(
                position = FilePosition(filePath = filePath, offset = offset),
                includeDeclaration = false,
            ),
        )

        val trace = Files.readString(traceFile)
        listOf(
            "kast.idea.findReferences.indexLookup",
            "kast.idea.findReferences.findUsagesFallback",
            "kast.idea.findReferences.candidateDiscovery",
            "kast.idea.findReferences.referenceResolution",
        ).forEach { spanName ->
            assertTrue(trace.contains("\"name\":\"$spanName\"")) {
                "Expected trace span $spanName in:\n$trace"
            }
        }
    }

    @Test
    fun `find references uses ready source index before IDEA enumeration`() = runBlocking {
        ensureProjectReady()

        val referenceData = readAction {
            val usageFile = sampleUsageFileFixture.get()
            IndexedReferenceTestData(
                workspaceRoot = commonWorkspaceRoot(sampleFile.virtualFile.path, usageFile.virtualFile.path),
                declarationFilePath = sampleFile.virtualFile.path,
                declarationOffset = sampleFile.text.indexOf("greet"),
                usageFilePath = usageFile.virtualFile.path,
                usageOffset = usageFile.text.indexOf("greet(\"idea\")"),
            )
        }
        var lookedUpFqName: String? = null
        val referenceIndexLookup = ReferenceIndexLookup { targetFqName ->
            lookedUpFqName = targetFqName
            IndexedReferenceLookupResult.Ready(
                listOf(
                    SymbolReferenceRow(
                        sourcePath = referenceData.usageFilePath,
                        sourceOffset = referenceData.usageOffset,
                        targetFqName = targetFqName,
                        targetPath = referenceData.declarationFilePath,
                        targetOffset = referenceData.declarationOffset,
                    ),
                ),
            )
        }

        val result = backend(
            workspaceRoot = referenceData.workspaceRoot,
            referenceIndexLookup = referenceIndexLookup,
        ).findReferences(
            ReferencesQuery(
                position = FilePosition(
                    filePath = referenceData.declarationFilePath,
                    offset = referenceData.declarationOffset,
                ),
                includeDeclaration = false,
                includeUsageSiteScope = true,
            ),
        )

        assertEquals("demo.greet", lookedUpFqName)
        val reference = result.references.single()
        assertEquals(referenceData.usageFilePath, reference.filePath)
        assertTrue(reference.preview.contains("greet(\"idea\")"))
        assertNotNull(reference.usageSiteScope)
        assertEquals(true, result.searchScope?.exhaustive)
        assertEquals(result.searchScope?.candidateFileCount, result.searchScope?.searchedFileCount)
    }

    @Test
    fun `find references reports non exhaustive scope when fallback budget is exhausted`() = runBlocking {
        ensureProjectReady()

        val (workspaceRoot, filePath, offset) = readAction {
            val usageFile = sampleUsageFileFixture.get()
            Triple(
                commonWorkspaceRoot(sampleFile.virtualFile.path, usageFile.virtualFile.path),
                sampleFile.virtualFile.path,
                sampleFile.text.indexOf("greet"),
            )
        }
        var currentNanos = 0L
        val exhaustedClock = ReferenceSearchClock {
            currentNanos += 2_000_000L
            currentNanos
        }

        val result = backend(
            workspaceRoot = workspaceRoot,
            limits = defaultLimits.copy(requestTimeoutMillis = 1L),
            referenceSearchClock = exhaustedClock,
        ).findReferences(
            ReferencesQuery(
                position = FilePosition(filePath = filePath, offset = offset),
                includeDeclaration = false,
            ),
        )

        assertFalse(result.searchScope?.exhaustive ?: true)
        assertTrue(
            (result.searchScope?.searchedFileCount ?: Int.MAX_VALUE) <
                (result.searchScope?.candidateFileCount ?: 0),
        )
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

    private fun createIrrelevantKotlinFiles(count: Int) {
        val suffix = System.nanoTime().toString()
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                val sourceRoot = mainSourceRootFixture.get().virtualFile
                repeat(count) { index ->
                    val file = sourceRoot.createChildData(this, "Irrelevant${suffix}_$index.kt")
                    VfsUtil.saveText(
                        file,
                        """
                        package demo

                        fun unrelated${suffix}_$index(): Int = $index
                        """.trimIndent(),
                    )
                }
            }
        }
        waitUntilIndexesAreReady(project)
    }

    @Test
    fun `type hierarchy returns subtypes for interface`() = runBlocking {
        ensureProjectReady()

        val (filePath, offset) = readAction {
            hierarchyFile.virtualFile.path to hierarchyFile.text.indexOf("Shape")
        }

        val result = backend(Path.of(filePath).parent).typeHierarchy(
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
    fun `implementations returns concrete subtypes for interface`() = runBlocking {
        ensureProjectReady()

        val (filePath, offset) = readAction {
            hierarchyFile.virtualFile.path to hierarchyFile.text.indexOf("Shape")
        }

        val result = backend(Path.of(filePath).parent).implementations(
            ImplementationsQuery(
                position = FilePosition(filePath = filePath, offset = offset),
            ),
        )

        assertEquals("demo.hierarchy.Shape", result.declaration.fqName)
        val implementationFqNames = result.implementations.map { it.fqName }
        assertTrue(
            implementationFqNames.any { it == "demo.hierarchy.Circle" },
            "Expected Circle in implementations but got: $implementationFqNames",
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

private data class IndexedReferenceTestData(
    val workspaceRoot: Path,
    val declarationFilePath: String,
    val declarationOffset: Int,
    val usageFilePath: String,
    val usageOffset: Int,
)
