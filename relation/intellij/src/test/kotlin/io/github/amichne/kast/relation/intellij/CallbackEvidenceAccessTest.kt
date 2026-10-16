package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackExclusionReason
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowCause
import io.github.amichne.kast.relation.contract.CallbackInvocationFlowRead
import io.github.amichne.kast.relation.contract.CallbackNamedCallPolicy
import io.github.amichne.kast.relation.contract.CallbackResultProjection
import io.github.amichne.kast.relation.contract.RelationCallbackObservation
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Real scope refinement and the original budget governor consume scripted SDK observations. No K2 claim follows. */
class CallbackEvidenceAccessTest {
    private val base = RelationReadTest().request(RelationMeaning.Callees)
    private val root = Path.of(base.subject.lease.workspaceRoot.value)
    private val model =
        WorkspaceSearchScopeModel.compile(
            base.subject.lease.workspaceRoot,
            ImportedWorkspaceModelState.COMPLETE,
            listOf(
                source(
                    "subject.main",
                    "src",
                    WorkspaceSourceRootKind.PRODUCTION,
                    WorkspaceSourceRootProvenance.AUTHORED,
                ),
                source(
                    "client.main",
                    "client",
                    WorkspaceSourceRootKind.PRODUCTION,
                    WorkspaceSourceRootProvenance.AUTHORED,
                ),
                source(
                    "receiver.test",
                    "receiver/test",
                    WorkspaceSourceRootKind.TEST,
                    WorkspaceSourceRootProvenance.AUTHORED,
                ),
                source(
                    "receiver.generated",
                    "receiver/generated",
                    WorkspaceSourceRootKind.PRODUCTION,
                    WorkspaceSourceRootProvenance.GENERATED,
                ),
            ),
        )

    @Test
    fun `owned test and generated evidence do not broaden selected production results`() {
        val sdk = RelationScopeSdkFixture(sourceContains = { true })
        val selected =
            RelationRequest.start(
                base.subject,
                RelationMeaning.Callers,
                base.budget,
                RelationSearchBoundary.Explicit(
                    SymbolSearchScope.Workspace(
                        SymbolSourceKindPolicy.PRODUCTION_ONLY,
                        SymbolGeneratedSourcePolicy.EXCLUDE,
                        SymbolLibraryPolicy.EXCLUDE,
                    ),
                    SymbolDiscoveryDirectoryConstraint(
                        SymbolDiscoveryDirectory.parse("client").refined(),
                        SymbolDiscoveryContainment.DESCENDANTS,
                    ),
                ),
            )
        val compiler = IntellijRelationScopeCompiler()
        val result = compile(sdk, selected, compiler)
        val evidence = evidence(sdk, result, compiler).sources
        assertFalse(result.nativeScope.isSearchInModuleContent(sdk.module("receiver.test"), true))
        assertTrue(evidence.nativeScope.isSearchInModuleContent(sdk.module("receiver.test"), true))
        for ((index, path) in listOf("receiver/test/Receiver.kt", "receiver/generated/Receiver.kt").withIndex()) {
            val file = EnumerationTestFile(root.resolve(path), index)
            assertEquals(RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED, result.admitProviderSite(file))
            assertEquals(RelationProviderScopeAdmission.ADMITTED, evidence.admitProviderSite(file))
        }
        assertFalse(evidence.nativeScope.isSearchInLibraries)
        sdk.assertConsumed()
    }

    @Test
    fun `callback evidence rejects unowned files and preserves injected source admission`() {
        val sdk = RelationScopeSdkFixture(sourceContains = { true })
        val compiler = IntellijRelationScopeCompiler { it.fileName.toString() != "Denied.kt" }
        val evidence = evidence(sdk, compile(sdk, base, compiler), compiler).sources
        for ((index, path) in listOf("unowned/Other.kt", "client/Denied.kt").withIndex()) {
            assertEquals(
                RelationProviderScopeAdmission.UNAVAILABLE,
                evidence.admitProviderSite(EnumerationTestFile(root.resolve(path), index)),
            )
        }
        assertTrue(sdk.fileIndexCalls.isEmpty(), "Rejected inputs must not reach the source index")
        sdk.assertConsumed()
    }

    @Test
    fun `previous callback work exhausts the same grant before result membership reads a file`() {
        val read = RelationReadTest().request(RelationMeaning.Callees, workLimit = 1L)
        val sdk = RelationScopeSdkFixture()
        val collector = IntellijRelationCollector(read, { 0L })
        assertEquals(CallbackWorkAdmission.READY, collector.admitCallbackWork())
        val effects = NoFileEffects()
        val projection = IntellijCallbackResultProjection(sdk.project, compile(sdk, read), collector, effects, {})
        assertEquals(
            CallbackResultProjectionRead.Unproven(CallbackInvocationFlowCause.WORK_LIMIT_REACHED),
            projection.callback(raw(read)),
        )
        assertEquals(0, effects.fileCalls)
        sdk.assertConsumed()
    }

    @Test
    fun `the original deadline stops result membership before file inspection`() {
        val sdk = RelationScopeSdkFixture()
        var now = 0L
        val collector = IntellijRelationCollector(base, { now })
        now = base.budget.resources.elapsedTimeLimit.value * NANOS_PER_MILLISECOND
        val effects = NoFileEffects()
        val projection = IntellijCallbackResultProjection(sdk.project, compile(sdk, base), collector, effects, {})
        assertEquals(
            CallbackResultProjectionRead.Unproven(CallbackInvocationFlowCause.TIME_LIMIT_REACHED),
            projection.callback(raw(base)),
        )
        assertEquals(0, effects.fileCalls)
        sdk.assertConsumed()
    }

    private fun compile(
        sdk: RelationScopeSdkFixture,
        request: RelationRequest,
        compiler: IntellijRelationScopeCompiler = IntellijRelationScopeCompiler(),
    ): CompiledRelationScope =
        when (val result = compiler.compile(sdk.project, request, model)) {
            is IntellijRelationScopeCompilation.Compiled -> result.scope
            is IntellijRelationScopeCompilation.Rejected -> error("Invalid modeled fixture: ${result.failures}")
        }

    private fun evidence(
        sdk: RelationScopeSdkFixture,
        selected: CompiledRelationScope,
        compiler: IntellijRelationScopeCompiler,
    ): CallbackEvidenceAccess =
        CallbackEvidenceAccess.compile(selected) { scope, constraints ->
                when (val result = compiler.compile(sdk.project, selected.request, model, scope, constraints)) {
                    is IntellijRelationScopeCompilation.Compiled -> Refinement.Refined(result.scope)
                    is IntellijRelationScopeCompilation.Rejected ->
                        Refinement.Rejected(RelationCompilerRejection.SCOPE_REJECTED)
                }
            }
            .refined()

    private fun raw(read: RelationRequest): RelationCallbackObservation {
        val selector = (read.subject as io.github.amichne.kast.relation.contract.RelationEndpoint.Subject).selector
        val owner = CompilerGroundedSymbolEvidence.fromSelector(selector)
        val body = RelationOccurrence.fromBoundary(selector.file, 42, 49).refined()
        return RelationCallbackObservation.fromNativeBoundary(
                request = read,
                occurrence = RelationOccurrence.fromBoundary(selector.file, 43, 44).refined(),
                target = owner,
                lexicalOwner = owner,
                callbackBody = body,
                policy = CallbackNamedCallPolicy.Excluded(CallbackExclusionReason.NON_INLINE_ARGUMENT, body),
                flow = CallbackInvocationFlowRead.Unavailable(CallbackInvocationFlowCause.STORED_CALLBACK),
                resultProjection = CallbackResultProjection.Unproven(CallbackInvocationFlowCause.OUTSIDE_DOMAIN),
            )
            .refined()
    }

    private fun source(
        module: String,
        path: String,
        kind: WorkspaceSourceRootKind,
        provenance: WorkspaceSourceRootProvenance,
    ) =
        WorkspaceSourceRootBoundary(
            module,
            root,
            ":${module.substringBefore('.')}",
            "${if (kind == WorkspaceSourceRootKind.TEST) "test" else "main"}",
            root.resolve(path),
            kind,
            provenance,
        )

    private class NoFileEffects : IntellijReadObservation {
        var fileCalls = 0

        override fun count(
            counter: IntellijReadCounter,
            contributor: io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor,
            amount: Int,
        ) = Unit

        override fun terminated(
            reason: io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination,
            contributor: io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor,
        ) = Unit

        override fun enterCall(call: IntellijReadCall): IntellijReadCallScope {
            if (call == IntellijReadCall.VFS_FIND_FILE || call == IntellijReadCall.PSI_FIND_FILE) {
                fileCalls++
                throw AssertionError("An exhausted original grant must prevent the native file effect")
            }
            return IntellijReadCallScope.None
        }
    }

    private fun <V, F> Refinement<V, F>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid fixture input: $failure")
        }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
