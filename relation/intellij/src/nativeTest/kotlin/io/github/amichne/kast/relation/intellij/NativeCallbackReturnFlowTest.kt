package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Starts at the decode result, never at an argument whose dependence would require a model. */
class NativeCallbackReturnFlowTest : NativeReferenceFixtureTest() {
    override val fixtureSdkName = "callback-return-flow-jdk"

    override fun runInDispatchThread() = false

    fun testDirectCallbackResultReturnsToItsExactSupplier() = checkReturns("fun target() = capture { decode() }", 1)

    fun testDistinctSuppliersNeverCrossReturn() =
        checkReturns("fun target() { val first = capture { decode() }; val second = capture { decode() } }", 2)

    fun testDiscardedCallbackResultDoesNotReachConstantWrapperReturn() =
        checkReturns("fun target() = discard { decode() }", 1, reachesCaller = false)

    fun testReturnedUninvokedCallbackRemainsRejected() = runBlocking {
        val fixture = fixture("fun target(): () -> String = { decode() }")
        assertTrue(resolve(fixture, fixture.seeds.single()) is ValueSiteResolution.Rejected)
        assertEquals(
            io.github.amichne.kast.relation.contract.ValueProducerSeedRead.Rejected(
                io.github.amichne.kast.relation.contract.ValueProducerSeedRejection.OWNER_MISMATCH
            ),
            producerRead(fixture),
        )
    }

    fun testPublicProducerSeedsTheExactInvokedDecodeResult() = runBlocking {
        val fixture = fixture("fun target() = runFlow { capture { decode() } }")
        val result = producerRead(fixture)
        assertTrue(
            "Exact public producer must retain admitted callback execution: $result",
            result is io.github.amichne.kast.relation.contract.ValueProducerSeedRead.Seeded,
        )
        val seed = (result as io.github.amichne.kast.relation.contract.ValueProducerSeedRead.Seeded).seed
        assertEquals(fixture.seeds.single(), seed.site.range)
        assertEquals(seed.site.executionOwner, seed.invocation.executionOwner)
        assertTrue(
            seed.site.executionOwner.context is io.github.amichne.kast.relation.contract.ValueExecutionContext.Callback
        )
        assertEquals(
            EXPECTED_NESTED_RETURN_TRANSFERS,
            trace(fixture, seed.site).count { it.kind == ValueTransferKind.SOURCE_CALL_RETURN },
        )
    }

    private suspend fun producerRead(
        fixture: ReturnFixture
    ): io.github.amichne.kast.relation.contract.ValueProducerSeedRead {
        val request =
            io.github.amichne.kast.relation.contract.ValueProducerSeedRequest.create(
                    enclosing = fixture.owner,
                    anchor = fixture.seeds.single(),
                    expectedCallable = fixture.decoder,
                    budget = fixture.domain.budget,
                    boundary = RelationSearchBoundary.WORKSPACE_EXPANSION,
                )
                .nativeRefined()
        return IntellijValueFlowCompilerAdapter()
            .seed(project = project, current = fixture.owner.lease, request = request, model = fixture.model)
    }

    fun testNestedCallbackNormalTryAndFieldResultReachOuterReturn() =
        checkReturns(
            "fun target() = runFlow { val attempt = capture { " +
                "try { Attempt.Value(decode()) } catch (e: Exception) { Attempt.Value(\"fallback\") } " +
                "}; attempt.value }",
            1,
            nested = true,
        )

    private fun checkReturns(caller: String, count: Int, reachesCaller: Boolean = true, nested: Boolean = false) =
        runBlocking {
            val fixture = fixture(caller)
            assertEquals(count, fixture.seeds.size)
            val arrivals =
                fixture.seeds.map { seed ->
                    val resolved = resolve(fixture, seed)
                    assertTrue(
                        "Exact invoked anonymous seed must be admitted: $resolved",
                        resolved is ValueSiteResolution.Found,
                    )
                    val edges = trace(fixture, (resolved as ValueSiteResolution.Found).site)
                    val returned = edges.filter { edge ->
                        edge.kind == ValueTransferKind.SOURCE_CALL_RETURN &&
                            edge.target.enclosing.valueIdentity == fixture.ownerEndpoint().valueIdentity
                    }
                    if (reachesCaller)
                        assertTrue("Missing matched callback return: ${edges.map { it.kind }}", returned.isNotEmpty())
                    else assertTrue("Discarded input must not reach wrapper's constant result", returned.isEmpty())
                    if (nested) assertNestedRoute(edges)
                    returned.map { it.target.range }.toSet()
                }
            if (count == 2)
                assertTrue(
                    "Suppliers must not share a return destination",
                    arrivals[0].intersect(arrivals[1]).isEmpty(),
                )
        }

    private suspend fun trace(fixture: ReturnFixture, seed: ValueSite): List<ValueTransfer> {
        val pending = ArrayDeque<ValueSite>().apply { add(seed) }
        val visited = mutableSetOf<io.github.amichne.kast.relation.contract.ValueSiteIdentity>()
        val edges = mutableListOf<ValueTransfer>()
        while (pending.isNotEmpty()) {
            val site = pending.removeFirst()
            if (!visited.add(site.identity)) continue
            assertTrue("Native route must stay finite", visited.size < MAX_NATIVE_ROUTE_SITES)
            val result =
                IntellijValueFlowCompilerAdapter()
                    .read(
                        project = project,
                        current = fixture.owner.lease,
                        request =
                            ValueFlowRequest(site, fixture.domain.budget, RelationSearchBoundary.WORKSPACE_EXPANSION),
                        model = fixture.model,
                    )
            assertTrue("Expected native observation at ${site.range}: $result", result is ValueFlowRead.Observed)
            val step = (result as ValueFlowRead.Observed).step
            edges.addAll(step.transfers)
            step.transfers.forEach { pending.add(it.target) }
        }
        return edges
    }

    private fun assertNestedRoute(edges: List<ValueTransfer>) {
        assertEquals(
            listOf(
                ValueTransferKind.ARGUMENT,
                ValueTransferKind.CONSTRUCTOR_FIELD,
                ValueTransferKind.BRANCH_ALTERNATIVE,
                ValueTransferKind.RETURN,
                ValueTransferKind.SOURCE_CALL_RETURN,
                ValueTransferKind.RETURN,
                ValueTransferKind.SOURCE_CALL_RETURN,
                ValueTransferKind.LOCAL_BINDING,
                ValueTransferKind.LOCAL_READ,
                ValueTransferKind.FIELD_READ,
                ValueTransferKind.RETURN,
                ValueTransferKind.SOURCE_CALL_RETURN,
                ValueTransferKind.RETURN,
                ValueTransferKind.SOURCE_CALL_RETURN,
                ValueTransferKind.RETURN,
            ),
            edges.map { it.kind },
        )
        val construction = edges.single { it.kind == ValueTransferKind.CONSTRUCTOR_FIELD }
        val field =
            construction.target.valuePath.segments.single()
                as io.github.amichne.kast.relation.contract.ValuePathSegment.Field
        assertEquals("value", field.field.declaration.name.value)
        val selected = edges.single { it.kind == ValueTransferKind.FIELD_READ }
        assertEquals(construction.target.valuePath, selected.source.valuePath)
        assertTrue(selected.target.valuePath.segments.isEmpty())
        val branch = edges.single { it.kind == ValueTransferKind.BRANCH_ALTERNATIVE }
        val proof = branch.evidence as io.github.amichne.kast.relation.contract.ValueTransferEvidence.NormalBranchResult
        assertEquals(io.github.amichne.kast.relation.contract.ValueTryBranchAlternative.TryBody, proof.alternative)
        assertEquals(branch.target.range, proof.tryRange)
        assertEquals(construction.target.valuePath, branch.target.valuePath)
        edges
            .filter { it.kind == ValueTransferKind.SOURCE_CALL_RETURN }
            .forEach { edge ->
                assertEquals(edge.source.valuePath, edge.target.valuePath)
            }
    }

    private suspend fun resolve(
        fixture: ReturnFixture,
        seed: io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange,
    ) =
        IntellijValueFlowCompilerAdapter()
            .resolve(
                project = project,
                current = fixture.owner.lease,
                enclosing = fixture.owner,
                anchor = seed,
                domain = fixture.domain,
                model = fixture.model,
            )

    private suspend fun fixture(caller: String): ReturnFixture {
        lateinit var base: NativeReferenceFixture
        ApplicationManager.getApplication().invokeAndWait {
            base = prepareReferenceFixture()
            ModuleRootModificationUtil.updateModel(module) { model ->
                val library = model.moduleLibraryTable.createLibrary("callback-return-stdlib")
                val roots = library.modifiableModel
                val jar = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
                roots.addRoot("jar://$jar!/", OrderRootType.CLASSES)
                roots.commit()
            }
            WriteCommandAction.runWriteCommandAction(project) {
                base.callerFile.setBinaryContent(
                    ("package consumer\nclass FlowScope\n" +
                            "fun <T> runFlow(block: FlowScope.() -> T): T = FlowScope().block()\n" +
                            "fun <T> capture(block: () -> T): T = block()\n" +
                            "fun discard(block: () -> String): String { block(); return \"constant\" }\n" +
                            "object Attempt { data class Value(val value: String) }\n" +
                            "fun decode(): String = \"decoded\"\n$caller\n")
                        .toByteArray()
                )
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        return readAction { describeFixture(base) }
    }

    private fun describeFixture(base: NativeReferenceFixture): ReturnFixture {
        val file = psiManager.findFile(base.callerFile) as KtFile
        val owner = file.declarations.filterIsInstance<KtNamedFunction>().single { it.name == "target" }
        val projection = IntellijK2RelationProjection(project, base.request.subject.lease.workspaceRoot)
        val evidence = (projection.project(owner) as IntellijRelationDeclarationProjection.Projected).evidence
        val budget =
            io.github.amichne.kast.relation.contract.RelationBudget(
                base.request.budget.resources as io.github.amichne.kast.kernel.ResourceBudget.Bounded,
                io.github.amichne.kast.relation.contract.RelationByteLimit.parse(8_000_000L).nativeRefined(),
            )
        val domain =
            reissuedNativeRelationRequest(
                request = base.request,
                evidence = evidence,
                meaning = RelationMeaning.References,
                budget = budget,
            )
        val root = base.callerFile.parent
        val model =
            WorkspaceSearchScopeModel.compile(
                domain.subject.lease.workspaceRoot,
                ImportedWorkspaceModelState.COMPLETE,
                listOf(
                    WorkspaceSourceRootBoundary(
                        ideaModuleName = module.name,
                        linkedBuildRoot = project.basePath.let { Path.of(checkNotNull(it)) },
                        gradleProjectPath = ":main",
                        sourceSetName = "main",
                        sourceRoot = root.toNioPath(),
                        sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                        provenance = WorkspaceSourceRootProvenance.AUTHORED,
                    )
                ),
            )
        val seeds =
            PsiTreeUtil.collectElementsOfType(owner, KtCallExpression::class.java)
                .filter { it.calleeExpression?.text == "decode" }
                .map(::sourceValueRange)
        return ReturnFixture(
            owner = (domain.subject as io.github.amichne.kast.relation.contract.RelationEndpoint.Subject).selector,
            domain = domain,
            model = model,
            seeds = seeds,
            decoder = decoderSelector(base, file, projection),
        )
    }

    private fun decoderSelector(
        base: NativeReferenceFixture,
        file: KtFile,
        projection: IntellijK2RelationProjection,
    ): SymbolSelector {
        val decoder = file.declarations.filterIsInstance<KtNamedFunction>().single { it.name == "decode" }
        val evidence = (projection.project(decoder) as IntellijRelationDeclarationProjection.Projected).evidence
        return SymbolSelector.issue(base.request.subject.lease, base.request.searchScope, evidence)
    }
}

private const val MAX_NATIVE_ROUTE_SITES = 80
private const val EXPECTED_NESTED_RETURN_TRANSFERS = 4

private data class ReturnFixture(
    val owner: SymbolSelector,
    val domain: RelationRequest,
    val model: WorkspaceSearchScopeModelCompilation,
    val seeds: List<io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange>,
    val decoder: SymbolSelector,
) {
    fun ownerEndpoint() = io.github.amichne.kast.relation.contract.RelationEndpoint.subject(owner)
}
