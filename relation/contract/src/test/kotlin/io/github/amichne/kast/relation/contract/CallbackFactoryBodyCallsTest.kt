package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CallbackFactoryBodyCallsTest {
    private val fixture = CallbackInvocationFlowFixture()

    @Test
    fun `returned anonymous body cannot claim complete without its entire call inventory`() =
        with(fixture) {
            val call =
                ValueInvocation.fromCompiler(caller, range(20, 80), endpoint("factory", 210, 400, emptyList())).value()
            val returned = returned(call, anonymous(270, 300))
            assertEquals(
                Refinement.Rejected(CallbackFactoryReturnFailure.BODY_CALL_INVENTORY_MISMATCH),
                CallbackFactoryReturn.fromCompiler(call, returned, emptyList(), CallbackFactoryBodyCalls.NotApplicable),
            )
        }

    @Test
    fun `compiler confirmed named body call remains part of returned factory proof`() =
        with(fixture) {
            val call =
                ValueInvocation.fromCompiler(caller, range(20, 80), endpoint("factory", 210, 400, emptyList())).value()
            val body = anonymous(270, 300)
            val beta = endpoint("betaTarget", 610, 700, emptyList())
            val entry = CallbackFactoryBodyCall.Named(occurrence(280, 290), beta)
            val calls =
                CallbackFactoryBodyCalls.Exhaustive.fromCompiler(body, listOf(entry), CallbackInvocationScan.EXHAUSTIVE)
                    .value()
            val proof = CallbackFactoryReturn.fromCompiler(call, returned(call, body), emptyList(), calls).value()
            assertEquals(listOf(entry), (proof.bodyCalls as CallbackFactoryBodyCalls.Exhaustive).calls)
            assertEquals(body, calls.body)
        }

    @Test
    fun `partial body scan cannot become an exhaustive inventory`() =
        with(fixture) {
            assertEquals(
                Refinement.Rejected(CallbackFactoryBodyFailure.INCOMPLETE),
                CallbackFactoryBodyCalls.Exhaustive.fromCompiler(body, emptyList(), CallbackInvocationScan.INCOMPLETE),
            )
        }

    @Test
    fun `duplicate or outside body call is rejected before factory admission`() =
        with(fixture) {
            val inside = CallbackFactoryBodyCall.Named(occurrence(40, 50), target)
            assertEquals(
                Refinement.Rejected(CallbackFactoryBodyFailure.DUPLICATE_OCCURRENCE),
                CallbackFactoryBodyCalls.Exhaustive.fromCompiler(
                    body,
                    listOf(inside, inside),
                    CallbackInvocationScan.EXHAUSTIVE,
                ),
            )
            assertEquals(
                Refinement.Rejected(CallbackFactoryBodyFailure.OCCURRENCE_OUTSIDE_BODY),
                CallbackFactoryBodyCalls.Exhaustive.fromCompiler(
                    body,
                    listOf(CallbackFactoryBodyCall.Named(occurrence(100, 110), target)),
                    CallbackInvocationScan.EXHAUSTIVE,
                ),
            )
        }

    @Test
    fun `another anonymous body inventory cannot replace the selected returned body`() =
        with(fixture) {
            val call =
                ValueInvocation.fromCompiler(caller, range(20, 80), endpoint("factory", 210, 400, emptyList())).value()
            assertEquals(
                Refinement.Rejected(CallbackFactoryReturnFailure.BODY_CALL_INVENTORY_MISMATCH),
                CallbackFactoryReturn.fromCompiler(
                    call,
                    returned(call, anonymous(270, 300)),
                    emptyList(),
                    bodyCalls(anonymous(310, 330)),
                ),
            )
        }

    @Test
    fun `body named target from another basis cannot enter the factory proof`() =
        with(fixture) {
            val call =
                ValueInvocation.fromCompiler(caller, range(20, 80), endpoint("factory", 210, 400, emptyList())).value()
            val body = anonymous(270, 300)
            val beta = endpoint("betaTarget", 610, 700, emptyList())
            val otherLease =
                io.github.amichne.kast.workspace.contract.SemanticReadLease(
                    root,
                    io.github.amichne.kast.kernel.EvidenceGeneration.parse(2).value(),
                )
            val foreign = RelationEndpoint.resolve(otherLease, beta.scope, beta.evidence, beta.constraints).value()
            assertEquals(
                Refinement.Rejected(CallbackFactoryReturnFailure.BASIS_MISMATCH),
                CallbackFactoryReturn.fromCompiler(
                    call,
                    returned(call, body),
                    emptyList(),
                    bodyCalls(body, listOf(CallbackFactoryBodyCall.Named(occurrence(280, 290), foreign))),
                ),
            )
        }

    @Test
    fun `unavailable library body call cannot become complete through a boundary tag`() =
        with(fixture) {
            val library = libraryBoundary()
            assertEquals(
                Refinement.Rejected(CallbackFactoryBodyFailure.BOUNDARY_DISPOSITION_MISMATCH),
                CallbackFactoryBodyCalls.Exhaustive.fromCompiler(
                    body,
                    listOf(
                        CallbackFactoryBodyCall.Boundary(
                            occurrence(40, 50),
                            library,
                            SourceLessCallableDisposition.LIBRARY_SOURCE_UNAVAILABLE,
                        )
                    ),
                    CallbackInvocationScan.EXHAUSTIVE,
                ),
            )
        }

    @Test
    fun `compiler confirmed excluded library call retains its qualified boundary`() =
        with(fixture) {
            val entry =
                CallbackFactoryBodyCall.Boundary(
                    occurrence(40, 50),
                    libraryBoundary(),
                    SourceLessCallableDisposition.LIBRARY_POLICY_EXCLUDED,
                )
            assertEquals(listOf(entry), bodyCalls(body, listOf(entry)).calls)
        }

    @Test
    fun `library include scope cannot manufacture a policy exclusion for returned body`() =
        with(fixture) {
            val scope =
                io.github.amichne.kast.symbol.contract.SymbolSearchScope.Workspace(
                    io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy.EXCLUDE,
                    io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy.INCLUDE,
                )
            val producer = endpoint("factory", 210, 400, emptyList())
            val included = RelationEndpoint.resolve(lease, scope, producer.evidence).value()
            val call = ValueInvocation.fromCompiler(caller, range(20, 80), included).value()
            val body = anonymous(270, 300)
            val entry =
                CallbackFactoryBodyCall.Boundary(
                    occurrence(280, 290),
                    libraryBoundary(),
                    SourceLessCallableDisposition.LIBRARY_POLICY_EXCLUDED,
                )
            assertEquals(
                Refinement.Rejected(CallbackFactoryReturnFailure.BODY_CALL_INVENTORY_MISMATCH),
                CallbackFactoryReturn.fromCompiler(
                    call,
                    returned(call, body),
                    emptyList(),
                    bodyCalls(body, listOf(entry)),
                ),
            )
        }

    private fun libraryBoundary() =
        with(fixture) {
            SourceLessCallable.fromCompiler(
                    target.signature,
                    io.github.amichne.kast.symbol.contract.CompilerSymbolKind.FUNCTION,
                    SourceLessCallableOrigin.LIBRARY,
                    SourceLessCallableModuleKind.LIBRARY,
                    SourceLessCallableModuleName.parse("library").value(),
                )
                .value()
        }

    private fun returned(call: ValueInvocation, body: RelationCallableBody.Anonymous) =
        with(fixture) {
            val source = ValueSite.fromCompiler(call.callable, body.range, ValueRole.ExpressionResult).value()
            ImmutableCallbackValue.fromCompiler(
                    ImmutableCallbackValueOrigin.Anonymous(body),
                    source,
                    source,
                    emptyList(),
                )
                .value()
        }
}
