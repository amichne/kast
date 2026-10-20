package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.CanonicalCompilerCallableSignature
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueImplementationCallOracleTest {
    @Test
    fun `implementation frame preserves the static call and exact concrete source proof`() {
        val case = implementationCase()
        val root = implementationRoot(WorkspaceSourceRootKind.PRODUCTION)
        val ownership = ValueSourceOwnership.fromCompiler(case.first.source, root).flowRefined()
        val frame =
            ValueCallFrame.implementation(case.call, case.dispatch, case.first, ownership = ownership).flowRefined()
        val target = assertInstanceOf(ValueCallTarget.Implementation::class.java, frame.target)
        val coverage = assertInstanceOf(ValueImplementationCoverage.Exhausted::class.java, target.dispatch.coverage)
        val invocation = assertInstanceOf(ValueInvocation::class.java, frame.invocation)
        assertSame(case.call, frame.invocation)
        assertEquals(case.first.target.valueIdentity, invocation.callable.valueIdentity)
        assertEquals(case.first.source.valueIdentity, frame.callee.enclosing.valueIdentity)
        assertNotEquals(invocation.callable.valueIdentity, frame.callee.enclosing.valueIdentity)
        assertSame(case.dispatch, target.dispatch)
        assertSame(case.compilation.batch, target.dispatch.batch)
        assertSame(case.compilation.coverage, coverage.evidence)
        assertSame(case.first, target.relation)
        assertSame(ownership, target.ownership)
        assertSame(root, target.ownership.root)
        assertEquals(case.first.source.valueIdentity, ownership.declaration)
        assertEquals(case.call.basis, ownership.basis)
    }

    @Test
    fun `two implementations cannot consume each others return for the same static invocation`() {
        val case = implementationCase()
        val first = case.frame(case.first)
        val second = case.frame(case.second)
        val stack = ValueCallStack.Empty.enter(first).flowRefined()
        val source =
            ValueSite.fromCompiler(
                    case.first.source,
                    flowRange(360, 370),
                    ValueRole.Return,
                    callStack = stack,
                )
                .flowRefined()
        assertEquals(first.invocation, second.invocation)
        assertNotEquals(first.callee, second.callee)
        assertEquals(Refinement.Rejected(ValueCallBindingFailure.RETURN_MISMATCH), stack.exit(second))
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            implementationReturn(source, second),
        )
        val matched = implementationReturn(source, first).flowRefined()
        assertEquals(case.call.range, matched.target.range)
        assertEquals(ValueCallStack.Empty, matched.target.callStack)
    }

    @Test
    fun `implementation proof cannot redirect another static callable`() {
        val case = implementationCase()
        val wrong = flowEndpoint("OtherMapper.map", 700, 800, parameters = listOf("String"))
        val invocation = ValueInvocation.fromCompiler(case.call.enclosing, case.call.range, wrong).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.CALLABLE_MISMATCH),
            ValueCallFrame.implementation(
                invocation,
                case.dispatch,
                case.first,
                ownership = case.ownership(case.first),
            ),
        )
    }

    @Test
    fun `foreign invocation basis cannot consume local implementation evidence`() {
        val case = implementationCase()
        val caller = flowEndpoint("invoke", 0, 200, generation = 2)
        val callable = flowEndpoint("Mapper.map", 220, 280, parameters = listOf("String"), generation = 2)
        val foreign = ValueInvocation.fromCompiler(caller, flowRange(20, 50), callable).flowRefined()
        assertEquals(case.call.callable.valueIdentity, foreign.callable.valueIdentity)
        assertNotEquals(case.call.basis, foreign.basis)
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.BASIS_MISMATCH),
            ValueCallFrame.implementation(foreign, case.dispatch, case.first, ownership = case.ownership(case.first)),
        )
    }

    @Test
    fun `a valid fact outside the captured dispatch cannot supply a target`() {
        val case = implementationCase()
        val subset = implementationBatch(case.compilation.batch.request, listOf(case.first))
        val dispatch = ValueImplementationDispatch.fromCompiler(RelationCompilation.complete(subset)).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.IMPLEMENTATION_PROOF_MISMATCH),
            ValueCallFrame.implementation(case.call, dispatch, case.second, ownership = case.ownership(case.second)),
        )
    }

    @Test
    fun `ownership of a same typed sibling does not prove the chosen implementation`() {
        val case = implementationCase()
        val siblingOwnership = case.ownership(case.second)
        assertEquals(
            listOf("String"),
            (case.first.source.signature as CanonicalCompilerCallableSignature).valueParameters.map { it.value },
        )
        assertEquals(
            listOf("String"),
            (case.second.source.signature as CanonicalCompilerCallableSignature).valueParameters.map { it.value },
        )
        assertEquals(case.first.source.file, case.second.source.file)
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.SOURCE_OWNERSHIP_MISMATCH),
            ValueCallFrame.implementation(case.call, case.dispatch, case.first, ownership = siblingOwnership),
        )
    }

    @Test
    fun `production dispatch rejects test ownership of the same declaration`() {
        val case = implementationCase()
        val testRoot = implementationRoot(WorkspaceSourceRootKind.TEST)
        val testOwnership = ValueSourceOwnership.fromCompiler(case.first.source, testRoot).flowRefined()
        assertEquals(case.first.source.valueIdentity, testOwnership.declaration)
        assertEquals(SymbolSourceKindPolicy.PRODUCTION_ONLY, case.dispatch.batch.request.searchScope.sourceKinds)
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.SOURCE_OWNERSHIP_MISMATCH),
            ValueCallFrame.implementation(case.call, case.dispatch, case.first, ownership = testOwnership),
        )
        assertEquals(case.first.source.valueIdentity, case.frame(case.first).callee.enclosing.valueIdentity)
    }

    @Test
    fun `incomplete implementation coverage keeps the exact compiler limitation with an admitted frame`() {
        val case = implementationCase()
        val limitations = setOf(RelationLimitation.UNRESOLVED_TARGET, RelationLimitation.PROVIDER_INCOMPLETE)
        val compiled = RelationCompilation.qualifiedTerminal(case.compilation.batch, limitations).flowRefined()
        val dispatch = ValueImplementationDispatch.fromCompiler(compiled).flowRefined()
        val frame =
            ValueCallFrame.implementation(case.call, dispatch, case.first, ownership = case.ownership(case.first))
                .flowRefined()
        val target = assertInstanceOf(ValueCallTarget.Implementation::class.java, frame.target)
        val coverage = assertInstanceOf(ValueImplementationCoverage.Incomplete::class.java, target.dispatch.coverage)
        assertSame(compiled.batch, target.dispatch.batch)
        assertSame(compiled.coverage, coverage.evidence)
        assertEquals(limitations, coverage.evidence.limitations)
        assertEquals(2, coverage.evidence.knownMinimum.value)
        assertEquals(case.first.source.valueIdentity, frame.callee.enclosing.valueIdentity)
    }

    @Test
    fun `fresh dispatch counters preserve the same frame and matched return`() {
        val case = implementationCase()
        val request =
            RelationRequest.start(case.first.target, RelationMeaning.Implementations, implementationBudget(64))
        val batch = implementationBatch(request, case.compilation.batch.facts, workUnits = 7)
        val dispatch = ValueImplementationDispatch.fromCompiler(RelationCompilation.complete(batch)).flowRefined()
        val fresh =
            ValueCallFrame.implementation(case.call, dispatch, case.first, ownership = case.ownership(case.first))
                .flowRefined()
        val previous = case.frame(case.first)
        val returned =
            ValueSite.fromCompiler(
                    case.first.source,
                    flowRange(360, 370),
                    ValueRole.Return,
                    callStack = ValueCallStack.Empty.enter(previous).flowRefined(),
                )
                .flowRefined()
        assertNotSame(case.compilation.batch.request, batch.request)
        assertNotEquals(case.compilation.batch.request.budget, batch.request.budget)
        assertEquals(2L, case.compilation.batch.examinedWorkUnits.value)
        assertEquals(7L, batch.examinedWorkUnits.value)
        assertEquals(previous, fresh)
        assertEquals(previous.hashCode(), fresh.hashCode())
        assertEquals(ValueCallStack.Empty, implementationReturn(returned, fresh).flowRefined().target.callStack)
    }

    @Test
    fun `changed coverage or concrete implementation changes frame identity`() {
        val case = implementationCase()
        val compiled =
            RelationCompilation.qualifiedTerminal(case.compilation.batch, setOf(RelationLimitation.UNRESOLVED_TARGET))
                .flowRefined()
        val partial = ValueImplementationDispatch.fromCompiler(compiled).flowRefined()
        val partialFrame =
            ValueCallFrame.implementation(case.call, partial, case.first, ownership = case.ownership(case.first))
                .flowRefined()
        val full = case.frame(case.first)
        assertNotEquals(full, partialFrame)
        assertNotEquals(full, case.frame(case.second))
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.RETURN_MISMATCH),
            ValueCallStack.Empty.enter(full).flowRefined().exit(partialFrame),
        )
    }

    @Test
    fun `changed source domain changes frame identity for the same declarations`() {
        val production = implementationCase()
        val combined = implementationCase(SymbolSourceKindPolicy.PRODUCTION_AND_TEST)
        val first = production.frame(production.first)
        val second = combined.frame(combined.first)
        assertEquals(first.invocation.identity, second.invocation.identity)
        assertEquals(first.callee.identity, second.callee.identity)
        assertNotEquals(production.dispatch.batch.request.searchScope, combined.dispatch.batch.request.searchScope)
        assertNotEquals(first, second)
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.RETURN_MISMATCH),
            ValueCallStack.Empty.enter(first).flowRefined().exit(second),
        )
    }
}

private data class ImplementationCase(
    val call: ValueInvocation,
    val first: RelationFact,
    val second: RelationFact,
    val compilation: RelationCompilation.Complete,
    val dispatch: ValueImplementationDispatch,
) {
    fun ownership(fact: RelationFact): ValueSourceOwnership =
        ValueSourceOwnership.fromCompiler(fact.source, implementationRoot(WorkspaceSourceRootKind.PRODUCTION))
            .flowRefined()

    fun frame(fact: RelationFact): ValueCallFrame =
        ValueCallFrame.implementation(call, dispatch, fact, ownership = ownership(fact)).flowRefined()
}

private fun implementationCase(
    sourceKinds: SymbolSourceKindPolicy = SymbolSourceKindPolicy.PRODUCTION_ONLY
): ImplementationCase {
    val subject = implementationEndpoint("Mapper.map", 220, 280, sourceKinds = sourceKinds)
    val caller = implementationEndpoint("invoke", 0, 200, sourceKinds = sourceKinds)
    val request = RelationRequest.start(subject, RelationMeaning.Implementations, implementationBudget())
    val first =
        implementationFact(request, implementationEndpoint("FirstMapper.map", 300, 400, sourceKinds = sourceKinds))
    val second =
        implementationFact(request, implementationEndpoint("SecondMapper.map", 450, 550, sourceKinds = sourceKinds))
    val compilation = RelationCompilation.complete(implementationBatch(request, listOf(first, second)))
    return ImplementationCase(
        ValueInvocation.fromCompiler(caller, flowRange(20, 50), subject).flowRefined(),
        first,
        second,
        compilation,
        ValueImplementationDispatch.fromCompiler(compilation).flowRefined(),
    )
}

private fun implementationEndpoint(
    name: String,
    start: Int,
    end: Int,
    sourceKinds: SymbolSourceKindPolicy,
): RelationEndpoint {
    val endpoint = flowEndpoint(name, start, end, parameters = listOf("String")) as RelationEndpoint.Resolved
    val scope =
        SymbolSearchScope.Workspace(
            sourceKinds,
            SymbolGeneratedSourcePolicy.EXCLUDE,
            SymbolLibraryPolicy.EXCLUDE,
        )
    return RelationEndpoint.resolve(endpoint.lease, scope, endpoint.evidence).flowRefined()
}

private fun implementationFact(request: RelationRequest, source: RelationEndpoint): RelationFact =
    RelationFact.create(
            request,
            source,
            request.subject,
            RelationOccurrence.fromSource(source.file, source.range),
            RelationProvenance.K2_AUTHORED_SOURCE,
        )
        .flowRefined()

internal fun implementationBatch(
    request: RelationRequest,
    facts: List<RelationFact>,
    workUnits: Long = facts.size.toLong(),
): RelationBatch {
    val ordered = facts.sorted()
    val bytes = ordered.sumOf { it.canonicalProjection().toByteArray(Charsets.UTF_8).size.toLong() }
    return RelationBatch.create(
            request = request,
            facts = ordered,
            encodedBytes = RelationByteCount.parse(bytes).flowRefined(),
            examinedWorkUnits = RelationWorkCount.parse(workUnits).flowRefined(),
            resultCount = RelationResultCount.parse(facts.size).flowRefined(),
        )
        .flowRefined()
}

internal fun implementationBudget(workLimit: Long = 32): RelationBudget =
    RelationBudget(
        ResourceBudget(
            ResultLimit.parse(8).flowRefined(),
            WorkUnitLimit.parse(workLimit).flowRefined(),
            ElapsedTimeLimitMillis.parse(1000).flowRefined(),
        ),
        RelationByteLimit.parse(100000).flowRefined(),
    )

private fun implementationRoot(kind: WorkspaceSourceRootKind): ModelOwnedSourceRoot {
    val path = Path.of("/fixture")
    val compiled =
        WorkspaceSearchScopeModel.compile(
            CanonicalWorkspaceRoot.fromCanonicalPath(path).flowRefined(),
            ImportedWorkspaceModelState.COMPLETE,
            listOf(
                WorkspaceSourceRootBoundary(
                    ideaModuleName = "fixture",
                    linkedBuildRoot = path,
                    gradleProjectPath = ":",
                    sourceSetName = if (kind == WorkspaceSourceRootKind.TEST) "test" else "main",
                    sourceRoot = path,
                    sourceKind = kind,
                    provenance = WorkspaceSourceRootProvenance.AUTHORED,
                )
            ),
        )
    return assertInstanceOf(WorkspaceSearchScopeModelCompilation.Compiled::class.java, compiled)
        .model
        .sourceRoots
        .single()
}

private fun implementationReturn(
    source: ValueSite,
    frame: ValueCallFrame,
): Refinement<ValueTransfer, ValueTransferFailure> =
    ValueTransfer.fromCompiler(
        source,
        frame.invocation.resultSite(),
        ValueTransferKind.SOURCE_CALL_RETURN,
        evidence = ValueSourceTransfer.fromCompiler(ValueSourceOperation.Return(frame)),
    )
