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
import io.github.amichne.kast.kernel.ReturnedByteLimit
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
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
import io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.query.contract.QueryBudget
import io.github.amichne.kast.query.contract.QueryByteLimit
import io.github.amichne.kast.query.protocol.QueryInvocationPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Confirmed direct callees and unknown callback execution keep separate evidence. */
class NativeDirectCalleeEvidenceTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testOpaqueSupplierKeepsDirectCalleeAndTypedGapInOneResponse() =
        assertIncompleteSupplier(
            "external fun opaque(): () -> String\nfun entry() = wrapper(opaque())\n",
            QueryCallbackFlowCauseDocument.EXTERNAL_CALLABLE,
        )

    fun testNestedSupplierKeepsDirectCalleeAndTypedGapInOneResponse() =
        assertIncompleteSupplier(
            "fun entry() = kotlin.run { wrapper { \"x\" } }\n",
            QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION,
        )

    private fun assertIncompleteSupplier(supplier: String, cause: QueryCallbackFlowCauseDocument) = runBlocking {
        val fixture = prepareCallees(supplier)
        val outcome =
            fixture
                .protocol()
                .execute(request = callees("wrapper"), lease = fixture.lease, budget = budget, policy = policy)
        assertTrue("Expected an explicit incomplete graph", outcome is OperationOutcome.Rejected)
        val rejection = (outcome as OperationOutcome.Rejected).reason as QueryRunRejection.CompletionUnproven
        val retained = rejection.evidence as QueryCompletionEvidenceDocument.Retained
        val sink = retained.preview.values.single() as QueryResultItemDocument.ExactSymbol
        assertEquals("consumer.sink", declaredIdentity(checkNotNull(sink.signature)))
        assertEquals(QueryCompletionCoverageDocument.Complete, rejection.originalCoverage)
        assertEquals(
            QueryCallbackGraphCauseDocument.Unavailable(cause),
            rejection.callbackGraphFailure?.cause,
        )
        val encoded = CanonicalQueryCliDocuments.project(outcome) as ProjectedOperationOutcome.Rejected
        val detail =
            Json.parseToJsonElement(encoded.document.value)
                .jsonObject
                .getValue("rejection")
                .jsonObject
                .getValue("detail")
                .jsonObject
        val preview = detail.getValue("evidence").jsonObject.getValue("preview").jsonArray
        assertEquals("sink", preview.single().jsonObject.getValue("name").jsonPrimitive.content)
        val graph = detail.getValue("cause").jsonObject.getValue("graphFailure").jsonObject
        assertEquals(cause.name, graph.getValue("cause").jsonObject.getValue("cause").jsonPrimitive.content)
    }

    fun testNoSuppliersCompletesScopedDirectCalleeQuery() = runBlocking {
        val fixture = prepareCallees()
        val outcome =
            fixture
                .protocol()
                .execute(request = callees("wrapper"), lease = fixture.lease, budget = budget, policy = policy)
        assertTrue("Expected an exhausted supplier inventory", outcome is OperationOutcome.Complete)
        val result = (outcome as OperationOutcome.Complete).evidence.payload
        val sink = result.items.values.single() as QueryResultItemDocument.ExactSymbol
        assertEquals("consumer.sink", declaredIdentity(checkNotNull(sink.signature)))
    }

    fun testPlainArgumentCompletesDirectCalleeQuery() = runBlocking {
        val fixture = prepareCallees()
        val outcome =
            fixture
                .protocol()
                .execute(request = callees("plain"), lease = fixture.lease, budget = budget, policy = policy)
        assertTrue("Expected a complete direct graph, received $outcome", outcome is OperationOutcome.Complete)
        val result = (outcome as OperationOutcome.Complete).evidence.payload
        val sink = result.items.values.single() as QueryResultItemDocument.ExactSymbol
        assertEquals("consumer.sink", declaredIdentity(checkNotNull(sink.signature)))
        assertEquals(QueryResultInterpretationDocument.QueryResult, result.interpretation)
    }

    private fun prepareCallees(supplier: String = "") =
        prepare(
            "package consumer\nfun sink(value: String) = value\n" +
                "fun wrapper(f: () -> String) = sink(f())\nfun plain() = sink(\"x\")\n" +
                supplier
        )

    private fun prepare(source: String): NativeScopedTraceFixture {
        lateinit var fixture: NativeScopedTraceFixture
        ApplicationManager.getApplication().invokeAndWait {
            val base = getOrCreateProjectBaseDir()
            val directory =
                WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
                    base.createChildDirectory(this, "src").createChildDirectory(this, "backend-service-clients")
                }
            configureModule(base, directory)
            val files =
                WriteCommandAction.writeCommandAction(project).compute<List<VirtualFile>, RuntimeException> {
                    listOf(
                        directory.createChildData(this, "Client.kt").also { it.setBinaryContent(source.toByteArray()) }
                    )
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
                            gradleProjectPath = ":direct-callees",
                            sourceSetName = "main",
                            sourceRoot = directory.toNioPath(),
                            sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                            provenance = WorkspaceSourceRootProvenance.AUTHORED,
                        )
                    ),
                ) as WorkspaceSearchScopeModelCompilation.Compiled
            fixture = NativeScopedTraceFixture(project = project, root = root, model = model.model, files = files)
        }
        return fixture
    }

    private fun configureModule(base: VirtualFile, source: VirtualFile) {
        val sdk = JavaSdk.getInstance().createJdk("direct-callees-jdk", System.getProperty("java.home"), false)
        registerTestProjectJdk(sdk)
        ModuleRootModificationUtil.updateModel(module) { model ->
            model.sdk = sdk
            model.addContentEntry(base).addSourceFolder(source, false)
            val roots = model.moduleLibraryTable.createLibrary("direct-callees-stdlib").modifiableModel
            val stdlib = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
            roots.addRoot("jar://$stdlib!/", OrderRootType.CLASSES)
            roots.commit()
        }
    }

    private val directory =
        QueryDirectoryScopeDocument(
            gapText(NativeScopedTraceFixture.SOURCE_DIRECTORY),
            QueryContainmentDocument.DESCENDANTS,
        )
    private val domain =
        QueryExpansionScopeDocument.Sources(
            sourceSets = gapBounded(listOf(gapText("main"))),
            directory = directory,
            sourcePolicy = QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY,
            generatedSources = QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
        )

    private fun callees(name: String) =
        QueryRunRequest.Run(
            from =
                QueryFromDocument.Symbols(
                    QueryMatchDocument.Name(gapText(name), SymbolDiscoveryMatchDocument.EXACT_NAME),
                    QueryScopeDocument(gapBounded(listOf(gapText("main"))), directory, null),
                    gapBounded(listOf(QueryDeclarationKindDocument.FUNCTION)),
                ),
            steps = gapBounded(listOf(QueryStepDocument.Related(RelationKindDocument.CALLEES, domain))),
            output =
                QueryOutputDocument.Symbols(
                    gapBounded(
                        listOf(
                            QuerySymbolFieldDocument.NAME,
                            QuerySymbolFieldDocument.LOCATION,
                            QuerySymbolFieldDocument.SIGNATURE,
                        )
                    )
                ),
            execution =
                QueryExecutionDocument(QueryExecutionKindDocument.EXHAUSTIVE, QueryExecutionBudgetDocument.INTERACTIVE),
            executionBudget =
                ExecutionBudgetDocument(
                    maxElapsedMillis = NativeScopedTraceFixture.resources.elapsedTimeLimit,
                    maxWorkUnits = NativeScopedTraceFixture.resources.workUnitLimit,
                    maxResults = NativeScopedTraceFixture.resources.resultLimit,
                    maxReturnedBytes = ReturnedByteLimit.parse(NativeScopedTraceFixture.RETURNED_BYTES).traceRefined(),
                ),
        )

    private val budget =
        QueryBudget(
            NativeScopedTraceFixture.resources,
            QueryByteLimit.parse(NativeScopedTraceFixture.RETURNED_BYTES).traceRefined(),
        )
    private val policy =
        QueryInvocationPolicy(
            previewRows = NativeScopedTraceFixture.resources.resultLimit,
            previewBytesLimit = budget.returnedBytes,
            retainedBytes = QueryByteLimit.parse(32_000_000L).traceRefined(),
            previewBytes = CanonicalQueryCliDocuments::previewBytes,
        )
}

private fun gapText(value: String) = ProtocolText.parse(value).traceRefined()

private fun <Value> gapBounded(values: List<Value>) = BoundedProtocolList.create(values).traceRefined()
