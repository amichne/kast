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
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.WorkAllowance
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

    fun testUnitCoercedCallbackDoesNotReturnDecodedValue() = runBlocking {
        val fixture = fixture("fun target(): Unit = consume { decode() }")
        val resolved = resolve(fixture, fixture.seeds.single())
        assertTrue("Legal Unit callback body must remain admitted: $resolved", resolved is ValueSiteResolution.Found)
        assertTrue(trace(fixture, (resolved as ValueSiteResolution.Found).site).isEmpty())
    }

    fun testStringCallbackReturnsDecodedValue() = checkReturns("fun target(): String = stringResult { decode() }", 1)

    fun testAnyCallbackReturnsDecodedValue() = checkReturns("fun target(): Any = anyResult { decode() }", 1)

    fun testUnitCoercionCannotBeReadmittedAsReturn() =
        checkReturnReadmission("fun target(): Unit = consume { decode() }", admitted = false)

    fun testStringReturnCanBeReadmitted() =
        checkReturnReadmission("fun target(): String = stringResult { decode() }", admitted = true)

    fun testAnyReturnCanBeReadmitted() =
        checkReturnReadmission("fun target(): Any = anyResult { decode() }", admitted = true)

    fun testRunnerControlledCallbackReturnAndReadmissionExceedLegacyTypeCap() = runBlocking {
        val deepType = "Box<".repeat(40) + "Leaf" + ">".repeat(40)
        fixture(
                "fun target(): Any = capture { decode() }",
                "class Box<T>\nclass Leaf\nfun decode(): $deepType = throw java.lang.IllegalStateException()",
            )
            .checkDeepReturnBudgets(project)
    }

    fun testNullableUnitReturnKeepsNullProvenance() = runBlocking {
        val fixture = fixture(NULLABLE_UNIT_CALLER, "fun decode(): Nothing? = null")
        val resolved = resolve(fixture, fixture.seeds.single())
        assertTrue("Nullable return producer must be admitted: $resolved", resolved is ValueSiteResolution.Found)
        val edges = trace(fixture, (resolved as ValueSiteResolution.Found).site)
        assertTrue(
            "Null result must reach the matching nullable wrapper call",
            edges.any { edge ->
                edge.kind == ValueTransferKind.SOURCE_CALL_RETURN &&
                    edge.target.enclosing.valueIdentity == fixture.ownerEndpoint().valueIdentity
            },
        )
    }

    fun testNullableUnitReturnCanBeReadmitted() =
        checkReturnReadmission(NULLABLE_UNIT_CALLER, admitted = true, "fun decode(): Nothing? = null")

    fun testNullableUnitExpressionIsCoercedWithoutReturnOrObligation() = runBlocking {
        val fixture = fixture("fun target(): Unit = consume { decode() }", "fun decode(): Unit? = null")
        val resolved = resolve(fixture, fixture.seeds.single())
        assertTrue("Unit-coerced expression must be admitted: $resolved", resolved is ValueSiteResolution.Found)
        val steps = fixture.traceSteps(project, (resolved as ValueSiteResolution.Found).site)
        assertTrue(steps.flatMap { it.transfers }.isEmpty())
        assertTrue("Coercion is proven, not an unsupported return", steps.flatMap { it.obligations }.isEmpty())
    }

    private fun checkReturnReadmission(
        caller: String,
        admitted: Boolean,
        decoderDeclaration: String = "fun decode(): String = \"decoded\"",
    ) = runBlocking {
        val fixture = fixture(caller, decoderDeclaration)
        fixture.checkReturnReadmission(project, admitted)
    }

    fun testIgnoredOuterCallbackRejectsNestedProducer() =
        checkDisconnectedProducer("fun target() = ignore { capture { decode() } }")

    fun testReturnedOuterCallbackRejectsNestedProducer() =
        checkDisconnectedProducer("fun target(): () -> String = { capture { decode() } }")

    fun testMixedInvocationOwnersDoNotHideUnsupportedResultRoute() =
        checkDisconnectedProducer(
            "fun mixed(block: () -> String): String { block(); return capture { block() } }\n" +
                "fun target(): String = mixed { decode() }"
        )

    private fun checkDisconnectedProducer(caller: String) = runBlocking {
        val fixture = fixture(caller)
        assertEquals(
            io.github.amichne.kast.relation.contract.ValueProducerSeedRead.Rejected(
                io.github.amichne.kast.relation.contract.ValueProducerSeedRejection.OWNER_MISMATCH
            ),
            producerRead(fixture),
        )
        assertTrue(resolve(fixture, fixture.seeds.single()) is ValueSiteResolution.Rejected)
    }

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

    fun testOuterCallbackCaptureRemainsAnObligation() = runBlocking {
        val fixture = fixture("fun target() = runFlow { val x = decode(); capture { x } }")
        val resolved = resolve(fixture, fixture.seeds.single())
        assertTrue(resolved is ValueSiteResolution.Found)
        val steps = fixture.traceSteps(project, (resolved as ValueSiteResolution.Found).site)
        assertTrue(
            "Unsupported outer callback capture must retain an obligation",
            steps.any { it.obligations.isNotEmpty() },
        )
        assertTrue(
            steps
                .flatMap { it.transfers }
                .none {
                    it.kind == ValueTransferKind.LOCAL_READ && it.source.executionOwner != it.target.executionOwner
                }
        )
    }

    private suspend fun trace(fixture: ReturnFixture, seed: ValueSite): List<ValueTransfer> =
        fixture.traceSteps(project, seed).flatMap { it.transfers }

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

    private suspend fun fixture(
        caller: String,
        decoderDeclaration: String = "fun decode(): String = \"decoded\"",
    ): ReturnFixture {
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
                            "fun consume(block: () -> Unit): Unit = block()\n" +
                            "fun stringResult(block: () -> String): String = block()\n" +
                            "fun anyResult(block: () -> Any): Any = block()\n" +
                            "fun nullableResult(block: () -> Unit?): Unit? = block()\n" +
                            "fun ignore(block: () -> String): String = \"constant\"\n" +
                            "fun discard(block: () -> String): String { block(); return \"constant\" }\n" +
                            "object Attempt { data class Value(val value: String) }\n" +
                            "$decoderDeclaration\n$caller\n")
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
        val workspaceRoot = base.request.subject.lease.workspaceRoot
        val projection = IntellijK2RelationProjection(project, workspaceRoot, workAllowance = WorkAllowance.Unlimited)
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
private const val NULLABLE_UNIT_CALLER =
    "fun target(flag: Boolean): Unit? = nullableResult { if (flag) return@nullableResult Unit; decode() }"

private suspend fun ReturnFixture.checkDeepReturnBudgets(project: com.intellij.openapi.project.Project) {
    val runner =
        copy(
            domain =
                RelationRequest.start(
                    selector = owner,
                    meaning = RelationMeaning.References,
                    budget = domain.budget.copy(resources = ResourceBudget.RunnerControlled),
                    boundary = RelationSearchBoundary.WORKSPACE_EXPANSION,
                )
        )
    val resolved =
        IntellijValueFlowCompilerAdapter()
            .resolve(
                project = project,
                current = owner.lease,
                enclosing = owner,
                anchor = seeds.single(),
                domain = runner.domain,
                model = model,
            )
    org.junit.Assert.assertTrue("Expected deep producer admission: $resolved", resolved is ValueSiteResolution.Found)
    val source = (resolved as ValueSiteResolution.Found).site
    checkDeepReturn(project, source, unlimited = false)
    runner.checkDeepReturn(project, source, unlimited = true)
}

private suspend fun ReturnFixture.checkDeepReturn(
    project: com.intellij.openapi.project.Project,
    source: ValueSite,
    unlimited: Boolean,
) {
    val read =
        IntellijValueFlowCompilerAdapter()
            .read(
                project = project,
                current = owner.lease,
                request = ValueFlowRequest(source, domain.budget, RelationSearchBoundary.WORKSPACE_EXPANSION),
                model = model,
            )
    org.junit.Assert.assertTrue("Expected deep callback observation: $read", read is ValueFlowRead.Observed)
    val step = (read as ValueFlowRead.Observed).step
    val revalidated = revalidateReturn(project)
    if (unlimited) {
        org.junit.Assert.assertTrue(step.obligations.isEmpty())
        org.junit.Assert.assertEquals(ValueTransferKind.RETURN, step.transfers.single().kind)
        org.junit.Assert.assertTrue(
            "Expected unlimited return readmission: $revalidated",
            revalidated is io.github.amichne.kast.relation.contract.ValueModelSiteRead.Revalidated,
        )
    } else {
        org.junit.Assert.assertTrue(step.transfers.isEmpty())
        org.junit.Assert.assertEquals(
            listOf(io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause.WORK_LIMIT_REACHED),
            step.obligations.map { it.cause },
        )
        org.junit.Assert.assertEquals(
            io.github.amichne.kast.relation.contract.ValueModelSiteRead.Unsupported(
                io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause.WORK_LIMIT_REACHED
            ),
            revalidated,
        )
    }
}

private suspend fun ReturnFixture.checkReturnReadmission(
    project: com.intellij.openapi.project.Project,
    admitted: Boolean,
) {
    val result = revalidateReturn(project)
    if (admitted)
        org.junit.Assert.assertTrue(
            "Compiler-proven return must be readmitted: $result",
            result is io.github.amichne.kast.relation.contract.ValueModelSiteRead.Revalidated,
        )
    else
        org.junit.Assert.assertEquals(
            io.github.amichne.kast.relation.contract.ValueModelSiteRead.Unsupported(
                io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION
            ),
            result,
        )
}

private suspend fun ReturnFixture.revalidateReturn(
    project: com.intellij.openapi.project.Project
): io.github.amichne.kast.relation.contract.ValueModelSiteRead {
    val request =
        io.github.amichne.kast.relation.contract.ValueSiteRevalidationRequest.create(
                enclosing = owner,
                anchor = seeds.single(),
                role = io.github.amichne.kast.relation.contract.ValueSiteRoleClaim.Return,
                budget = domain.budget,
            )
            .nativeRefined()
    return IntellijValueFlowCompilerAdapter()
        .revalidateSite(project = project, current = owner.lease, request = request, model = model)
}

private suspend fun ReturnFixture.traceSteps(
    project: com.intellij.openapi.project.Project,
    seed: ValueSite,
): List<io.github.amichne.kast.relation.contract.ValueFlowStep> {
    val pending = ArrayDeque<ValueSite>().apply { add(seed) }
    val visited = mutableSetOf<io.github.amichne.kast.relation.contract.ValueSiteIdentity>()
    val steps = mutableListOf<io.github.amichne.kast.relation.contract.ValueFlowStep>()
    while (pending.isNotEmpty()) {
        val site = pending.removeFirst()
        if (!visited.add(site.identity)) continue
        org.junit.Assert.assertTrue("Native route must stay finite", visited.size < MAX_NATIVE_ROUTE_SITES)
        val result =
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = owner.lease,
                    request = ValueFlowRequest(site, domain.budget, RelationSearchBoundary.WORKSPACE_EXPANSION),
                    model = model,
                )
        org.junit.Assert.assertTrue(
            "Expected native observation at ${site.range}: $result",
            result is ValueFlowRead.Observed,
        )
        val step = (result as ValueFlowRead.Observed).step
        steps.add(step)
        step.transfers.forEach { pending.add(it.target) }
    }
    return steps
}

private fun assertNestedRoute(edges: List<ValueTransfer>) {
    org.junit.Assert.assertEquals(
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
    org.junit.Assert.assertEquals("value", field.field.declaration.name.value)
    val selected = edges.single { it.kind == ValueTransferKind.FIELD_READ }
    org.junit.Assert.assertEquals(construction.target.valuePath, selected.source.valuePath)
    org.junit.Assert.assertTrue(selected.target.valuePath.segments.isEmpty())
    val branch = edges.single { it.kind == ValueTransferKind.BRANCH_ALTERNATIVE }
    val proof = branch.evidence as io.github.amichne.kast.relation.contract.ValueTransferEvidence.NormalBranchResult
    org.junit.Assert.assertEquals(
        io.github.amichne.kast.relation.contract.ValueTryBranchAlternative.TryBody,
        proof.alternative,
    )
    org.junit.Assert.assertEquals(branch.target.range, proof.tryRange)
    org.junit.Assert.assertEquals(construction.target.valuePath, branch.target.valuePath)
    edges
        .filter { it.kind == ValueTransferKind.SOURCE_CALL_RETURN }
        .forEach { edge ->
            org.junit.Assert.assertEquals(edge.source.valuePath, edge.target.valuePath)
        }
}

private data class ReturnFixture(
    val owner: SymbolSelector,
    val domain: RelationRequest,
    val model: WorkspaceSearchScopeModelCompilation,
    val seeds: List<io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange>,
    val decoder: SymbolSelector,
) {
    fun ownerEndpoint() = io.github.amichne.kast.relation.contract.RelationEndpoint.subject(owner)
}
