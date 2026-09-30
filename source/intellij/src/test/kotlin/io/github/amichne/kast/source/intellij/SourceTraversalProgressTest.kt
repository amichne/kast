package io.github.amichne.kast.source.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiManager
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.source.contract.Containment
import io.github.amichne.kast.source.contract.DeclarationKind
import io.github.amichne.kast.source.contract.DeclarationKindSelection
import io.github.amichne.kast.source.contract.DeclarationVisibility
import io.github.amichne.kast.source.contract.EntityFilter
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.RegionSelection
import io.github.amichne.kast.source.contract.SourceEntity
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceEntityName
import io.github.amichne.kast.source.contract.SourceRange
import io.github.amichne.kast.source.contract.SourceReadAnchor
import io.github.amichne.kast.source.contract.SourceReadContinuationState
import io.github.amichne.kast.source.contract.SourceReadPage
import io.github.amichne.kast.source.contract.SourceReadRequest
import io.github.amichne.kast.source.contract.SourceReadResult
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.source.contract.SourceTextByteLimit
import io.github.amichne.kast.source.contract.SourceTextIdentity
import io.github.amichne.kast.source.contract.TextProjection
import io.github.amichne.kast.source.contract.Utf16CodeUnitCount
import io.github.amichne.kast.source.contract.Utf16CodeUnitOffset
import io.github.amichne.kast.source.contract.VisibilitySelection
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Real physical PSI traversal; visibility is an explicit observation seam, not a K2 authority claim. */
class SourceTraversalProgressTest {
    @Test
    fun `121 physical declarations exhaust with work32 and entity limits1 5 and20 without repeated projection`(
        @TempDir home: Path
    ) = runTest {
        val expected = (0 until 121).map { "function${it.toString().padStart(3, '0')}" }
        val text = expected.joinToString("\n") { "fun $it() = 1" }
        physical(home, text) { file, snapshot ->
            for (limit in listOf(1, 5, 20)) {
                val observation = Observation()
                val projected = mutableListOf<String>()
                val result =
                    drain(file, snapshot, declarations(Containment.DIRECT), limit, observation) { declaration, _ ->
                        projected += checkNotNull(declaration.name)
                        DeclarationVisibility.PUBLIC
                    }
                assertEquals(expected, result.map { (it.selector.name as SourceEntityName.Present).value })
                assertEquals(121, result.map { it.selector.fingerprint }.distinct().size)
                assertEquals(121, observation.counts[IntellijReadCounter.SOURCE_ENTITIES_PROJECTED])
                assertEquals(expected, projected)
            }
        }
    }

    @Test
    fun `excluded descendant prefix and rejected visibility preserve structural progress`(@TempDir home: Path) =
        runTest {
            val text =
                "class Excluded { " +
                    (0 until 100).joinToString(" ") { "fun nested$it() = 0" } +
                    " }\n" +
                    (0 until 100).joinToString("\n") { "private fun hidden$it() = 0" } +
                    "\nfun retained() = 1"
            physical(home, text) { file, snapshot ->
                val projected = mutableListOf<String>()
                val result =
                    drain(
                        file,
                        snapshot,
                        declarations(Containment.DIRECT, DeclarationVisibility.PUBLIC),
                        1,
                        Observation(),
                    ) { declaration, _ ->
                        val name = checkNotNull(declaration.name)
                        check(!name.startsWith("nested")) { "Excluded depth reached compiler projection" }
                        projected += name
                        if (name.startsWith("hidden")) DeclarationVisibility.PRIVATE else DeclarationVisibility.PUBLIC
                    }
                assertEquals(listOf("retained"), result.map { (it.selector.name as SourceEntityName.Present).value })
                assertEquals((0 until 100).map { "hidden$it" } + "retained", projected)
            }
        }

    @Test
    fun `same parameter property and value phases survive a one entity page`(@TempDir home: Path) = runTest {
        physical(home, "class Box(val item: Int)") { file, snapshot ->
            val matching =
                EntitySelection.matching(
                        Containment.DESCENDANTS,
                        listOf(
                            EntityFilter.Declarations(
                                DeclarationKindSelection.from(setOf(DeclarationKind.PROPERTY)).value(),
                                VisibilitySelection.Any,
                            ),
                            EntityFilter.Parameters,
                        ),
                    )
                    .value()
            val observation = Observation()
            val result = drain(file, snapshot, matching, 1, observation)
            assertEquals(2, result.size)
            assertInstanceOf(SourceEntity.Declaration::class.java, result[0])
            assertInstanceOf(SourceEntity.ValueParameter::class.java, result[1])
            assertEquals(result[0].selector.range, result[1].selector.range)
            assertNotEquals(result[0].selector.fingerprint, result[1].selector.fingerprint)
            assertEquals(2, observation.counts[IntellijReadCounter.SOURCE_ENTITIES_PROJECTED])
        }
    }

    private suspend fun drain(
        file: KtFile,
        snapshot: SourceSnapshot,
        selection: EntitySelection,
        limit: Int,
        observation: Observation,
        visibility: (org.jetbrains.kotlin.psi.KtNamedDeclaration, NativeVisibilityTarget) -> DeclarationVisibility? =
            { _, _ ->
                DeclarationVisibility.PUBLIC
            },
    ): List<SourceEntity> {
        val region = SourceSelector.issueRoot(sourceRange(snapshot, 0, snapshot.length.value), SourceRegionKind.FILE)
        val fixture = PhysicalProjection(file, snapshot, region, observation, visibility)
        val port =
            IntellijSourceReadPort(
                IntellijSourceRegionAccess { _, request, cursor -> fixture.capture(request, cursor) },
                TestSourceCursorPort(),
            )
        val facts = mutableListOf<SourceEntity>()
        var page: SourceReadPage = SourceReadPage.First
        var pages = 0
        while (true) {
            check(pages++ <= snapshot.length.value) { "A finite physical fixture did not exhaust" }
            val request =
                SourceReadRequest(
                    SourceReadAnchor.Source(region),
                    RegionSelection.Anchor,
                    selection,
                    TextProjection.None,
                    SourceEntityLimit.parse(limit).value(),
                    SourceTextByteLimit.parse(1024).value(),
                    page,
                    ResourceBudget(
                        ResultLimit.parse(limit).value(),
                        WorkUnitLimit.parse(32).value(),
                        ElapsedTimeLimitMillis.parse(1000).value(),
                    ),
                )
            when (val result = port.read(snapshot.context, request)) {
                is SourceReadResult.Complete -> return facts + result.entities
                is SourceReadResult.Rejected ->
                    error("Physical traversal page $pages rejected: ${result.reason}; counts=${observation.counts}")
                is SourceReadResult.Qualified -> {
                    facts += result.entities
                    val continuation =
                        assertInstanceOf(
                                SourceReadContinuationState.Available::class.java,
                                result.qualification.continuation,
                            )
                            .continuation
                    page = SourceReadPage.Continue(continuation)
                }
            }
        }
    }

    private inner class PhysicalProjection(
        val file: KtFile,
        val snapshot: SourceSnapshot,
        val region: SourceSelector,
        val observation: Observation,
        val visibility: (org.jetbrains.kotlin.psi.KtNamedDeclaration, NativeVisibilityTarget) -> DeclarationVisibility?,
    ) {
        fun capture(request: SourceReadRequest, cursor: IntellijSourceEntityCursor): IntellijSourceRegionAccessResult {
            val selected = request.entities as EntitySelection.Matching
            val projection =
                IntellijSourceEntityAttempt.collect(
                    IntellijSourceExecution(request.resources, { 0L }),
                    selected,
                    cursor,
                    request.entityLimit,
                ) { attempt ->
                    NativeSourceEntityEnumerator(
                            LiveSourceDocument(snapshot, file.text, file),
                            NativeSourceRegion(file.textRange, SourceRegionKind.FILE, file),
                            region,
                            includeDeclarations = true,
                            includeParameters = EntityFilter.Parameters in selected.filters,
                            includeCalls = false,
                            includeReferences = false,
                            containment = selected.containment,
                            attempt = attempt,
                            visibility = visibility,
                            target = { error("No target resolution is permitted") },
                            observation = observation,
                        )
                        .enumerate()
                }
            return when (projection) {
                is NativeSourceEntityProjection.Projected ->
                    IntellijSourceRegionAccessResult.Selected(
                        IntellijSelectedSourceCapture.create(
                                snapshot,
                                region,
                                region,
                                file.text,
                                projection.page,
                            )
                            .value()
                    )
                is NativeSourceEntityProjection.Rejected ->
                    error("Physical native projection rejected: ${projection.reason}; counts=${observation.counts}")
            }
        }
    }

    private fun declarations(containment: Containment, visibility: DeclarationVisibility? = null): EntitySelection =
        EntitySelection.matching(
                containment,
                listOf(
                    EntityFilter.Declarations(
                        DeclarationKindSelection.from(setOf(DeclarationKind.FUNCTION)).value(),
                        if (visibility == null) VisibilitySelection.Any
                        else VisibilitySelection.exact(setOf(visibility)).value(),
                    )
                ),
            )
            .value()

    private class Observation : IntellijReadObservation {
        val counts = mutableMapOf<IntellijReadCounter, Int>()

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            counts[counter] = (counts[counter] ?: 0) + amount
        }

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit
    }

    @OptIn(
        CompilerConfiguration.Internals::class,
        org.jetbrains.kotlin.K1Deprecation::class,
        org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class,
    )
    private suspend fun physical(home: Path, text: String, assertion: suspend (KtFile, SourceSnapshot) -> Unit) {
        val properties =
            listOf("idea.home.path", "idea.config.path", "idea.system.path").associateWith(System::getProperty)
        Files.createDirectories(home.resolve("bin"))
        Files.writeString(home.resolve("bin/idea.properties"), "")
        System.setProperty("idea.home.path", home.toString())
        System.setProperty("idea.config.path", home.resolve("config").toString())
        System.setProperty("idea.system.path", home.resolve("system").toString())
        val disposable = Disposer.newDisposable()
        try {
            val environment =
                KotlinCoreEnvironment.createForTests(
                    disposable,
                    CompilerConfiguration().apply { extensionsStorage = CompilerPluginRegistrar.ExtensionStorage() },
                    EnvironmentConfigFiles.JVM_CONFIG_FILES,
                )
            val path = home.resolve("Page.kt")
            Files.writeString(path, text)
            val virtual = checkNotNull(environment.findLocalFile(path.toRealPath().toString()))
            val file = PsiManager.getInstance(environment.project).findFile(virtual) as KtFile
            val root = CanonicalWorkspaceRoot.fromCanonicalPath(home.toRealPath()).value()
            val snapshot =
                SourceSnapshot.create(
                    SemanticReadLease(root, EvidenceGeneration.parse(42).value()),
                    WorkspaceStateIdentity.parse("workspace-state-v1|physical").value(),
                    SymbolDiscoveryFileIdentity.Workspace(
                        CanonicalWorkspaceFilePath.fromCanonicalPath(root, path.toRealPath()).value()
                    ),
                    SourceTextIdentity.fromNormalizedCommittedText(text),
                    Utf16CodeUnitCount.parse(text.length).value(),
                )
            assertion(file, snapshot)
        } finally {
            val application = ApplicationManager.getApplication()
            if (application == null) Disposer.dispose(disposable)
            else application.runWriteAction { Disposer.dispose(disposable) }
            properties.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }

    private fun sourceRange(snapshot: SourceSnapshot, start: Int, end: Int) =
        SourceRange.create(snapshot, Utf16CodeUnitOffset.parse(start).value(), Utf16CodeUnitOffset.parse(end).value())
            .value()

    private fun <T> Refinement<T, *>.value(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Physical source fixture rejected: $failure")
        }
}
