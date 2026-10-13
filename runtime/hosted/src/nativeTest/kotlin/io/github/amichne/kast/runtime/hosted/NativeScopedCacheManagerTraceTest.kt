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
import io.github.amichne.kast.protocol.contract.CompilerSignatureDocument
import io.github.amichne.kast.protocol.contract.ExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
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
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.protocol.contract.QueryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryStepDocument
import io.github.amichne.kast.protocol.contract.QuerySymbolFieldDocument
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.SymbolDocument
import io.github.amichne.kast.protocol.contract.SymbolQualifiedIdentityDocument
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
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/** Real K2 ports and public completion under a fixed source domain; no installed Host or enterprise replay. */
class NativeScopedCacheManagerTraceTest : NativeSmartModeFixtureTest() {
    private val lazyClientText =
        "package scoped\n" +
            "class InterestRatesClientImpl(private val cacheManager: CacheManager)\n" +
            "object InterestRatesModule {\n" +
            "    val interestRatesClient by kotlin.lazy { InterestRatesClientImpl(CacheManager()) }\n" +
            "}\nfun entry() = InterestRatesModule.interestRatesClient\n"

    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testScopedTraceAndEmptyReferenceControl() = runBlocking {
        val fixture = prepare()
        val protocol = fixture.protocol()
        val started = System.nanoTime()
        val outcome =
            protocol.execute(
                request = request("CacheManager", true),
                lease = fixture.lease,
                budget = budget,
                policy = policy,
            )
        assertTrue("Expected complete scoped TRACE, received $outcome", outcome is OperationOutcome.Complete)
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        val result = (outcome as OperationOutcome.Complete).evidence.payload
        assertTrace(result, fixture.lease.workspaceRoot)
        assertCallback(result)
        val projected = CanonicalQueryCliDocuments.project(outcome) as ProjectedOperationOutcome.Complete
        val encodedBytes = projected.document.value.toByteArray(Charsets.UTF_8).size
        println("Scoped TRACE elapsedMillis=$elapsedMillis encodedBytes=$encodedBytes")
        val attachedEntries =
            result.items.values.sumOf { (it as QueryResultItemDocument.ExactSymbol).connections.values.size }
        println("Scoped TRACE attachedConnectionEntries=$attachedEntries")
        println("Scoped TRACE counters=${fixture.observation.snapshot()}")
        assertTrue(fixture.observation[IntellijReadCounter.CALLBACK_DEPENDENCY_CONTRACTS_ADMITTED] > 0L)
        val empty =
            protocol.execute(
                request = request("execute", false),
                lease = fixture.lease,
                budget = budget,
                policy = policy,
            )
        assertTrue("Expected complete empty REFERENCES, received $empty", empty is OperationOutcome.Complete)
        assertTrue((empty as OperationOutcome.Complete).evidence.payload.items.values.isEmpty())
    }

    fun testLazyConstructorCallersKeepThePropertySupplyAndOccurrence() = runBlocking {
        val fixture = prepare(lazyClientText)
        val protocol = fixture.protocol()
        val outcome =
            protocol.execute(
                request = lazyRequest(),
                lease = fixture.lease,
                budget = budget,
                policy = policy,
            )
        if (outcome is OperationOutcome.Rejected) printLazyRejection(protocol, fixture, outcome.reason)
        assertTrue("Expected complete lazy TRACE", outcome is OperationOutcome.Complete)
        val result = (outcome as OperationOutcome.Complete).evidence.payload
        val names =
            result.items.values
                .map { identity(checkNotNull((it as QueryResultItemDocument.ExactSymbol).signature)) }
                .toSet()
        assertTrue(names.isEmpty())
        assertFalse(names.any { it.startsWith("unrelated.") })
        assertTrue(result.failures.values.isEmpty())
        assertTrue(result.omissions.values.isEmpty())
        assertLazyConstructor(result, fixture)
    }

    private fun assertLazyConstructor(result: QueryRunResult, fixture: NativeScopedTraceFixture) {
        val callbacks = result.relationObservations.values.flatMap { it.callbackObservations.values }
        val construction = callbacks.single()
        assertEquals(
            "scoped.InterestRatesClientImpl",
            identity(construction.target.compilerTarget.compilerEvidence.signature),
        )
        val flow = construction.flow as QueryCallbackFlowDocument.Observed
        val binding = flow.binding as QueryCallbackBindingDocument.DeferredDependency
        assertEquals("kotlin.lazy", identity(binding.target.compilerEvidence.signature))
        assertEquals(
            "scoped.InterestRatesModule.interestRatesClient",
            identity(binding.owner.compilerTarget.compilerEvidence.signature),
        )
        assertEquals(QueryCallbackInvocationScanDocument.EXHAUSTIVE, flow.scan)
        assertTrue(flow.obligations.values.isEmpty())
        assertTrue(flow.invocations.values.isEmpty())
        val start = lazyClientText.indexOf("InterestRatesClientImpl(CacheManager())")
        assertEquals(start, construction.occurrence.range.startInclusive.value)
        assertEquals(start + "InterestRatesClientImpl".length, construction.occurrence.range.endExclusive.value)
        assertTrue(fixture.observation[IntellijReadCounter.CALLBACK_DEFERRED_MODELS_ADMITTED] > 0L)
    }

    private fun lazyRequest() =
        request("InterestRatesClientImpl", true)
            .copy(steps = bounded(listOf(QueryStepDocument.Related(RelationKindDocument.CALLERS, domain))))

    private suspend fun printLazyRejection(
        protocol: io.github.amichne.kast.query.protocol.CanonicalQueryProtocol,
        fixture: NativeScopedTraceFixture,
        rejection: io.github.amichne.kast.protocol.contract.QueryRunRejection,
    ) {
        val reason = rejection as io.github.amichne.kast.protocol.contract.QueryRunRejection.CompletionUnproven
        println("Lazy tracer graph failure=${reason.callbackGraphFailure?.cause}")
        val handle =
            (reason.evidence as io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument.Retained)
                .result
        val read =
            protocol.execute(
                request =
                    QueryRunRequest.ReadResult.symbols(
                        handle,
                        output = request("InterestRatesClientImpl", true).output as QueryOutputDocument.Symbols,
                    ),
                lease = fixture.lease,
                budget = budget,
                policy = policy,
            ) as OperationOutcome.Qualified
        println("Lazy tracer failures=${read.evidence.payload.failures.values}")
        println("Lazy tracer omissions=${read.evidence.payload.omissions.values}")
        println("Lazy tracer terminations=${fixture.observation.terminations}")
    }

    private fun assertTrace(result: QueryRunResult, root: CanonicalWorkspaceRoot) {
        val actual = TraceActual.from(result)
        assertEquals(TraceOracle.symbols, actual.symbols)
        assertEquals(TraceOracle.edges, actual.edges)
        assertTrue(TraceOracle.accepts(actual))
        assertFalse(TraceOracle.accepts(actual.copy(symbols = actual.symbols - "scoped.client")))
        assertFalse(TraceOracle.accepts(actual.copy(symbols = actual.symbols + "unrelated.outside")))
        assertFalse(TraceOracle.accepts(actual.copy(edges = actual.edges.drop(1).toSet())))
        assertFalse(TraceOracle.accepts(actual.copy(completion = TraceCompletion.PARTIAL)))
        assertTrue(result.failures.values.isEmpty())
        assertTrue(result.omissions.values.isEmpty())
        val rows = result.items.values.map { it as QueryResultItemDocument.ExactSymbol }
        assertEquals(TraceOracle.symbols.size, rows.size)
        val sourceDirectory = Path.of(root.value).resolve(NativeScopedTraceFixture.SOURCE_DIRECTORY)
        assertTrue(
            rows.all { Path.of(root.value).resolve(checkNotNull(it.location).file.value).startsWith(sourceDirectory) }
        )
        assertTrue(
            rows
                .flatMap { it.connections.values }
                .all {
                    Path.of(root.value).resolve(it.occurrence.file.value) == sourceDirectory.resolve("Client.kt")
                }
        )
        result.relationObservations.values.forEach { relation ->
            assertEquals(QueryRelationCoverageDocument.Exhausted, relation.coverage)
            assertEquals(QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY, relation.effectiveDomain.sourcePolicy)
            assertEquals(QueryDiscoveryInclusionPolicyDocument.EXCLUDE, relation.effectiveDomain.libraries)
            assertEquals(directory, relation.effectiveDomain.directory)
        }
    }

    private fun assertCallback(result: QueryRunResult) {
        val callback = result.relationObservations.values.flatMap { it.callbackObservations.values }.single()
        val flow = callback.flow as QueryCallbackFlowDocument.Observed
        val binding = flow.binding as QueryCallbackBindingDocument.DependencyContract
        assertEquals(1, binding.position.value)
        assertEquals("kotlin.with", identity(binding.target.compilerEvidence.signature))
        assertEquals(flow.basis, binding.basis)
        assertEquals(QueryCallbackInvocationScanDocument.EXHAUSTIVE, flow.scan)
        assertTrue(flow.obligations.values.isEmpty())
        assertTrue(flow.invocations.values.isEmpty())
        assertTrue(binding.classDigest.value.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(binding.target.file.value.endsWith(".class"))
    }

    private fun prepare(clientText: String = NativeScopedTraceFixture.clientText): NativeScopedTraceFixture {
        lateinit var fixture: NativeScopedTraceFixture
        ApplicationManager.getApplication().invokeAndWait {
            val base = getOrCreateProjectBaseDir()
            val directories =
                WriteCommandAction.writeCommandAction(project).compute<List<VirtualFile>, RuntimeException> {
                    val src = base.createChildDirectory(this, "src")
                    listOf(
                        src.createChildDirectory(this, "backend-service-clients"),
                        src.createChildDirectory(this, "unrelated"),
                    )
                }
            configureModule(base, directories)
            val files = sourceFiles(directories, clientText)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val root = CanonicalWorkspaceRoot.fromCanonicalPath(base.toNioPath()).traceRefined()
            val model =
                WorkspaceSearchScopeModel.compile(
                    root,
                    ImportedWorkspaceModelState.COMPLETE,
                    directories.mapIndexed { index, directory ->
                        WorkspaceSourceRootBoundary(
                            ideaModuleName = module.name,
                            linkedBuildRoot = base.toNioPath(),
                            gradleProjectPath = if (index == 0) ":backend-service-clients" else ":unrelated",
                            sourceSetName = "main",
                            sourceRoot = directory.toNioPath(),
                            sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                            provenance = WorkspaceSourceRootProvenance.AUTHORED,
                        )
                    },
                ) as WorkspaceSearchScopeModelCompilation.Compiled
            fixture = NativeScopedTraceFixture(project = project, root = root, model = model.model, files = files)
        }
        return fixture
    }

    private fun configureModule(base: VirtualFile, directories: List<VirtualFile>) {
        val sdk = JavaSdk.getInstance().createJdk("scoped-trace-jdk", System.getProperty("java.home"), false)
        registerTestProjectJdk(sdk)
        ModuleRootModificationUtil.updateModel(module) { native ->
            native.sdk = sdk
            val content = native.addContentEntry(base)
            directories.forEach { content.addSourceFolder(it, false) }
            val library = native.moduleLibraryTable.createLibrary("scoped-trace-stdlib")
            val roots = library.modifiableModel
            val jar = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
            roots.addRoot("jar://$jar!/", OrderRootType.CLASSES)
            roots.commit()
        }
    }

    private fun sourceFiles(directories: List<VirtualFile>, clientText: String) =
        WriteCommandAction.writeCommandAction(project).compute<List<VirtualFile>, RuntimeException> {
            listOf(
                directories[0].createChildData(this, "CacheManager.kt").also {
                    it.setBinaryContent(NativeScopedTraceFixture.cacheText.toByteArray())
                },
                directories[0].createChildData(this, "Client.kt").also {
                    it.setBinaryContent(clientText.toByteArray())
                },
                directories[1].createChildData(this, "Outside.kt").also {
                    it.setBinaryContent(NativeScopedTraceFixture.outsideText.toByteArray())
                },
            )
        }

    private fun request(name: String, trace: Boolean) =
        QueryRunRequest.Run(
            from =
                QueryFromDocument.Symbols(
                    QueryMatchDocument.Name(text(name), SymbolDiscoveryMatchDocument.EXACT_NAME),
                    QueryScopeDocument(bounded(listOf(text("main"))), directory, null),
                    bounded(
                        listOf(if (trace) QueryDeclarationKindDocument.CLASS else QueryDeclarationKindDocument.FUNCTION)
                    ),
                ),
            steps =
                bounded(
                    listOf(
                        if (trace) QueryStepDocument.Trace(domain)
                        else QueryStepDocument.Related(RelationKindDocument.REFERENCES, domain)
                    )
                ),
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
            text(NativeScopedTraceFixture.SOURCE_DIRECTORY),
            QueryContainmentDocument.DESCENDANTS,
        )
    private val domain =
        QueryExpansionScopeDocument.Sources(
            sourceSets = bounded(listOf(text("main"))),
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

private enum class TraceCompletion {
    COMPLETE,
    PARTIAL,
}

private data class TraceEdge(
    val source: String,
    val target: String,
    val kind: RelationKindDocument,
    val start: Int,
    val end: Int,
)

private data class TraceActual(val symbols: Set<String>, val edges: Set<TraceEdge>, val completion: TraceCompletion) {
    companion object {
        fun from(result: QueryRunResult): TraceActual {
            val rows = result.items.values.map { it as QueryResultItemDocument.ExactSymbol }
            return TraceActual(
                rows.map { identity(checkNotNull(it.signature)) }.toSet(),
                rows
                    .flatMap { it.connections.values }
                    .map { relation ->
                        TraceEdge(
                            source = identity(relation.source),
                            target = identity(relation.target),
                            kind = relation.meaning,
                            start = relation.occurrence.range.startInclusive.value,
                            end = relation.occurrence.range.endExclusive.value,
                        )
                    }
                    .toSet(),
                TraceCompletion.COMPLETE,
            )
        }
    }
}

private object TraceOracle {
    val symbols =
        setOf(
            "scoped.CacheManager",
            "scoped.CacheManager.operation",
            "scoped.CacheManager.execute",
            "scoped.client",
            "scoped.entry",
        )
    private val client = NativeScopedTraceFixture.clientText
    private val cacheStart = client.indexOf("CacheManager")
    private val operationStart = client.indexOf("operation")
    private val clientStart = client.lastIndexOf("client")
    val edges =
        setOf(
            TraceEdge(
                source = "scoped.client",
                target = "scoped.CacheManager",
                kind = RelationKindDocument.REFERENCES,
                start = cacheStart,
                end = cacheStart + "CacheManager".length,
            ),
            TraceEdge(
                source = "scoped.client",
                target = "scoped.CacheManager.operation",
                kind = RelationKindDocument.REFERENCES,
                start = operationStart,
                end = operationStart + "operation".length,
            ),
            TraceEdge(
                source = "scoped.client",
                target = "scoped.CacheManager.operation",
                kind = RelationKindDocument.CALLERS,
                start = operationStart,
                end = operationStart + "operation".length,
            ),
            TraceEdge(
                source = "scoped.entry",
                target = "scoped.client",
                kind = RelationKindDocument.CALLERS,
                start = clientStart,
                end = clientStart + "client".length,
            ),
        )

    fun accepts(actual: TraceActual) =
        actual.completion == TraceCompletion.COMPLETE && actual.symbols == symbols && actual.edges == edges
}

private fun identity(symbol: SymbolDocument): String =
    when (val value = symbol.qualifiedIdentity) {
        is SymbolQualifiedIdentityDocument.Available -> value.value.value
        SymbolQualifiedIdentityDocument.Unavailable -> error("Oracle expects only named source declarations")
    }

private fun identity(signature: CompilerSignatureDocument): String =
    when (signature) {
        is CompilerSignatureDocument.Function -> signature.qualifiedIdentity.value
        is CompilerSignatureDocument.ClassLike -> signature.qualifiedIdentity.value
        is CompilerSignatureDocument.Property -> signature.qualifiedIdentity.value
        is CompilerSignatureDocument.TypeAlias -> signature.qualifiedIdentity.value
        is CompilerSignatureDocument.LocalFunction,
        is CompilerSignatureDocument.LocalProperty,
        is CompilerSignatureDocument.AnonymousObject -> error("Oracle excludes local declarations")
    }

private fun text(value: String) = ProtocolText.parse(value).traceRefined()

private fun <Value> bounded(values: List<Value>) = BoundedProtocolList.create(values).traceRefined()
