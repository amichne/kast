package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackArgumentBinding
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.CallbackInvocationFlow
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackInvocationScan
import io.github.amichne.kast.relation.contract.CallbackNamedCallPolicy
import io.github.amichne.kast.relation.contract.CallbackParameterInvocation
import io.github.amichne.kast.relation.contract.CallbackResultProjection
import io.github.amichne.kast.relation.contract.CallbackResultSite
import io.github.amichne.kast.relation.contract.CompleteStaticCallbackGraph
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationCallbackObservation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Real scope and work admission use bounded SDK file observations. */
class CallbackResultWorkAdmissionTest {
    @Test
    fun `known excluded occurrence cannot consume the eligible invocation work unit`() {
        val case = SelectionCase(1)
        val result = case.project()
        val admitted =
            assertInstanceOf(CallbackResultProjectionRead.Admitted::class.java, result, "Actual result: $result")
        assertEquals(CallbackResultSite.Invocation(case.invocation), admitted.membership.site)
        assertTrue(admitted.membership.belongsTo(case.request))
        assertEquals(listOf(case.inside), case.lookups)
    }

    @Test
    fun `two work units admit the same invocation as a contrasting control`() {
        val case = SelectionCase(2)
        val admitted = assertInstanceOf(CallbackResultProjectionRead.Admitted::class.java, case.project())
        assertEquals(CallbackResultSite.Invocation(case.invocation), admitted.membership.site)
    }

    @Test
    fun `known exclusions do not bypass the original deadline`() {
        val case = SelectionCase(1)
        case.expire()
        assertEquals(
            CallbackResultProjectionRead.Unproven(CallbackInvocationFlowCause.TIME_LIMIT_REACHED),
            case.project(),
        )
        assertEquals(emptyList<SymbolDiscoveryFileIdentity>(), case.lookups)
    }

    private class SelectionCase(workLimit: Long) {
        private val base = RelationReadTest().request(RelationMeaning.Callers, workLimit = workLimit)
        private val root = Path.of(base.subject.lease.workspaceRoot.value)
        val request =
            RelationRequest.start(
                base.subject,
                RelationMeaning.Callers,
                base.budget,
                RelationSearchBoundary.Explicit(
                    base.subject.scope,
                    SymbolDiscoveryDirectoryConstraint(
                        SymbolDiscoveryDirectory.parse("client").refined(),
                        SymbolDiscoveryContainment.DESCENDANTS,
                    ),
                ),
            )
        val outside = file("supplier/Supplier.kt")
        val inside = file("client/Receiver.kt")
        private val supplier = evidence(outside, "supply", emptyList())
        private val receiver = evidence(inside, "receive", listOf("()->Unit"))
        val invocation =
            CallbackParameterInvocation.fromCompiler(
                    occurrence(inside, 50, 55),
                    RelationCallableBody.Named.fromCompiler(receiver).refined(),
                )
                .refined()
        private val raw = observation()
        private var now = 0L
        private val collector = IntellijRelationCollector(request, { now })
        private val sdk = RelationScopeSdkFixture(sourceContains = { true })
        private val scope = compile()
        val lookups = mutableListOf<SymbolDiscoveryFileIdentity>()

        fun project(): CallbackResultProjectionRead {
            assertTrue(CompleteStaticCallbackGraph.admit(raw) is Refinement.Refined)
            val result =
                IntellijCallbackResultProjection(
                        sdk.project,
                        scope,
                        collector,
                        IntellijReadObservation.None,
                        {},
                        ::lookup,
                    )
                    .callback(raw)
            sdk.assertConsumed()
            return result
        }

        fun expire() {
            now = request.budget.resources.bounded().elapsedTimeLimit.value * 1_000_000L
        }

        private fun lookup(identity: SymbolDiscoveryFileIdentity): VirtualFile {
            check(identity == outside || identity == inside) { "Unexpected file identity: $identity" }
            check(identity !in lookups) { "Unexpected repeated lookup: $identity" }
            lookups += identity
            val path = (identity as SymbolDiscoveryFileIdentity.Workspace).path.value
            return EnumerationTestFile(Path.of(path), if (identity == outside) 1 else 2)
        }

        private fun compile(): CompiledRelationScope {
            val model =
                WorkspaceSearchScopeModel.compile(
                    base.subject.lease.workspaceRoot,
                    ImportedWorkspaceModelState.COMPLETE,
                    listOf("src", "supplier", "client").map { name ->
                        WorkspaceSourceRootBoundary(
                            "$name.main",
                            root,
                            ":$name",
                            "main",
                            root.resolve(name),
                            WorkspaceSourceRootKind.PRODUCTION,
                            WorkspaceSourceRootProvenance.AUTHORED,
                        )
                    },
                )
            return when (val compiled = IntellijRelationScopeCompiler().compile(sdk.project, request, model)) {
                is IntellijRelationScopeCompilation.Compiled -> compiled.scope
                is IntellijRelationScopeCompilation.Rejected -> error("Invalid fixture scope: ${compiled.failures}")
            }
        }

        private fun observation(): RelationCallbackObservation {
            val flow =
                CallbackInvocationFlow.fromCompiler(
                        base.subject.lease.identity,
                        callbackBody(),
                        CallbackBindingEvidence.Bound(binding()),
                        listOf(invocation),
                        emptySet(),
                        CallbackInvocationScan.EXHAUSTIVE,
                    )
                    .refined()
            val subject = assertInstanceOf(RelationEndpoint.Subject::class.java, base.subject)
            return RelationCallbackObservation.fromNativeBoundary(
                    request,
                    occurrence(outside, 21, 22),
                    CompilerGroundedSymbolEvidence.fromSelector(subject.selector),
                    supplier,
                    occurrence(outside, 20, 30),
                    CallbackNamedCallPolicy.AdmittedInline,
                    CallbackInvocationFlowRead.Observed(flow),
                    CallbackResultProjection.Unproven(CallbackInvocationFlowCause.OUTSIDE_DOMAIN),
                )
                .refined()
        }

        private fun binding(): CallbackArgumentBinding {
            val source = RelationEndpoint.resolve(base.subject.lease, base.subject.scope, supplier).refined()
            val target = RelationEndpoint.resolve(base.subject.lease, base.subject.scope, receiver).refined()
            return CallbackArgumentBinding.fromCompiler(
                    ValueInvocation.fromCompiler(source, range(10, 40), target).refined(),
                    RelationCallableBody.Named.fromCompiler(supplier).refined(),
                    ValueArgumentPosition.parse(0).refined(),
                    occurrence(inside, 5, 15),
                )
                .refined()
        }

        private fun callbackBody(): RelationCallableBody.Anonymous {
            val bodyRange = range(20, 30)
            val signature =
                CanonicalCompilerSignature.function(
                        RelationCallableBody.Anonymous.sourceIdentity(outside, bodyRange),
                        null,
                        emptyList(),
                        emptyList(),
                        0,
                    )
                    .refined()
            return RelationCallableBody.Anonymous.fromCompiler(
                    outside,
                    bodyRange,
                    assertInstanceOf(CanonicalCompilerSignature.Function::class.java, signature),
                )
                .refined()
        }

        private fun file(path: String) =
            SymbolDiscoveryFileIdentity.Workspace(
                CanonicalWorkspaceFilePath.fromCanonicalPath(base.subject.lease.workspaceRoot, root.resolve(path))
                    .refined()
            )

        private fun occurrence(file: SymbolDiscoveryFileIdentity.Workspace, start: Int, end: Int) =
            RelationOccurrence.fromBoundary(file, start, end).refined()

        private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).refined()

        private fun evidence(file: SymbolDiscoveryFileIdentity.Workspace, name: String, parameters: List<String>) =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file,
                    0,
                    100,
                    name,
                    "sample.$name",
                    CompilerSymbolKind.FUNCTION,
                    CanonicalCompilerSignature.function("sample.$name", null, emptyList(), parameters, 0).refined(),
                )
                .refined()
    }
}

private fun <T, F> Refinement<T, F>.refined(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid fixture: $failure")
    }
