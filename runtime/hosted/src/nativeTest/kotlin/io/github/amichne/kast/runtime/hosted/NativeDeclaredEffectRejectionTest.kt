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
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionUnprovenReason
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
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
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

/** Missing metadata must remain a typed public rejection with retained callback evidence. */
class NativeDeclaredEffectRejectionTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testAbsentEffectDoesNotAcquireUnknown() = assertRejected(DeclaredRejectionCase.ABSENT)

    fun testOtherFormalEffectDoesNotAcquireTheDeclaredEffect() = assertRejected(DeclaredRejectionCase.OTHER_FORMAL)

    fun testSameNameStringOverloadDoesNotAcquireTheIntContract() = assertRejected(DeclaredRejectionCase.STRING_OVERLOAD)

    private fun assertRejected(case: DeclaredRejectionCase) = runBlocking {
        DeclaredEffectArchive.compile().use { archive ->
            val fixture = prepare(case, archive)
            val protocol = fixture.protocol()
            val outcome = protocol.execute(request = request(), lease = fixture.lease, budget = budget, policy = policy)
            assertTrue(
                "Expected typed rejection for ${case.name}, received $outcome",
                outcome is OperationOutcome.Rejected,
            )
            val reason = (outcome as OperationOutcome.Rejected).reason as QueryRunRejection.CompletionUnproven
            assertEquals(QueryCompletionUnprovenReason.CALLBACK_GRAPH_UNPROVEN, reason.reason)
            val cause = checkNotNull(reason.callbackGraphFailure).cause as QueryCallbackGraphCauseDocument.Unresolved
            assertEquals(
                listOf(QueryCallbackFlowCauseDocument.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN),
                cause.obligations.values,
            )
            assertEquals(QueryCallbackInvocationScanDocument.INCOMPLETE, cause.scan)
            val evidence = reason.evidence as QueryCompletionEvidenceDocument.Retained
            val retained =
                protocol.execute(
                    request = evidence.readRequest(),
                    lease = fixture.lease,
                    budget = budget,
                    policy = policy,
                )
            assertTrue("Retained rejection must stay qualified", retained is OperationOutcome.Qualified)
            val result = (retained as OperationOutcome.Qualified).evidence.payload
            assertRetained(case, result)
        }
    }

    private fun assertRetained(case: DeclaredRejectionCase, result: QueryRunResult) {
        assertTrue(result.items.values.isEmpty())
        val callback = result.relationObservations.values.flatMap { it.callbackObservations.values }.single()
        assertEquals("proof.target", declaredIdentity(callback.target.compilerTarget.compilerEvidence.signature))
        assertEquals(
            "consumer.control",
            declaredIdentity(callback.lexicalOwner.compilerTarget.compilerEvidence.signature),
        )
        val flow = callback.flow as QueryCallbackFlowDocument.Observed
        val binding = flow.binding as QueryCallbackBindingDocument.Unavailable
        assertEquals(QueryCallbackFlowCauseDocument.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN, binding.cause)
        assertEquals(
            listOf(QueryCallbackFlowCauseDocument.DEPENDENCY_CONTRACT_EFFECT_UNPROVEN),
            flow.obligations.values,
        )
        assertEquals(QueryCallbackInvocationScanDocument.INCOMPLETE, flow.scan)
        assertTrue(flow.invocations.values.isEmpty())
        val expectedStart = case.source.indexOf("proof.target()") + "proof.".length
        assertEquals(expectedStart, callback.occurrence.range.startInclusive.value)
        assertEquals(expectedStart + "target".length, callback.occurrence.range.endExclusive.value)
        println("R1 public negative=${case.name} cause=${binding.cause} retained=QUALIFIED")
    }

    private fun prepare(case: DeclaredRejectionCase, archive: DeclaredEffectArchive): NativeScopedTraceFixture {
        lateinit var fixture: NativeScopedTraceFixture
        ApplicationManager.getApplication().invokeAndWait {
            val base = getOrCreateProjectBaseDir()
            val directory =
                WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
                    base.createChildDirectory(this, "src").createChildDirectory(this, "backend-service-clients")
                }
            configureModule(base, directory, archive)
            val files = sourceFiles(directory, case)
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
                            gradleProjectPath = ":semantic-effects",
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

    private fun configureModule(base: VirtualFile, source: VirtualFile, archive: DeclaredEffectArchive) {
        val sdk = JavaSdk.getInstance().createJdk("declared-effect-trace-jdk", System.getProperty("java.home"), false)
        registerTestProjectJdk(sdk)
        ModuleRootModificationUtil.updateModel(module) { model ->
            model.sdk = sdk
            model.addContentEntry(base).addSourceFolder(source, false)
            val roots = model.moduleLibraryTable.createLibrary("declared-effect-trace-binary").modifiableModel
            roots.addRoot("jar://${archive.jar}!/", OrderRootType.CLASSES)
            val stdlib = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
            roots.addRoot("jar://$stdlib!/", OrderRootType.CLASSES)
            roots.commit()
        }
    }

    private fun sourceFiles(directory: VirtualFile, case: DeclaredRejectionCase): List<VirtualFile> =
        WriteCommandAction.writeCommandAction(project).compute<List<VirtualFile>, RuntimeException> {
            listOf(
                directory.createChildData(this, "Targets.kt").also {
                    it.setBinaryContent(DeclaredEffectArchive.resource("Targets.kt"))
                },
                directory.createChildData(this, "Caller.kt").also { it.setBinaryContent(case.source.toByteArray()) },
            )
        }

    private fun request() =
        QueryRunRequest.Run(
            from =
                QueryFromDocument.Symbols(
                    QueryMatchDocument.Name(
                        controlText("target"),
                        SymbolDiscoveryMatchDocument.EXACT_NAME,
                    ),
                    QueryScopeDocument(controlBounded(listOf(controlText("main"))), directory, null),
                    controlBounded(listOf(QueryDeclarationKindDocument.FUNCTION)),
                ),
            steps = controlBounded(listOf(QueryStepDocument.Related(RelationKindDocument.CALLERS, domain))),
            output =
                QueryOutputDocument.Symbols(
                    controlBounded(
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

    private val directory =
        QueryDirectoryScopeDocument(
            controlText(NativeScopedTraceFixture.SOURCE_DIRECTORY),
            QueryContainmentDocument.DESCENDANTS,
        )
    private val domain =
        QueryExpansionScopeDocument.Sources(
            sourceSets = controlBounded(listOf(controlText("main"))),
            directory = directory,
            sourcePolicy = QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY,
            generatedSources = QueryDiscoveryInclusionPolicyDocument.EXCLUDE,
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

private enum class DeclaredRejectionCase(val resource: String) {
    ABSENT("r1-control-absent.kt"),
    OTHER_FORMAL("r1-control-other-formal.kt"),
    STRING_OVERLOAD("r1-control-string-overload.kt");

    val source = DeclaredEffectArchive.resource(resource).toString(Charsets.UTF_8)
}

private fun controlText(value: String) = ProtocolText.parse(value).traceRefined()

private fun <Value> controlBounded(values: List<Value>) = BoundedProtocolList.create(values).traceRefined()
