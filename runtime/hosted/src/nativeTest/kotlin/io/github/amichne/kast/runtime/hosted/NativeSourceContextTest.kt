package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.SourceBodyKindDocument
import io.github.amichne.kast.protocol.contract.SourceEnclosingRegionKindDocument
import io.github.amichne.kast.protocol.contract.SourceEntitySelectionDocument
import io.github.amichne.kast.protocol.contract.SourceLineCountDocument
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocument
import io.github.amichne.kast.protocol.contract.SourceReadLimitationDocument
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SourceReadResult
import io.github.amichne.kast.protocol.contract.SourceRegionSelectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextProjectionDocument
import io.github.amichne.kast.protocol.contract.SourceTextRequestDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.protocol.QueryInvocationPolicy
import io.github.amichne.kast.query.protocol.SourceProtocolBudget
import io.github.amichne.kast.source.contract.SourceTextByteLimit
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

/** Real K2 selection and native source reads under one case-owned source basis. */
class NativeSourceContextTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testExactOverloadOccurrenceBodyExcerptAndRegionReentry() = runBlocking {
        val (fixture, _) = prepare()
        val protocol = fixture.protocol()
        val discovery = query(fixture, protocol, search("target"))
        val symbols = discovery.items.values.map { it as QueryResultItemDocument.ExactSymbol }
        assertEquals(EXPECTED_OVERLOADS, symbols.size)
        val exact = symbols.single {
            checkNotNull(it.location).range.startInclusive.value == source.indexOf("fun target(value: Int)")
        }
        val exactRead = read(fixture, SourceReadAnchorDocument.SnapshotSymbol(exact.ref.token), body)
        assertEquals(targetBody, returned(exactRead).text.value)
        val occurrences =
            query(
                    fixture,
                    protocol,
                    QueryRunRequest.Run(
                        from = QueryFromDocument.References(bounded(listOf(exact.ref))),
                        steps = bounded(listOf(QueryStepDocument.Related(RelationKindDocument.REFERENCES, domain))),
                        output = QueryOutputDocument.Occurrences,
                        execution = execution,
                    ),
                )
                .items
                .values
                .map { (it as QueryResultItemDocument.ReferenceOccurrence).occurrence.occurrence }
        assertEquals(EXPECTED_OCCURRENCES, occurrences.size)
        assertEquals(EXPECTED_OCCURRENCES, occurrences.map { it.candidateSelector }.distinct().size)
        val regions = occurrences.map { occurrence ->
            val selected =
                read(
                    fixture = fixture,
                    focus = SourceReadAnchorDocument.Candidate(occurrence.candidateSelector),
                    region = enclosingBody,
                    text = SourceTextRequestDocument.Excerpt(SourceLineCountDocument.parse(2).traceRefined()),
                )
            assertTrue(returned(selected).lines.let { it.endInclusive.value - it.startInclusive.value + 1 } <= 2)
            val expanded = read(fixture, SourceReadAnchorDocument.Source(selected.region.selection.selector), anchor)
            assertEquals(selected.region.selection.selector, expanded.region.selection.selector)
            returned(expanded).text.value
        }
        assertEquals(listOf(firstBody, firstBody, nestedBody).sorted(), regions.sorted())
        assertOversized(fixture, exact)
    }

    private suspend fun assertOversized(fixture: NativeScopedTraceFixture, exact: QueryResultItemDocument.ExactSymbol) {
        val oversized =
            fixture
                .sourceProtocol()
                .execute(
                    sourceRequest(SourceReadAnchorDocument.SnapshotSymbol(exact.ref.token), body),
                    fixture.lease,
                    SourceProtocolBudget(
                        NativeScopedTraceFixture.resources,
                        SourceTextByteLimit.parse(1).traceRefined(),
                    ),
                )
        assertTrue(oversized.toString(), oversized is OperationOutcome.Qualified)
        assertEquals(
            listOf(SourceReadLimitationDocument.TEXT_BYTE_LIMIT_REACHED),
            (oversized as OperationOutcome.Qualified).qualification.limitations,
        )
    }

    fun testKnownChainAndMatchedSourceViews() = runBlocking {
        val (fixture, _) = prepare()
        val workflow =
            NativeSourceContextWorkflow(
                fixture = fixture,
                request =
                    search("first")
                        .copy(steps = bounded(listOf(QueryStepDocument.Related(RelationKindDocument.CALLEES, domain)))),
                budget = budget,
                policy = policy,
                expectedOffset = source.indexOf("fun target(value: Int)"),
                expectedBody = targetBody,
            )
        val before = workflow.run(SourceContextStrategy.FILE_RECONSTRUCTION)
        val after = workflow.run(SourceContextStrategy.STRUCTURAL_VIEW)
        assertEquals(before.nativeCounters, after.nativeCounters)
        assertTrue("The focused view did not reduce encoded output: $before / $after", after.bytes < before.bytes)
        println("SOURCE_CONTEXT_WORKFLOW before=$before after=$after counters=${fixture.observation.snapshot()}")
    }

    fun testChangedSourceRejectsTheOriginalExactAndRegionFocus() = runBlocking {
        val (fixture, file) = prepare()
        val symbol =
            query(fixture, fixture.protocol(), search("first")).items.values.single()
                as QueryResultItemDocument.ExactSymbol
        val selected = read(fixture, SourceReadAnchorDocument.SnapshotSymbol(symbol.ref.token), body)
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                file.setBinaryContent((source + "\n// changed\n").toByteArray())
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        for (focus in
            listOf(
                SourceReadAnchorDocument.SnapshotSymbol(symbol.ref.token),
                SourceReadAnchorDocument.Source(selected.region.selection.selector),
            )) {
            val outcome = fixture.sourceProtocol().execute(sourceRequest(focus, anchor), fixture.lease, sourceBudget)
            assertEquals(OperationOutcome.Rejected(SourceReadRejection.SOURCE_STATE_MISMATCH), outcome)
        }
    }

    private suspend fun query(
        fixture: NativeScopedTraceFixture,
        protocol: io.github.amichne.kast.query.protocol.CanonicalQueryProtocol,
        request: QueryRunRequest,
    ): QueryRunResult {
        val outcome = protocol.execute(request = request, lease = fixture.lease, budget = budget, policy = policy)
        assertTrue(outcome.toString(), outcome is OperationOutcome.Complete)
        return (outcome as OperationOutcome.Complete).evidence.payload
    }

    private suspend fun read(
        fixture: NativeScopedTraceFixture,
        focus: SourceReadAnchorDocument,
        region: SourceRegionSelectionDocument,
        text: SourceTextRequestDocument = SourceTextRequestDocument.Complete,
    ): SourceReadResult {
        val outcome = fixture.sourceProtocol().execute(sourceRequest(focus, region, text), fixture.lease, sourceBudget)
        assertTrue(outcome.toString(), outcome is OperationOutcome.Complete)
        return (outcome as OperationOutcome.Complete).evidence.payload
    }

    private fun returned(result: SourceReadResult) = result.text as SourceTextProjectionDocument.Returned

    private fun sourceRequest(
        focus: SourceReadAnchorDocument,
        region: SourceRegionSelectionDocument,
        text: SourceTextRequestDocument = SourceTextRequestDocument.Complete,
    ) = SourceReadRequest(anchor = focus, region = region, entities = SourceEntitySelectionDocument.None, text = text)

    private fun search(name: String) =
        QueryRunRequest.Run(
            from =
                QueryFromDocument.Symbols(
                    QueryMatchDocument.Name(text(name), SymbolDiscoveryMatchDocument.EXACT_NAME),
                    QueryScopeDocument(bounded(listOf(text("main"))), directory, null),
                    bounded(listOf(QueryDeclarationKindDocument.FUNCTION)),
                ),
            steps = bounded(emptyList()),
            output =
                QueryOutputDocument.Symbols(
                    bounded(
                        listOf(
                            QuerySymbolFieldDocument.NAME,
                            QuerySymbolFieldDocument.LOCATION,
                            QuerySymbolFieldDocument.SIGNATURE,
                        )
                    )
                ),
            execution = execution,
        )

    private fun prepare(): Pair<NativeScopedTraceFixture, VirtualFile> {
        lateinit var result: Pair<NativeScopedTraceFixture, VirtualFile>
        ApplicationManager.getApplication().invokeAndWait {
            val base = getOrCreateProjectBaseDir()
            val directory =
                WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
                    base.createChildDirectory(this, "src")
                }
            val sdk = JavaSdk.getInstance().createJdk("source-context-jdk", System.getProperty("java.home"), false)
            registerTestProjectJdk(sdk)
            ModuleRootModificationUtil.updateModel(module) { native ->
                native.sdk = sdk
                native.addContentEntry(base).addSourceFolder(directory, false)
                val library = native.moduleLibraryTable.createLibrary("source-context-stdlib")
                val roots = library.modifiableModel
                val jar = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
                roots.addRoot("jar://$jar!/", OrderRootType.CLASSES)
                roots.commit()
            }
            val file =
                WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
                    directory.createChildData(this, "Owners.kt").also { it.setBinaryContent(source.toByteArray()) }
                }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val root = CanonicalWorkspaceRoot.fromCanonicalPath(base.toNioPath()).traceRefined()
            val model =
                WorkspaceSearchScopeModel.compile(
                    root,
                    ImportedWorkspaceModelState.COMPLETE,
                    listOf(
                        WorkspaceSourceRootBoundary(
                            ideaModuleName = module.name,
                            linkedBuildRoot = base.toNioPath(),
                            gradleProjectPath = ":",
                            sourceSetName = "main",
                            sourceRoot = directory.toNioPath(),
                            sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                            provenance = WorkspaceSourceRootProvenance.AUTHORED,
                        )
                    ),
                ) as WorkspaceSearchScopeModelCompilation.Compiled
            result =
                NativeScopedTraceFixture(project = project, root = root, model = model.model, files = listOf(file)) to
                    file
        }
        return result
    }

    private val execution =
        QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE)
    private val directory = QueryDirectoryScopeDocument(text("src"), QueryContainmentDocument.DESCENDANTS)
    private val domain =
        QueryExpansionScopeDocument.Sources(
            sourceSets = bounded(listOf(text("main"))),
            directory = directory,
            sourcePolicy = QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY,
            generatedSources = QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
        )
    private val budget = QueryBudget(NativeScopedTraceFixture.resources, QueryByteLimit.parse(524288).traceRefined())
    private val sourceBudget =
        SourceProtocolBudget(NativeScopedTraceFixture.resources, SourceTextByteLimit.parse(65536).traceRefined())
    private val policy =
        QueryInvocationPolicy(
            previewRows = NativeScopedTraceFixture.resources.resultLimit,
            previewBytesLimit = budget.returnedBytes,
            retainedBytes = QueryByteLimit.parse(524288).traceRefined(),
            previewBytes = CanonicalQueryCliDocuments::previewBytes,
        )
    private val anchor = SourceRegionSelectionDocument.Anchor
    private val body = SourceRegionSelectionDocument.Body(SourceBodyKindDocument.CALLABLE)
    private val enclosingBody = SourceRegionSelectionDocument.Enclosing(SourceEnclosingRegionKindDocument.CALLABLE_BODY)

    companion object {
        private const val targetBody = "{\n        val adjusted = value + 1\n        return adjusted\n    }"
        private const val firstBody = "{\n        target(1)\n        target(2)\n    }"
        private const val nestedBody = "{\n            Outer().target(3)\n        }"
        private const val EXPECTED_OVERLOADS = 3
        private const val EXPECTED_OCCURRENCES = 3
        private val source =
            "package scoped\nclass Outer {\n" +
                "    fun target(value: Int): Int $targetBody\n" +
                "    fun target(value: String) = value.length\n" +
                "    fun first() $firstBody\n" +
                "    class Nested {\n" +
                "        fun target(value: Int) = value - 1\n" +
                "        fun second() $nestedBody\n" +
                "    }\n}\n" +
                (1..200).joinToString("\n") {
                    "// Unrelated context line $it supplies no declaration or reference evidence."
                } +
                "\n"

        private fun text(value: String) = ProtocolText.parse(value).traceRefined()

        private fun <T> bounded(values: List<T>) = BoundedProtocolList.create(values).traceRefined()
    }
}
