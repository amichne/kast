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
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
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
import io.github.amichne.kast.protocol.contract.QueryRelationCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryResultInterpretationDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
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

/** Real K2 source relationships remain usable when callback execution lacks proof. */
class NativeTraceEffectGapTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testNestedSourceCallbacksKeepSourceRelationships() =
        assertTrace(
            "scope { scope { proof.target() } }",
            setOf(QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION),
        )

    fun testNestedSourceAndLibraryCallbacksKeepSourceRelationships() =
        assertTrace(
            "kotlin.with(Unit) { scope { proof.target() } }",
            setOf(
                QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION,
                QueryCallbackFlowCauseDocument.OUTSIDE_DOMAIN,
                QueryCallbackFlowCauseDocument.EXTERNAL_CALLABLE,
            ),
        )

    fun testNestedLibraryCallbacksKeepSourceRelationships() =
        assertTrace(
            "kotlin.with(Unit) { Unit.also { proof.target() } }",
            setOf(QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION),
        )

    fun testAbsentBinaryEffectKeepsSourceRelationships() {
        DeclaredEffectArchive.compile().use { archive ->
            assertTrace(
                "effects.absent(block = { proof.target() }, marker = 1)",
                setOf(QueryCallbackFlowCauseDocument.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN),
                listOf(archive.jar),
            )
        }
    }

    private fun assertTrace(
        expression: String,
        causes: Set<QueryCallbackFlowCauseDocument>,
        libraries: List<Path> = emptyList(),
    ) = runBlocking {
        val source =
            "package consumer\nfun scope(block: () -> Unit) { block() }\n" +
                "fun client() { $expression }\nfun entry() { client() }\n"
        val fixture = prepare(source, libraries)
        val outcome =
            fixture.protocol().execute(request = request, lease = fixture.lease, budget = budget, policy = policy)
        assertTrue(
            "Expected complete source TRACE, received ${outcome::class.simpleName}",
            outcome is OperationOutcome.Complete,
        )
        val result = (outcome as OperationOutcome.Complete).evidence.payload
        assertSourceRelationships(result, source, causes)
    }

    private fun assertSourceRelationships(
        result: QueryRunResult,
        source: String,
        causes: Set<QueryCallbackFlowCauseDocument>,
    ) {
        assertEquals(QueryResultInterpretationDocument.SourceRelationships(), result.interpretation)
        val symbols = result.items.values.map { it as QueryResultItemDocument.ExactSymbol }
        assertEquals(
            setOf("proof.target", "consumer.client", "consumer.entry"),
            symbols.map { declaredIdentity(checkNotNull(it.signature)) }.toSet(),
        )
        assertTrue(result.failures.values.isEmpty())
        assertTrue(result.omissions.values.isEmpty())
        assertTrue(result.relationObservations.values.all { it.coverage == QueryRelationCoverageDocument.Exhausted })
        val callback = result.relationObservations.values.flatMap { it.callbackObservations.values }.single()
        assertEquals("proof.target", declaredIdentity(callback.target.compilerTarget.compilerEvidence.signature))
        assertEquals(
            "consumer.client",
            declaredIdentity(callback.lexicalOwner.compilerTarget.compilerEvidence.signature),
        )
        val start = source.indexOf("proof.target()") + "proof.".length
        assertEquals(start, callback.occurrence.range.startInclusive.value)
        assertEquals(start + "target".length, callback.occurrence.range.endExclusive.value)
        val flow = callback.flow as QueryCallbackFlowDocument.Observed
        assertEquals(causes, flow.obligations.values.toSet())
        println("Native source TRACE gaps=${flow.obligations.values} symbols=${symbols.size}")
    }

    private fun prepare(source: String, libraries: List<Path>): NativeScopedTraceFixture {
        lateinit var fixture: NativeScopedTraceFixture
        ApplicationManager.getApplication().invokeAndWait {
            val base = getOrCreateProjectBaseDir()
            val directory =
                WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
                    base.createChildDirectory(this, "src").createChildDirectory(this, "backend-service-clients")
                }
            configureModule(base, directory, libraries)
            val files =
                WriteCommandAction.writeCommandAction(project).compute<List<VirtualFile>, RuntimeException> {
                    listOf(
                        directory.createChildData(this, "Targets.kt").also {
                            it.setBinaryContent("package proof\nfun target() = 1\n".toByteArray())
                        },
                        directory.createChildData(this, "Client.kt").also { it.setBinaryContent(source.toByteArray()) },
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
                            gradleProjectPath = ":trace-effects",
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

    private fun configureModule(base: VirtualFile, source: VirtualFile, libraries: List<Path>) {
        val sdk = JavaSdk.getInstance().createJdk("trace-effects-jdk", System.getProperty("java.home"), false)
        registerTestProjectJdk(sdk)
        ModuleRootModificationUtil.updateModel(module) { model ->
            model.sdk = sdk
            model.addContentEntry(base).addSourceFolder(source, false)
            val roots = model.moduleLibraryTable.createLibrary("trace-effects-binaries").modifiableModel
            val stdlib = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
            (libraries + listOf(stdlib)).forEach { roots.addRoot("jar://$it!/", OrderRootType.CLASSES) }
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
    private val request =
        QueryRunRequest.Run(
            from =
                QueryFromDocument.Symbols(
                    QueryMatchDocument.Name(gapText("target"), SymbolDiscoveryMatchDocument.EXACT_NAME),
                    QueryScopeDocument(gapBounded(listOf(gapText("main"))), directory, null),
                    gapBounded(listOf(QueryDeclarationKindDocument.FUNCTION)),
                ),
            steps = gapBounded(listOf(QueryStepDocument.Trace(domain))),
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
