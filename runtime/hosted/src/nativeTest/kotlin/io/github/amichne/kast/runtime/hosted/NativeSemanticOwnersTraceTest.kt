package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.JavaModuleType
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryCallbackFlowCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphCauseDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphPolicyDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackGraphUnprovenExclusionReasonDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackNavigationDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackNavigationEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryCompletionEvidenceDocument
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.contract.RelationKindDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/** Fixed source oracles exercise real compiler ports, query completion, and public projection. */
class NativeSemanticOwnersTraceTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testNamedFunctionFunction() = assertDeferred("R2_named_function_function")

    fun testNamedFunctionConstructor() = assertDeferred("R2_named_function_constructor")

    fun testLocalFunctionFunction() = assertDeferred("R2_local_function_function")

    fun testLocalFunctionConstructor() = assertDeferred("R2_local_function_constructor")

    fun testTopPropertyFunction() = assertDeferred("R2_top_property_function")

    fun testTopPropertyConstructor() = assertDeferred("R2_top_property_constructor")

    fun testMemberPropertyFunction() = assertDeferred("R2_member_property_function")

    fun testMemberPropertyConstructor() = assertDeferred("R2_member_property_constructor")

    fun testLocalPropertyFunction() = assertDeferred("R2_local_property_function")

    fun testLocalPropertyConstructor() = assertDeferred("R2_local_property_constructor")

    fun testGetterFunction() = assertDeferred("R2_getter_function")

    fun testGetterConstructor() = assertDeferred("R2_getter_constructor")

    fun testSetterFunction() = assertDeferred("R2_setter_function")

    fun testSetterConstructor() = assertDeferred("R2_setter_constructor")

    fun testConstructorBodyFunction() = assertDeferred("R2_constructor_body_function")

    fun testConstructorBodyConstructor() = assertDeferred("R2_constructor_body_constructor")

    fun testInitBlockFunction() = assertDeferred("R2_init_block_function")

    fun testInitBlockConstructor() = assertDeferred("R2_init_block_constructor")

    fun testDirectLambdaFunction() = assertDeferred("R2_direct_lambda_function")

    fun testDirectLambdaConstructor() = assertDeferred("R2_direct_lambda_constructor")

    fun testDirectAnonymousFunction() = assertDeferred("R2_direct_anonymous_function")

    fun testDirectAnonymousConstructor() = assertDeferred("R2_direct_anonymous_constructor")

    fun testDepthTwoLambdaFunction() = assertDeferred("R2_depth_two_lambda_function")

    fun testDepthTwoLambdaConstructor() = assertDeferred("R2_depth_two_lambda_constructor")

    fun testDepthTwoAnonymousFunction() = assertDeferred("R2_depth_two_anonymous_function")

    fun testDepthTwoAnonymousConstructor() = assertDeferred("R2_depth_two_anonymous_constructor")

    fun testBareConstructorReferenceHasNoCallerInvocation() = runBlocking {
        val source = resource("semantic-owners/R4_bare_constructor_reference.kt")
        val fixture = prepare(source)
        val outcome =
            fixture
                .protocol()
                .execute(
                    request = NativeSemanticOwnersRequests.request(NativeSemanticOwnersTargetShape.CONSTRUCTOR),
                    lease = fixture.lease,
                    budget = NativeSemanticOwnersRequests.budget,
                    policy = NativeSemanticOwnersRequests.policy,
                )
        assertTrue("Expected complete bare-reference CALLERS, received $outcome", outcome is OperationOutcome.Complete)
        val result = (outcome as OperationOutcome.Complete).evidence.payload
        assertTrue(result.items.values.isEmpty())
        assertTrue(result.relationObservations.values.flatMap { it.callbackObservations.values }.isEmpty())
        assertTrue(result.failures.values.isEmpty())
        assertTrue(result.omissions.values.isEmpty())
    }

    fun testBareConstructorReferenceKeepsTheReferenceOccurrence() = runBlocking {
        val source = resource("semantic-owners/R4_bare_constructor_reference.kt")
        val fixture = prepare(source)
        val outcome =
            fixture
                .protocol()
                .execute(
                    request =
                        NativeSemanticOwnersRequests.request(
                            NativeSemanticOwnersTargetShape.CONSTRUCTOR,
                            RelationKindDocument.REFERENCES,
                        ),
                    lease = fixture.lease,
                    budget = NativeSemanticOwnersRequests.budget,
                    policy = NativeSemanticOwnersRequests.policy,
                )
        assertTrue(
            "Expected complete bare-reference REFERENCES, received $outcome",
            outcome is OperationOutcome.Complete,
        )
        val result = (outcome as OperationOutcome.Complete).evidence.payload
        val rows = result.items.values.map { it as QueryResultItemDocument.ExactSymbol }
        assertEquals(
            setOf("semanticowners.reference"),
            rows.map { semanticOwnersIdentity(checkNotNull(it.signature)) }.toSet(),
        )
        val connection = rows.single().connections.values.single()
        val start = source.indexOf("::Target") + 2
        assertEquals(
            NativeSemanticOwnersRange(start, start + "Target".length),
            connection.occurrence.range.semanticOwnersSpan(),
        )
        assertEquals(RelationKindDocument.REFERENCES, connection.meaning)
        assertTrue(result.relationObservations.values.flatMap { it.callbackObservations.values }.isEmpty())
        assertTrue(result.failures.values.isEmpty())
        assertTrue(result.omissions.values.isEmpty())
    }

    fun testAnUninvokedOuterLambdaCannotCompleteItsDeferredTrace() = runBlocking {
        val source = resource("semantic-owners/R4_uninvoked_outer_lambda.kt")
        val fixture = prepare(source)
        val protocol = fixture.protocol()
        val outcome =
            protocol.execute(
                request = NativeSemanticOwnersRequests.request(NativeSemanticOwnersTargetShape.CONSTRUCTOR),
                lease = fixture.lease,
                budget = NativeSemanticOwnersRequests.budget,
                policy = NativeSemanticOwnersRequests.policy,
            )
        assertTrue("Expected an unresolved outer route, received $outcome", outcome is OperationOutcome.Rejected)
        val rejection = (outcome as OperationOutcome.Rejected).reason
        assertTrue(rejection is QueryRunRejection.CompletionUnproven)
        val completion = rejection as QueryRunRejection.CompletionUnproven
        val graph = checkNotNull(completion.callbackGraphFailure)
        assertTrue(unresolvedRouteCauses(graph.cause).isNotEmpty())
        val retained = completion.evidence as QueryCompletionEvidenceDocument.Retained
        val read =
            protocol.execute(
                request = retained.readRequest(),
                lease = fixture.lease,
                budget = NativeSemanticOwnersRequests.budget,
                policy = NativeSemanticOwnersRequests.policy,
            )
        assertTrue("The rejected trace must keep its source evidence", read is OperationOutcome.Qualified)
        val result = (read as OperationOutcome.Qualified).evidence.payload
        val callback = result.relationObservations.values.flatMap { it.callbackObservations.values }.single()
        assertEquals(
            "semanticowners.Target",
            semanticOwnersIdentity(callback.target.compilerTarget.compilerEvidence.signature),
        )
        val start = source.lastIndexOf("Target()")
        assertEquals(
            NativeSemanticOwnersRange(start, start + "Target".length),
            callback.occurrence.range.semanticOwnersSpan(),
        )
    }

    private fun assertDeferred(id: String) = runBlocking {
        val case = NativeSemanticOwnersCases.named(id)
        val fixture = prepare(case.source())
        val protocol = fixture.protocol()
        val started = System.nanoTime()
        val outcome =
            protocol.execute(
                request = NativeSemanticOwnersRequests.request(case.targetShape),
                lease = fixture.lease,
                budget = NativeSemanticOwnersRequests.budget,
                policy = NativeSemanticOwnersRequests.policy,
            )
        val queryMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        if (outcome !is OperationOutcome.Complete) {
            println("semanticOwners case=$id queryMillis=$queryMillis status=${outcome.javaClass.simpleName}")
            fail("Expected complete deferred owner case $id, received $outcome")
            return@runBlocking
        }
        val projection = CanonicalQueryCliDocuments.project(outcome) as ProjectedOperationOutcome.Complete
        val completedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        val bytes = projection.document.value.toByteArray(Charsets.UTF_8).size
        println("semanticOwners case=$id queryMillis=$queryMillis status=Complete")
        val result = outcome.evidence.payload
        assertDeferredNavigation(result, case)
        val oracle = NativeSemanticOwnersPublicOracle(Path.of(fixture.lease.workspaceRoot.value))
        val actual = oracle.actual(result)
        assertEquals("Public semantic records for $id", case.expected(), actual)
        assertTrue(case.accepts(actual))
        oracle.assertScopes(result)
        oracle.assertBinary(result)
        println(
            "semanticOwners case=$id projectedMillis=$completedMillis encodedBytes=$bytes " +
                "counters=${fixture.observation.snapshot()}"
        )
    }

    private fun assertDeferredNavigation(
        result: io.github.amichne.kast.protocol.contract.QueryRunResult,
        case: NativeSemanticOwnersCase,
    ) {
        val callbacks = result.relationObservations.values.flatMap { it.callbackObservations.values }
        assertTrue("Deferred observations must remain inspectable", callbacks.isNotEmpty())
        assertTrue(
            "Deferred supply must not become a proven invocation route",
            callbacks.all { it.navigation is QueryCallbackNavigationDocument.Unproven },
        )
        callbacks.forEach { callback ->
            val prefix = (callback.navigation as QueryCallbackNavigationDocument.Unproven).prefix.values
            assertEquals(case.directOuterCalls.size, prefix.size)
            assertEquals(
                case.directOuterCalls,
                prefix
                    .map { step ->
                        (step.evidence as QueryCallbackNavigationEvidenceDocument.DirectInvoke)
                            .binding
                            .occurrence
                            .range
                            .semanticOwnersSpan()
                    }
                    .toSet(),
            )
        }
    }

    private fun unresolvedRouteCauses(cause: QueryCallbackGraphCauseDocument): Set<QueryCallbackFlowCauseDocument> {
        val causes =
            when (cause) {
                is QueryCallbackGraphCauseDocument.Unavailable -> setOf(cause.cause)
                is QueryCallbackGraphCauseDocument.Unresolved -> cause.obligations.values.toSet()
                is QueryCallbackGraphCauseDocument.UnprovenPolicy -> {
                    val policy = cause.policy as QueryCallbackGraphPolicyDocument.Excluded
                    assertEquals(QueryCallbackGraphUnprovenExclusionReasonDocument.STORED_CALLBACK, policy.reason)
                    setOf(QueryCallbackFlowCauseDocument.STORED_CALLBACK)
                }
                is QueryCallbackGraphCauseDocument.InvalidFlow,
                is QueryCallbackGraphCauseDocument.InvalidGraphFailure,
                QueryCallbackGraphCauseDocument.IncompleteScan,
                QueryCallbackGraphCauseDocument.UnsupportedDefaultSupply,
                QueryCallbackGraphCauseDocument.MissingNamedOwner,
                QueryCallbackGraphCauseDocument.SupplierIdentityMismatch,
                QueryCallbackGraphCauseDocument.OutsideWorkspace,
                QueryCallbackGraphCauseDocument.NonCallableTarget,
                QueryCallbackGraphCauseDocument.CallableValueUnproven,
                QueryCallbackGraphCauseDocument.CyclicRoute ->
                    error("Expected an unresolved outer route, received $cause")
            }
        val expected =
            setOf(
                QueryCallbackFlowCauseDocument.NESTED_CALLBACK_EXECUTION,
                QueryCallbackFlowCauseDocument.STORED_CALLBACK,
            )
        assertTrue("Only the independently absent outer route can explain this control", expected.containsAll(causes))
        return causes
    }

    private fun prepare(source: String): NativeScopedTraceFixture {
        lateinit var fixture: NativeScopedTraceFixture
        ApplicationManager.getApplication().invokeAndWait {
            fixture =
                NativeSemanticOwnersFixture(project, module, getOrCreateProjectBaseDir())
                    .prepare(source, ::registerTestProjectJdk)
        }
        return fixture
    }

    private fun resource(name: String) =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)).bufferedReader(Charsets.UTF_8).use {
            it.readText()
        }
}
