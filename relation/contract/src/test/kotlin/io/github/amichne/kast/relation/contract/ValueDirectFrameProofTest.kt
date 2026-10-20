package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValueDirectFrameProofTest {
    @Test
    fun `equal distinct endpoints preserve direct full proof without a shared object`() {
        val f = DirectFrameProofCase()
        val first = f.frame()
        val host = copiedEndpoint(f.host)
        val callable = copiedEndpoint(f.callable)
        val second = f.frame(enclosing = host, callable = callable)
        assertNotSame(f.host, host)
        assertNotSame(f.callable, callable)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertTrue(first.callee.hasSameProofAs(second.callee))
        assertTrue(first.hasSameDirectProofAs(second))
        assertTrue(stack(first).hasSameDirectProofAs(stack(second)))
    }

    @Test
    fun `subject and resolved endpoints can prove the same direct frame`() {
        val f = DirectFrameProofCase()
        val subjectHost = RelationEndpoint.subject(SymbolSelector.issue(f.host.lease, f.host.scope, f.host.evidence))
        val subjectCallable =
            RelationEndpoint.subject(SymbolSelector.issue(f.callable.lease, f.callable.scope, f.callable.evidence))
        assertNotEquals(f.host, subjectHost)
        assertTrue(f.host.valueExecutionOwner.hasSameProofAs(subjectHost.valueExecutionOwner))
        val first = f.frame()
        val second = f.frame(enclosing = subjectHost, callable = subjectCallable)
        assertEquals(first, second)
        assertTrue(first.hasSameDirectProofAs(second))
        assertTrue(second.hasSameDirectProofAs(first))
        assertTrue(stack(first).hasSameDirectProofAs(stack(second)))
    }

    @Test
    fun `same frame identity cannot borrow a different scope from any endpoint position`() {
        val f = DirectFrameProofCase()
        val host =
            copiedEndpoint(
                f.host,
                scope = (f.host.scope as SymbolSearchScope.Workspace).copy(libraries = SymbolLibraryPolicy.INCLUDE),
            )
        val callable =
            copiedEndpoint(
                f.callable,
                scope = (f.callable.scope as SymbolSearchScope.Workspace).copy(libraries = SymbolLibraryPolicy.INCLUDE),
            )
        assertNotEquals(f.host.scope, host.scope)
        assertFalse(f.host.valueExecutionOwner.hasSameProofAs(host.valueExecutionOwner))
        assertForeignPositions(f, host, callable)
    }

    @Test
    fun `same frame identity cannot borrow different constraints from any endpoint position`() {
        val f = DirectFrameProofCase()
        val kinds = SymbolDiscoveryDeclarationKinds.from(setOf(f.host.kind)).flowRefined()
        val constraints = SymbolDiscoveryConstraints.None.copy(declarationKinds = kinds)
        val host = copiedEndpoint(f.host, constraints = constraints)
        val callable = copiedEndpoint(f.callable, constraints = constraints)
        assertNotEquals(f.host.constraints, host.constraints)
        assertFalse(f.host.valueExecutionOwner.hasSameProofAs(host.valueExecutionOwner))
        assertForeignPositions(f, host, callable)
    }

    @Test
    fun `basis body and actual call range remain necessary proof facts`() {
        val f = DirectFrameProofCase()
        val foreign = DirectFrameProofCase(generation = 2)
        assertEquals(f.host.valueIdentity, foreign.host.valueIdentity)
        assertNotEquals(f.host.lease, foreign.host.lease)
        assertFalse(f.host.valueExecutionOwner.hasSameProofAs(foreign.host.valueExecutionOwner))
        assertFalse(f.frame().hasSameDirectProofAs(foreign.frame()))
        val firstBody = callbackOracleOwner(f.host, 10, 100)
        val equalBody = callbackOracleOwner(copiedEndpoint(f.host), 10, 100)
        val otherBody = callbackOracleOwner(f.host, 10, 110)
        assertNotSame(firstBody.body, equalBody.body)
        assertTrue(firstBody.hasSameProofAs(equalBody))
        assertFalse(firstBody.hasSameProofAs(otherBody))
        assertFalse(firstBody.hasSameProofAs(f.host.valueExecutionOwner))
        assertFalse(f.frame(execution = firstBody).hasSameDirectProofAs(f.frame(execution = otherBody)))
        assertFalse(f.frame().hasSameDirectProofAs(f.frame(range = flowRange(60, 90))))
    }

    @Test
    fun `direct stacks compare every frame in order and permit two empty stacks`() {
        val f = DirectFrameProofCase()
        val first = f.frame()
        val second = f.frame(range = flowRange(60, 90))
        val ordered = stack(first).enter(second).flowRefined()
        val reversed = stack(second).enter(first).flowRefined()
        assertTrue(ValueCallStack.Empty.hasSameDirectProofAs(ValueCallStack.Empty))
        assertTrue(ordered.hasSameDirectProofAs(stack(first).enter(second).flowRefined()))
        assertFalse(ordered.hasSameDirectProofAs(reversed))
        assertFalse(ordered.hasSameDirectProofAs(stack(first)))
        assertFalse(ValueCallStack.Empty.hasSameDirectProofAs(stack(first)))
        assertFalse(stack(first).hasSameDirectProofAs(ValueCallStack.Empty))
    }

    @Test
    fun `callback implementation and reviewed frames cannot claim direct proof even against themselves`() {
        val frames =
            listOf(ValueCallbackOracleCase().frame(), implementationFrame(), ValueLetCallbackOracleCase().frame())
        for (frame in frames) {
            assertEquals(frame, frame)
            assertFalse(frame.hasSameDirectProofAs(frame))
            val context = stack(frame)
            assertFalse(context.hasSameDirectProofAs(context))
        }
    }

    private fun assertForeignPositions(
        f: DirectFrameProofCase,
        host: RelationEndpoint,
        callable: RelationEndpoint,
    ) {
        val original = f.frame()
        val foreign =
            listOf(
                f.frame(enclosing = host, execution = f.host.valueExecutionOwner),
                f.frame(callable = callable, callee = f.callable.valueExecutionOwner),
                f.frame(execution = host.valueExecutionOwner),
                f.frame(callee = callable.valueExecutionOwner),
            )
        for (candidate in foreign) {
            assertEquals(original, candidate)
            assertEquals(original.hashCode(), candidate.hashCode())
            assertFalse(original.hasSameDirectProofAs(candidate))
            assertFalse(candidate.hasSameDirectProofAs(original))
            assertFalse(stack(original).hasSameDirectProofAs(stack(candidate)))
        }
    }
}

private class DirectFrameProofCase(generation: Long = 1) {
    val host = flowEndpoint("invoke", 0, 200, generation = generation) as RelationEndpoint.Resolved
    val callable =
        flowEndpoint("Mapper.map", 300, 400, parameters = listOf("String"), generation = generation)
            as RelationEndpoint.Resolved

    fun frame(
        enclosing: RelationEndpoint = host,
        callable: RelationEndpoint = this.callable,
        execution: ValueExecutionOwner = enclosing.valueExecutionOwner,
        callee: ValueExecutionOwner = callable.valueExecutionOwner,
        range: io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange = flowRange(20, 50),
    ): ValueCallFrame =
        ValueCallFrame.direct(ValueInvocation.fromCompiler(enclosing, range, callable, execution).flowRefined(), callee)
            .flowRefined()
}

private fun copiedEndpoint(
    original: RelationEndpoint.Resolved,
    scope: SymbolSearchScope = original.scope,
    constraints: SymbolDiscoveryConstraints = original.constraints,
): RelationEndpoint.Resolved =
    RelationEndpoint.resolve(original.lease, scope, original.evidence, constraints).flowRefined()
        as RelationEndpoint.Resolved

private fun stack(frame: ValueCallFrame) = ValueCallStack.Empty.enter(frame).flowRefined()

private fun implementationFrame(): ValueCallFrame {
    val f = DirectFrameProofCase()
    val concrete = flowEndpoint("ConcreteMapper.map", 500, 600, parameters = listOf("String"))
    val request = RelationRequest.start(f.callable, RelationMeaning.Implementations, implementationBudget())
    val fact =
        RelationFact.create(
                request,
                concrete,
                f.callable,
                RelationOccurrence.fromSource(concrete.file, concrete.range),
                RelationProvenance.K2_AUTHORED_SOURCE,
            )
            .flowRefined()
    val compilation = RelationCompilation.complete(implementationBatch(request, listOf(fact)))
    val dispatch = ValueImplementationDispatch.fromCompiler(compilation).flowRefined()
    val ownership = ValueSourceOwnership.fromCompiler(concrete, directProofRoot()).flowRefined()
    return ValueCallFrame.implementation(f.frame().invocation as ValueInvocation, dispatch, fact, ownership)
        .flowRefined()
}

private fun directProofRoot(): ModelOwnedSourceRoot {
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
                    sourceSetName = "main",
                    sourceRoot = path,
                    sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                    provenance = WorkspaceSourceRootProvenance.AUTHORED,
                )
            ),
        )
    return assertInstanceOf(WorkspaceSearchScopeModelCompilation.Compiled::class.java, compiled)
        .model
        .sourceRoots
        .single()
}
