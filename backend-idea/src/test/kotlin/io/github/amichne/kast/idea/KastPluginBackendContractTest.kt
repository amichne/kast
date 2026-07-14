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
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.SearchScopeKind
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.TypeHierarchyDirection
import io.github.amichne.kast.api.contract.query.ImplementationsQuery
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.query.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.query.WorkspaceFilesQuery
import io.github.amichne.kast.api.contract.query.WorkspaceSearchQuery
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.api.reference.SymbolReferencePage
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

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

        private val highCardinalityUsageSource = buildString {
            appendLine("package demo")
            appendLine()
            appendLine("fun highCardinalityUses(): List<String> = listOf(")
            repeat(500) { index -> appendLine("    greet(\"$index\"),") }
            appendLine(")")
        }
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
        psiGeneration: () -> Long = { 1L },
        readEpochObserver: IdeaReadEpochObserver = IdeaReadEpochObserver.Disabled,
        referenceTraversalObserver: ReferenceTraversalObserver = ReferenceTraversalObserver.Disabled,
    ): KastPluginBackend = KastPluginBackend(
        project = project,
        workspaceRoot = workspaceRoot,
        limits = limits,
        telemetry = telemetry,
        referenceIndexLookup = referenceIndexLookup,
        referenceSearchClock = referenceSearchClock,
        psiGeneration = psiGeneration,
        readEpochObserver = readEpochObserver,
        referenceTraversalObserver = referenceTraversalObserver,
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
    fun `fallback discovery resumes across many nonmatching files without heuristic filtering`() = runBlocking {
        ensureProjectReady()
        val irrelevantFiles = createIrrelevantKotlinFiles(count = 200)

        val (workspaceRoot, filePath, offset) = readAction {
            val usageFile = sampleUsageFileFixture.get()
            Triple(
                commonWorkspaceRoot(sampleFile.virtualFile.path, usageFile.virtualFile.path),
                sampleFile.virtualFile.path,
                sampleFile.text.indexOf("greet"),
            )
        }

        val backend = backend(workspaceRoot)
        var result = backend.findReferences(
            ReferencesQuery(
                position = FilePosition(filePath = filePath, offset = offset),
                includeDeclaration = false,
                maxResults = 4,
            ),
        )
        assertTrue(result.references.isEmpty())
        assertNotNull(result.page?.nextPageToken)

        val references = mutableListOf<io.github.amichne.kast.api.contract.Location>()
        references += result.references
        repeat(10) {
            val nextPageToken = result.page?.nextPageToken ?: return@repeat
            result = backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(filePath = filePath, offset = offset),
                    includeDeclaration = false,
                    maxResults = 4,
                    pageToken = nextPageToken,
                ),
            )
            references += result.references
        }

        val searchScope = checkNotNull(result.searchScope)
        assertTrue(searchScope.candidateFileCount > 64)
        assertTrue(searchScope.searchedFileCount <= searchScope.candidateFileCount)
        assertTrue(references.any { reference -> reference.preview.contains("greet(\"idea\")") })
        deleteKotlinFiles(irrelevantFiles)
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
        val referenceIndexLookup = ReferenceIndexLookup { targetFqName, offset, maxResults ->
            lookedUpFqName = targetFqName
            assertEquals(0, offset.value)
            assertEquals(100, maxResults.value)
            IndexedReferenceLookupResult.Ready(
                SymbolReferencePage(
                    references = listOf(
                        SymbolReferenceRow(
                            sourcePath = referenceData.usageFilePath,
                            sourceOffset = referenceData.usageOffset,
                            targetFqName = targetFqName,
                            targetPath = referenceData.declarationFilePath,
                            targetOffset = referenceData.declarationOffset,
                        ),
                    ),
                    nextOffset = null,
                ),
                generation = SourceIndexGeneration(1),
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
    fun `indexed reference cursor fails typed when index becomes unavailable`() = runBlocking {
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
        var indexReady = true
        val lookup = ReferenceIndexLookup { targetFqName, _, _ ->
            if (indexReady) {
                IndexedReferenceLookupResult.Ready(
                    SymbolReferencePage(
                        references = listOf(
                            SymbolReferenceRow(
                                sourcePath = referenceData.usageFilePath,
                                sourceOffset = referenceData.usageOffset,
                                targetFqName = targetFqName,
                                targetPath = referenceData.declarationFilePath,
                                targetOffset = referenceData.declarationOffset,
                            ),
                        ),
                        nextOffset = NonNegativeInt(1),
                    ),
                    generation = SourceIndexGeneration(1),
                )
            } else {
                IndexedReferenceLookupResult.NotReady
            }
        }
        val backend = backend(
            workspaceRoot = referenceData.workspaceRoot,
            referenceIndexLookup = lookup,
        )
        val position = FilePosition(
            filePath = referenceData.declarationFilePath,
            offset = referenceData.declarationOffset,
        )
        val first = backend.findReferences(
            ReferencesQuery(position = position, includeDeclaration = false, maxResults = 1),
        )
        indexReady = false

        val failure = runCatching {
            backend.findReferences(
                ReferencesQuery(
                    position = position,
                    includeDeclaration = false,
                    maxResults = 1,
                    pageToken = requireNotNull(first.page?.nextPageToken),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is ConflictException)
        assertTrue(failure?.message.orEmpty().contains("source index became unavailable"))
    }

    @Test
    fun `find references fallback stops at page evidence and continues without overlap`() = runBlocking {
        ensureProjectReady()
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                val file = mainSourceRootFixture.get().virtualFile.createChildData(this, "HighCardinalityUsage.kt")
                VfsUtil.saveText(file, highCardinalityUsageSource)
            }
        }
        waitUntilIndexesAreReady(project)
        val usageFile = readAction {
            checkNotNull(mainSourceRootFixture.get().findFile("HighCardinalityUsage.kt"))
        }
        val traceFile = Files.createTempFile("kast-high-cardinality-references", ".jsonl")
        val telemetry = IdeaBackendTelemetry.create(
            IdeaTelemetryConfig(
                enabled = true,
                scopes = setOf(IdeaTelemetryScope.REFERENCES),
                detail = IdeaTelemetryDetail.BASIC,
                outputFile = traceFile,
            ),
        )
        val (workspaceRoot, filePath, offset) = readAction {
            Triple(
                commonWorkspaceRoot(sampleFile.virtualFile.path, usageFile.virtualFile.path),
                sampleFile.virtualFile.path,
                sampleFile.text.indexOf("greet"),
            )
        }
        var indexReady = false
        var indexLookupCount = 0
        val changingIndexLookup = ReferenceIndexLookup { _, _, _ ->
            indexLookupCount += 1
            if (indexReady) {
                IndexedReferenceLookupResult.Ready(
                    SymbolReferencePage(references = emptyList(), nextOffset = null),
                    generation = SourceIndexGeneration(1),
                )
            } else {
                IndexedReferenceLookupResult.NotReady
            }
        }
        val traversalCloseCount = AtomicInteger()
        val backend = backend(
            workspaceRoot = workspaceRoot,
            limits = defaultLimits.copy(
                requestTimeoutMillis = 60_000,
                perFileScanBudgetMillis = 30_000,
            ),
            telemetry = telemetry,
            referenceIndexLookup = changingIndexLookup,
            referenceTraversalObserver = ReferenceTraversalObserver { traversalCloseCount.incrementAndGet() },
        )

        val first = backend.findReferences(
            ReferencesQuery(
                position = FilePosition(filePath = filePath, offset = offset),
                includeDeclaration = false,
                maxResults = 4,
            ),
        )
        indexReady = true
        val second = backend.findReferences(
            ReferencesQuery(
                position = FilePosition(filePath = filePath, offset = offset),
                includeDeclaration = false,
                maxResults = 4,
                pageToken = requireNotNull(first.page?.nextPageToken),
            ),
        )

        assertEquals(4, first.references.size)
        assertEquals(4, second.references.size)
        assertTrue(first.cardinality is ResultCardinality.KnownMinimum)
        assertEquals(1, indexLookupCount)
        assertTrue(first.references.toSet().intersect(second.references.toSet()).isEmpty())
        val trace = Files.readString(traceFile)
        assertEquals(2, trace.windowed("\"kast.references.observedEvidenceCount\":\"5\"".length)
            .count { it == "\"kast.references.observedEvidenceCount\":\"5\"" }) {
            "Expected every reference page to stop after four results plus one lookahead:\n$trace"
        }
        assertTrue(trace.lineSequence().filter { it.contains("kast.references.pathProbeCount") }.all { line ->
            Regex(""""kast.references.pathProbeCount":"([0-9]+)"""")
                .find(line)?.groupValues?.get(1)?.toInt()?.let { it <= 64 } == true
        }) { "Candidate traversal exceeded page plus lookahead:\n$trace" }

        val replayFailure = runCatching {
            backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(filePath = filePath, offset = offset),
                    includeDeclaration = false,
                    maxResults = 4,
                    pageToken = requireNotNull(first.page?.nextPageToken),
                ),
            )
        }.exceptionOrNull()
        assertTrue(replayFailure is ConflictException)

        val mismatchFailure = runCatching {
            backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(filePath = filePath, offset = offset),
                    includeDeclaration = false,
                    maxResults = 5,
                    pageToken = requireNotNull(second.page?.nextPageToken),
                ),
            )
        }.exceptionOrNull()
        assertTrue(mismatchFailure is ConflictException)
        assertEquals(1, traversalCloseCount.get())
    }

    @Test
    fun `find references fallback preserves aliased compiler identity`() = runBlocking {
        ensureProjectReady()
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                val file = mainSourceRootFixture.get().virtualFile.createChildData(this, "AliasedUsage.kt")
                VfsUtil.saveText(
                    file,
                    """
                    package demo.alias

                    import demo.greet as welcome

                    fun useAlias(): String = welcome("idea")
                    """.trimIndent(),
                )
            }
        }
        waitUntilIndexesAreReady(project)
        val aliasFile = readAction {
            checkNotNull(mainSourceRootFixture.get().findFile("AliasedUsage.kt"))
        }
        val (workspaceRoot, filePath, offset) = readAction {
            Triple(
                commonWorkspaceRoot(sampleFile.virtualFile.path, aliasFile.virtualFile.path),
                sampleFile.virtualFile.path,
                sampleFile.text.indexOf("greet"),
            )
        }

        val result = backend(
            workspaceRoot = workspaceRoot,
            referenceIndexLookup = ReferenceIndexLookup.Unavailable,
        ).findReferences(
            ReferencesQuery(
                position = FilePosition(filePath = filePath, offset = offset),
                includeDeclaration = false,
                maxResults = 50,
            ),
        )

        assertTrue(result.references.any { reference ->
            reference.filePath.endsWith("AliasedUsage.kt") &&
                reference.startOffset == aliasFile.text.indexOf("welcome(\"idea\")")
        }) { "Expected aliased compiler reference, got: ${result.references}" }
    }

    @Test
    fun `find references fallback preserves operator convention identity`() = runBlocking {
        ensureProjectReady()
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                val root = mainSourceRootFixture.get().virtualFile
                VfsUtil.saveText(
                    root.createChildData(this, "OperatorDeclaration.kt"),
                    """
                    package demo.operator

                    data class Box(val value: Int)

                    operator fun Box.plus(other: Box): Box = Box(value + other.value)
                    """.trimIndent(),
                )
                VfsUtil.saveText(
                    root.createChildData(this, "OperatorUsage.kt"),
                    """
                    package demo.operator

                    fun combine(): Box = Box(1) + Box(2)
                    """.trimIndent(),
                )
            }
        }
        waitUntilIndexesAreReady(project)
        val declarationFile = readAction {
            checkNotNull(mainSourceRootFixture.get().findFile("OperatorDeclaration.kt"))
        }
        val usageFile = readAction {
            checkNotNull(mainSourceRootFixture.get().findFile("OperatorUsage.kt"))
        }
        val (workspaceRoot, filePath, offset) = readAction {
            Triple(
                commonWorkspaceRoot(declarationFile.virtualFile.path, usageFile.virtualFile.path),
                declarationFile.virtualFile.path,
                declarationFile.text.indexOf("plus"),
            )
        }

        val result = backend(
            workspaceRoot = workspaceRoot,
            referenceIndexLookup = ReferenceIndexLookup.Unavailable,
        ).findReferences(
            ReferencesQuery(
                position = FilePosition(filePath = filePath, offset = offset),
                includeDeclaration = false,
                maxResults = 50,
            ),
        )

        assertTrue(result.references.any { reference ->
            reference.filePath.endsWith("OperatorUsage.kt") &&
                reference.startOffset == usageFile.text.indexOf("+")
        }) { "Expected operator compiler reference, got: ${result.references}" }
    }

    @Test
    fun `find references preserves every Kotlin convention identity without spelling heuristics`() = runBlocking {
        ensureProjectReady()
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                val root = mainSourceRootFixture.get().virtualFile
                VfsUtil.saveText(
                    root.createChildData(this, "ConventionDeclaration.kt"),
                    """
                    package demo.convention

                    import kotlin.reflect.KProperty

                    class Box(var value: Int) {
                        override fun equals(other: Any?): Boolean = other is Box && value == other.value
                        override fun hashCode(): Int = value
                        operator fun contains(candidate: Int): Boolean = candidate == value
                        operator fun get(index: Int): Int = value + index
                        operator fun set(index: Int, replacement: Int) { value = replacement + index }
                        operator fun component1(): Int = value
                        operator fun invoke(): Int = value
                    }

                    class Delegate {
                        operator fun getValue(thisRef: Any?, property: KProperty<*>): Int = 7
                    }
                    """.trimIndent(),
                )
                VfsUtil.saveText(
                    root.createChildData(this, "ConventionUsage.kt"),
                    """
                    package demo.convention

                    fun useConventions(left: Box, right: Box) {
                        val equal = left == right
                        val unequal = left != right
                        val included = 1 in left
                        val excluded = 2 !in left
                        val indexed = left[0]
                        left[0] = 3
                        val delegated by Delegate()
                        val (component) = left
                        val invoked = left()
                    }
                    """.trimIndent(),
                )
            }
        }
        waitUntilIndexesAreReady(project)
        val declarationFile = readAction {
            checkNotNull(mainSourceRootFixture.get().findFile("ConventionDeclaration.kt"))
        }
        val usageFile = readAction {
            checkNotNull(mainSourceRootFixture.get().findFile("ConventionUsage.kt"))
        }
        val (workspaceRoot, declarationFilePath, declarationOffsets) = readAction {
            Triple(
                commonWorkspaceRoot(declarationFile.virtualFile.path, usageFile.virtualFile.path),
                declarationFile.virtualFile.path,
                listOf("equals", "contains", "get", "set", "getValue", "component1", "invoke")
                    .associateWith { declarationName -> declarationFile.text.indexOf("fun $declarationName") + 4 },
            )
        }
        val backend = backend(workspaceRoot, referenceIndexLookup = ReferenceIndexLookup.Unavailable)

        val expectedUsageByDeclaration = mapOf(
            "equals" to listOf("left == right", "left != right"),
            "contains" to listOf("1 in left", "2 !in left"),
            "get" to listOf("left[0]"),
            "set" to listOf("left[0] = 3"),
            "getValue" to listOf("delegated by Delegate()"),
            "component1" to listOf("val (component) = left"),
            "invoke" to listOf("left()"),
        )
        expectedUsageByDeclaration.forEach { (declarationName, expectedPreviews) ->
            val references = collectAllReferencePages(
                backend = backend,
                position = FilePosition(
                    filePath = declarationFilePath,
                    offset = declarationOffsets.getValue(declarationName),
                ),
            )
            expectedPreviews.forEach { expectedPreview ->
                assertTrue(references.any { reference -> reference.preview.contains(expectedPreview) }) {
                    "Expected $declarationName reference at '$expectedPreview', got: $references"
                }
            }
        }
    }

    @Test
    fun `reference traversal disposes exactly once on exhaustion exception and shutdown`() = runBlocking {
        ensureProjectReady()
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                VfsUtil.saveText(
                    mainSourceRootFixture.get().virtualFile.createChildData(this, "TraversalLifecycleUsage.kt"),
                    """
                    package demo

                    fun traversalLifecycleUses(): List<String> = listOf(greet("one"), greet("two"), greet("three"))
                    """.trimIndent(),
                )
            }
        }
        waitUntilIndexesAreReady(project)
        val usageFile = readAction {
            checkNotNull(mainSourceRootFixture.get().findFile("TraversalLifecycleUsage.kt"))
        }
        val (workspaceRoot, position) = readAction {
            commonWorkspaceRoot(sampleFile.virtualFile.path, usageFile.virtualFile.path) to
                FilePosition(sampleFile.virtualFile.path, sampleFile.text.indexOf("greet"))
        }

        val exhaustedCloseCount = AtomicInteger()
        val exhaustedBackend = backend(
            workspaceRoot = workspaceRoot,
            referenceTraversalObserver = ReferenceTraversalObserver { exhaustedCloseCount.incrementAndGet() },
        )
        val exhausted = exhaustedBackend.findReferences(
            ReferencesQuery(position = position, includeDeclaration = false, maxResults = 50),
        )
        assertEquals(null, exhausted.page)
        assertEquals(1, exhaustedCloseCount.get())

        val shutdownCloseCount = AtomicInteger()
        val shutdownBackend = backend(
            workspaceRoot = workspaceRoot,
            referenceTraversalObserver = ReferenceTraversalObserver { shutdownCloseCount.incrementAndGet() },
        )
        val retained = shutdownBackend.findReferences(
            ReferencesQuery(position = position, includeDeclaration = false, maxResults = 1),
        )
        assertNotNull(retained.page?.nextPageToken)
        shutdownBackend.close()
        shutdownBackend.close()
        assertEquals(1, shutdownCloseCount.get())

        var failClock = false
        val exceptionCloseCount = AtomicInteger()
        val exceptionBackend = backend(
            workspaceRoot = workspaceRoot,
            referenceSearchClock = ReferenceSearchClock {
                if (failClock) error("clock failure") else System.nanoTime()
            },
            referenceTraversalObserver = ReferenceTraversalObserver { exceptionCloseCount.incrementAndGet() },
        )
        val first = exceptionBackend.findReferences(
            ReferencesQuery(position = position, includeDeclaration = false, maxResults = 1),
        )
        failClock = true
        val failure = runCatching {
            exceptionBackend.findReferences(
                ReferencesQuery(
                    position = position,
                    includeDeclaration = false,
                    maxResults = 1,
                    pageToken = requireNotNull(first.page?.nextPageToken),
                ),
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(1, exceptionCloseCount.get())
    }

    @Test
    fun `indexed reference continuation rejects a changed source generation`() = runBlocking {
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
        var generation = SourceIndexGeneration(1)
        val lookup = ReferenceIndexLookup { targetFqName, _, _ ->
            IndexedReferenceLookupResult.Ready(
                page = SymbolReferencePage(
                    references = listOf(
                        SymbolReferenceRow(
                            sourcePath = referenceData.usageFilePath,
                            sourceOffset = referenceData.usageOffset,
                            targetFqName = targetFqName,
                            targetPath = referenceData.declarationFilePath,
                            targetOffset = referenceData.declarationOffset,
                        ),
                    ),
                    nextOffset = NonNegativeInt(1),
                ),
                generation = generation,
            )
        }
        val backend = backend(referenceData.workspaceRoot, referenceIndexLookup = lookup)
        val position = FilePosition(referenceData.declarationFilePath, referenceData.declarationOffset)
        val first = backend.findReferences(ReferencesQuery(position, maxResults = 1))
        generation = SourceIndexGeneration(2)

        val failure = runCatching {
            backend.findReferences(
                ReferencesQuery(
                    position = position,
                    maxResults = 1,
                    pageToken = requireNotNull(first.page?.nextPageToken),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is ConflictException)
        assertTrue(failure?.message.orEmpty().contains("source index changed"))
    }

    @Test
    fun `production source store mutation between indexed pages rejects continuation`() = runBlocking {
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
        val storeRoot = Files.createTempDirectory("kast-reference-generation")
        SqliteSourceIndexStore(storeRoot).use { store ->
            store.ensureSchema()
            store.upsertSymbolReference(
                sourcePath = referenceData.declarationFilePath,
                sourceOffset = referenceData.declarationOffset,
                targetFqName = "demo.greet",
                targetPath = referenceData.declarationFilePath,
                targetOffset = referenceData.declarationOffset,
            )
            store.upsertSymbolReference(
                sourcePath = referenceData.usageFilePath,
                sourceOffset = referenceData.usageOffset,
                targetFqName = "demo.greet",
                targetPath = referenceData.declarationFilePath,
                targetOffset = referenceData.declarationOffset,
            )
            val lookup = ReferenceIndexLookup { targetFqName, offset, maxResults ->
                val generated = store.generatedReferencePageToSymbol(targetFqName, offset, maxResults)
                IndexedReferenceLookupResult.Ready(generated.page, generated.generation)
            }
            val backend = backend(referenceData.workspaceRoot, referenceIndexLookup = lookup)
            val position = FilePosition(referenceData.declarationFilePath, referenceData.declarationOffset)
            val first = backend.findReferences(ReferencesQuery(position, maxResults = 1))

            store.clearReferencesFromFile(referenceData.usageFilePath)

            val failure = runCatching {
                backend.findReferences(
                    ReferencesQuery(
                        position = position,
                        maxResults = 1,
                        pageToken = requireNotNull(first.page?.nextPageToken),
                    ),
                )
            }.exceptionOrNull()
            assertTrue(failure is ConflictException)
            assertTrue(failure?.message.orEmpty().contains("source index changed"))
        }
    }

    @Test
    fun `indexed reference pages preserve cumulative search scope evidence`() = runBlocking {
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
        val lookup = ReferenceIndexLookup { targetFqName, offset, _ ->
            val row = if (offset.value == 0) {
                SymbolReferenceRow(
                    sourcePath = referenceData.declarationFilePath,
                    sourceOffset = referenceData.declarationOffset,
                    targetFqName = targetFqName,
                    targetPath = null,
                    targetOffset = null,
                )
            } else {
                SymbolReferenceRow(
                    sourcePath = referenceData.usageFilePath,
                    sourceOffset = referenceData.usageOffset,
                    targetFqName = targetFqName,
                    targetPath = null,
                    targetOffset = null,
                )
            }
            IndexedReferenceLookupResult.Ready(
                page = SymbolReferencePage(
                    references = listOf(row),
                    nextOffset = if (offset.value == 0) NonNegativeInt(1) else null,
                ),
                generation = SourceIndexGeneration(1),
            )
        }
        val backend = backend(referenceData.workspaceRoot, referenceIndexLookup = lookup)
        val position = FilePosition(referenceData.declarationFilePath, referenceData.declarationOffset)
        val first = backend.findReferences(ReferencesQuery(position, maxResults = 1))
        val second = backend.findReferences(
            ReferencesQuery(
                position = position,
                maxResults = 1,
                pageToken = requireNotNull(first.page?.nextPageToken),
            ),
        )

        assertEquals(1, first.searchScope?.candidateFileCount)
        assertEquals(2, second.searchScope?.candidateFileCount)
        assertEquals(2, second.searchScope?.searchedFileCount)
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
            (result.searchScope?.searchedFileCount ?: Int.MAX_VALUE) <=
                (result.searchScope?.candidateFileCount ?: 0),
        )
    }

    @Test
    fun `reference continuation generation is captured inside the traversal read epoch`() = runBlocking {
        ensureProjectReady()
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                VfsUtil.saveText(
                    mainSourceRootFixture.get().virtualFile.createChildData(this, "ConcurrentReferenceUsage.kt"),
                    """
                    package demo

                    fun concurrentUses(): List<String> = listOf(greet("one"), greet("two"))
                    """.trimIndent(),
                )
            }
        }
        waitUntilIndexesAreReady(project)
        val usageFile = readAction {
            checkNotNull(mainSourceRootFixture.get().findFile("ConcurrentReferenceUsage.kt"))
        }
        val (workspaceRoot, position) = readAction {
            commonWorkspaceRoot(sampleFile.virtualFile.path, usageFile.virtualFile.path) to
                FilePosition(sampleFile.virtualFile.path, sampleFile.text.indexOf("greet"))
        }
        val generation = AtomicLong(1)
        val enteredReadEpoch = CountDownLatch(1)
        val releaseReadEpoch = CountDownLatch(1)
        val blockedOnce = AtomicBoolean(false)
        val observer = IdeaReadEpochObserver { kind ->
            if (kind == IdeaReadEpochKind.REFERENCES && blockedOnce.compareAndSet(false, true)) {
                enteredReadEpoch.countDown()
                assertTrue(releaseReadEpoch.await(10, TimeUnit.SECONDS))
            }
        }
        val backend = backend(
            workspaceRoot = workspaceRoot,
            referenceIndexLookup = ReferenceIndexLookup.Unavailable,
            psiGeneration = generation::get,
            readEpochObserver = observer,
        )
        val firstDeferred = async(Dispatchers.Default) {
            backend.findReferences(
                ReferencesQuery(position = position, includeDeclaration = false, maxResults = 1),
            )
        }
        assertTrue(enteredReadEpoch.await(10, TimeUnit.SECONDS))

        val writeStarted = CountDownLatch(1)
        val writeCompleted = CountDownLatch(1)
        application.invokeLater {
            writeStarted.countDown()
            application.runWriteAction { generation.set(2) }
            writeCompleted.countDown()
        }
        assertTrue(writeStarted.await(10, TimeUnit.SECONDS))
        assertTrue(!writeCompleted.await(100, TimeUnit.MILLISECONDS))

        releaseReadEpoch.countDown()
        val first = firstDeferred.await()
        assertTrue(writeCompleted.await(10, TimeUnit.SECONDS))
        val failure = runCatching {
            backend.findReferences(
                ReferencesQuery(
                    position = position,
                    includeDeclaration = false,
                    maxResults = 1,
                    pageToken = requireNotNull(first.page?.nextPageToken),
                ),
            )
        }.exceptionOrNull()
        assertTrue(failure is ConflictException)
        assertTrue(failure?.message.orEmpty().contains("PSI changed"))
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

    private fun createIrrelevantKotlinFiles(count: Int): List<String> {
        val suffix = System.nanoTime().toString()
        val fileNames = (0 until count).map { index -> "Irrelevant${suffix}_$index.kt" }
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                val sourceRoot = mainSourceRootFixture.get().virtualFile
                fileNames.forEachIndexed { index, fileName ->
                    val file = sourceRoot.createChildData(this, fileName)
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
        return fileNames
    }

    private fun deleteKotlinFiles(fileNames: List<String>) {
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                val sourceRoot = mainSourceRootFixture.get().virtualFile
                fileNames.forEach { fileName -> sourceRoot.findChild(fileName)?.delete(this) }
            }
        }
        waitUntilIndexesAreReady(project)
    }

    private suspend fun collectAllReferencePages(
        backend: KastPluginBackend,
        position: FilePosition,
    ): List<io.github.amichne.kast.api.contract.Location> {
        val references = mutableListOf<io.github.amichne.kast.api.contract.Location>()
        var pageToken: String? = null
        do {
            val result = backend.findReferences(
                ReferencesQuery(
                    position = position,
                    includeDeclaration = false,
                    maxResults = 50,
                    pageToken = pageToken,
                ),
            )
            references += result.references
            pageToken = result.page?.nextPageToken
        } while (pageToken != null)
        return references
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
