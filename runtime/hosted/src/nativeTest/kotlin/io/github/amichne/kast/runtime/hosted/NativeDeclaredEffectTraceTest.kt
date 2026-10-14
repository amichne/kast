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
import io.github.amichne.kast.protocol.contract.ImpactSemanticBasisDocument
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.QueryCallbackBindingDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackBodyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackInvocationScanDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackNamedPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryContainmentDocument
import io.github.amichne.kast.protocol.contract.QueryDirectoryScopeDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoveryInclusionPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourcePolicyDocument
import io.github.amichne.kast.protocol.contract.QueryDiscoverySourceSetsDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionBudgetDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionDocument
import io.github.amichne.kast.protocol.contract.QueryExecutionKindDocument
import io.github.amichne.kast.protocol.contract.QueryExpansionScopeDocument
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.protocol.contract.QueryMatchDocument
import io.github.amichne.kast.protocol.contract.QueryOutputDocument
import io.github.amichne.kast.protocol.contract.QueryRelationCoverageDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
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

/** R1 uses the native adapter, canonical query, retained observations, and public projection. */
class NativeDeclaredEffectTraceTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testExactlyOnceLambdaFunction() = assertCase(DeclaredTraceCase.exactlyLambdaFunction)

    fun testExactlyOnceLambdaConstructor() = assertCase(DeclaredTraceCase.exactlyLambdaConstructor)

    fun testExactlyOnceAnonymousFunction() = assertCase(DeclaredTraceCase.exactlyAnonymousFunction)

    fun testExactlyOnceAnonymousConstructor() = assertCase(DeclaredTraceCase.exactlyAnonymousConstructor)

    fun testAtMostOnceLambdaFunction() = assertCase(DeclaredTraceCase.atMostLambdaFunction)

    fun testAtMostOnceLambdaConstructor() = assertCase(DeclaredTraceCase.atMostLambdaConstructor)

    fun testAtMostOnceAnonymousFunction() = assertCase(DeclaredTraceCase.atMostAnonymousFunction)

    fun testAtMostOnceAnonymousConstructor() = assertCase(DeclaredTraceCase.atMostAnonymousConstructor)

    fun testAtLeastOnceLambdaFunction() = assertCase(DeclaredTraceCase.atLeastLambdaFunction)

    fun testAtLeastOnceLambdaConstructor() = assertCase(DeclaredTraceCase.atLeastLambdaConstructor)

    fun testAtLeastOnceAnonymousFunction() = assertCase(DeclaredTraceCase.atLeastAnonymousFunction)

    fun testAtLeastOnceAnonymousConstructor() = assertCase(DeclaredTraceCase.atLeastAnonymousConstructor)

    fun testUnknownLambdaFunction() = assertCase(DeclaredTraceCase.unknownLambdaFunction)

    fun testUnknownLambdaConstructor() = assertCase(DeclaredTraceCase.unknownLambdaConstructor)

    fun testUnknownAnonymousFunction() = assertCase(DeclaredTraceCase.unknownAnonymousFunction)

    fun testUnknownAnonymousConstructor() = assertCase(DeclaredTraceCase.unknownAnonymousConstructor)

    private fun assertCase(case: DeclaredTraceCase) = runBlocking {
        DeclaredEffectArchive.compile().use { archive ->
            val fixture = prepare(case, archive)
            val started = System.nanoTime()
            val outcome =
                fixture
                    .protocol()
                    .execute(
                        request = request(case),
                        lease = fixture.lease,
                        budget = budget,
                        policy = policy,
                    )
            val projected = CanonicalQueryCliDocuments.project(outcome)
            val elapsedNanos = System.nanoTime() - started
            assertTrue("Expected Complete for ${case.id}, received $outcome", outcome is OperationOutcome.Complete)
            val result = (outcome as OperationOutcome.Complete).evidence.payload
            assertResult(case, archive.classDigest, result)
            assertInputIdentity(fixture, archive, result)
            val encoded = (projected as ProjectedOperationOutcome.Complete).document.value
            assertTrue(encoded.contains("\"invocation_kind\":\"${case.effect}\""))
            assertTrue(encoded.contains("\"class_digest\":\"${archive.classDigest}\""))
            println(
                "R1 public case=${case.id} elapsedNanos=$elapsedNanos " +
                    "fixture=cold repetitions=1 encodedBytes=${encoded.toByteArray().size}"
            )
            println("R1 public case=${case.id} counters=${fixture.observation.snapshot()}")
        }
    }

    private fun assertResult(case: DeclaredTraceCase, classDigest: String, result: QueryRunResult) {
        assertTrue(result.items.values.isEmpty())
        assertTrue(result.failures.values.isEmpty())
        assertTrue(result.omissions.values.isEmpty())
        val callbacks = result.relationObservations.values.flatMap { it.callbackObservations.values }
        assertEquals("An empty named result still needs its exact callback relationship", 1, callbacks.size)
        val callback = callbacks.single()
        val flow = callback.flow as QueryCallbackFlowDocument.Observed
        val binding = flow.binding as QueryCallbackBindingDocument.DependencyContract
        val supplier = binding.owner as QueryCallbackBodyDocument.Named
        assertEquals(case.target, declaredIdentity(callback.target.compilerTarget.compilerEvidence.signature))
        assertEquals(
            "consumer.${case.owner}",
            declaredIdentity(callback.lexicalOwner.compilerTarget.compilerEvidence.signature),
        )
        assertEquals(
            "consumer.${case.owner}",
            declaredIdentity(supplier.callable.compilerTarget.compilerEvidence.signature),
        )
        assertEquals("effects.${case.api}", declaredIdentity(binding.target.compilerEvidence.signature))
        assertOverload(binding)
        assertEquals(case.effect, binding.invocationKind.name)
        assertEquals(1, binding.position.value)
        assertEquals(classDigest, binding.classDigest.value)
        assertEquals("KOTLIN_BINARY_CONTRACT", binding.provenance.name)
        assertEquals(flow.basis, binding.basis)
        assertEquals(QueryCallbackInvocationScanDocument.EXHAUSTIVE, flow.scan)
        assertTrue(flow.obligations.values.isEmpty())
        assertTrue(flow.invocations.values.isEmpty())
        assertEquals(RelationKindDocument.CALLERS, callback.relation)
        val named = callback.namedPolicy as QueryCallbackNamedPolicyDocument.Excluded
        assertEquals("NON_INLINE_ARGUMENT", named.reason.name)
        assertOracle(case, classDigest, callback)
        assertLocations(case, callback)
        result.relationObservations.values.forEach { relation ->
            assertEquals(QueryRelationCoverageDocument.Exhausted, relation.coverage)
            val sourceSets = relation.effectiveDomain.sourceSets as QueryDiscoverySourceSetsDocument.Exact
            assertEquals(listOf("main"), sourceSets.names.values.map { it.value })
            assertEquals(directory, relation.effectiveDomain.directory)
            assertEquals(QueryDiscoverySourcePolicyDocument.PRODUCTION_ONLY, relation.effectiveDomain.sourcePolicy)
            assertEquals(QueryDiscoveryInclusionPolicyDocument.EXCLUDE, relation.effectiveDomain.libraries)
        }
    }

    private fun assertOverload(binding: QueryCallbackBindingDocument.DependencyContract) {
        val signature = binding.target.compilerEvidence.signature as CompilerSignatureDocument.Function
        assertEquals(2, signature.valueParameters.values.size)
        assertEquals("kotlin/Int", signature.valueParameters.values.first().value)
    }

    private fun assertInputIdentity(
        fixture: NativeScopedTraceFixture,
        archive: DeclaredEffectArchive,
        result: QueryRunResult,
    ) {
        val callback = result.relationObservations.values.flatMap { it.callbackObservations.values }.single()
        val flow = callback.flow as QueryCallbackFlowDocument.Observed
        val basis = flow.basis as ImpactSemanticBasisDocument.Published
        assertEquals(fixture.lease.workspaceRoot.value, basis.root.value)
        assertEquals(fixture.lease.generation.value, basis.generation.value)
        val root = Path.of(fixture.lease.workspaceRoot.value)
        val caller = root.resolve(NativeScopedTraceFixture.SOURCE_DIRECTORY).resolve("Caller.kt")
        val targets = root.resolve(NativeScopedTraceFixture.SOURCE_DIRECTORY).resolve("Targets.kt")
        assertEquals(caller, Path.of(callback.occurrence.file.value))
        assertEquals(caller, Path.of(callback.callbackBody.file.value))
        assertEquals(caller, Path.of(callback.lexicalOwner.declaration.file.value))
        assertEquals(targets, Path.of(callback.target.declaration.file.value))
        val binding = flow.binding as QueryCallbackBindingDocument.DependencyContract
        assertEquals(caller, Path.of(binding.occurrence.file.value))
        val supplier = binding.owner as QueryCallbackBodyDocument.Named
        assertEquals(caller, Path.of(supplier.callable.declaration.file.value))
        assertEquals("jar://${archive.jar}!/effects/DependencyEffectsKt.class", binding.target.file.value)
    }

    private fun assertOracle(
        case: DeclaredTraceCase,
        classDigest: String,
        callback: io.github.amichne.kast.protocol.contract.QueryCallbackObservationDocument,
    ) {
        val flow = callback.flow as QueryCallbackFlowDocument.Observed
        val binding = flow.binding as QueryCallbackBindingDocument.DependencyContract
        val supplier = binding.owner as QueryCallbackBodyDocument.Named
        val actual =
            DeclaredTraceActual(
                DeclaredTraceCompletion.COMPLETE,
                emptySet(),
                setOf(
                    DeclaredTraceEdge.Contract(
                        supplier = declaredIdentity(supplier.callable.compilerTarget.compilerEvidence.signature),
                        target = declaredIdentity(binding.target.compilerEvidence.signature),
                        start = binding.occurrence.range.startInclusive.value,
                        end = binding.occurrence.range.endExclusive.value,
                        effect = binding.invocationKind.name,
                        formal = binding.position.value,
                        classDigest = binding.classDigest.value,
                    ),
                    DeclaredTraceEdge.Body(
                        supplier = declaredIdentity(callback.lexicalOwner.compilerTarget.compilerEvidence.signature),
                        target = declaredIdentity(callback.target.compilerTarget.compilerEvidence.signature),
                        start = callback.occurrence.range.startInclusive.value,
                        end = callback.occurrence.range.endExclusive.value,
                    ),
                ),
            )
        assertTrue("R1 independent edge oracle rejected ${case.id}: $actual", case.accepts(actual, classDigest))
    }

    private fun assertLocations(
        case: DeclaredTraceCase,
        callback: io.github.amichne.kast.protocol.contract.QueryCallbackObservationDocument,
    ) {
        val flow = callback.flow as QueryCallbackFlowDocument.Observed
        val binding = flow.binding as QueryCallbackBindingDocument.DependencyContract
        assertEquals(case.targetStart, callback.occurrence.range.startInclusive.value)
        assertEquals(case.targetEnd, callback.occurrence.range.endExclusive.value)
        assertEquals(case.bodyStart, callback.callbackBody.range.startInclusive.value)
        assertEquals(case.bodyEnd, callback.callbackBody.range.endExclusive.value)
        assertEquals(callback.callbackBody, flow.body.occurrence)
        assertEquals(case.supplyStart, binding.occurrence.range.startInclusive.value)
        assertEquals(case.supplyEnd, binding.occurrence.range.endExclusive.value)
    }

    private fun prepare(case: DeclaredTraceCase, archive: DeclaredEffectArchive): NativeScopedTraceFixture {
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

    private fun sourceFiles(directory: VirtualFile, case: DeclaredTraceCase): List<VirtualFile> =
        WriteCommandAction.writeCommandAction(project).compute<List<VirtualFile>, RuntimeException> {
            listOf(
                directory.createChildData(this, "Targets.kt").also {
                    it.setBinaryContent(DeclaredEffectArchive.resource("Targets.kt"))
                },
                directory.createChildData(this, "Caller.kt").also { it.setBinaryContent(case.source.toByteArray()) },
            )
        }

    private fun request(case: DeclaredTraceCase) =
        QueryRunRequest.Run(
            from =
                QueryFromDocument.Symbols(
                    QueryMatchDocument.Name(
                        declaredText(case.target.substringAfterLast('.')),
                        SymbolDiscoveryMatchDocument.EXACT_NAME,
                    ),
                    QueryScopeDocument(declaredBounded(listOf(declaredText("main"))), directory, null),
                    declaredBounded(listOf(case.declarationKind)),
                ),
            steps = declaredBounded(listOf(QueryStepDocument.Related(RelationKindDocument.CALLERS, domain))),
            output =
                QueryOutputDocument.Symbols(
                    declaredBounded(
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
            declaredText(NativeScopedTraceFixture.SOURCE_DIRECTORY),
            QueryContainmentDocument.DESCENDANTS,
        )
    private val domain =
        QueryExpansionScopeDocument.Sources(
            sourceSets = declaredBounded(listOf(declaredText("main"))),
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

internal fun declaredIdentity(signature: CompilerSignatureDocument): String =
    when (signature) {
        is CompilerSignatureDocument.Function -> signature.qualifiedIdentity.value
        is CompilerSignatureDocument.ClassLike -> signature.qualifiedIdentity.value
        is CompilerSignatureDocument.Property -> signature.qualifiedIdentity.value
        is CompilerSignatureDocument.TypeAlias -> signature.qualifiedIdentity.value
        is CompilerSignatureDocument.LocalFunction,
        is CompilerSignatureDocument.LocalProperty,
        is CompilerSignatureDocument.AnonymousObject -> error("R1 oracle admits named source declarations")
    }

private fun declaredText(value: String) = ProtocolText.parse(value).traceRefined()

private fun <Value> declaredBounded(values: List<Value>) = BoundedProtocolList.create(values).traceRefined()
