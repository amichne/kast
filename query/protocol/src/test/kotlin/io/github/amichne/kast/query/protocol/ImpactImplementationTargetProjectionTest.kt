package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.ImpactCallFrameDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.admitsExecutionOwner
import io.github.amichne.kast.query.contract.QueryImpactPath
import io.github.amichne.kast.query.contract.QueryImpactRepresentation
import io.github.amichne.kast.query.contract.QueryImpactTerminal
import io.github.amichne.kast.relation.contract.RelationBatch
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteCount
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationResultCount
import io.github.amichne.kast.relation.contract.RelationWorkCount
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueCallStack
import io.github.amichne.kast.relation.contract.ValueFlowObligation
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalParameter
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueImplementationDispatch
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceOwnership
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactImplementationTargetProjectionTest {
    private val projectionReferences = CanonicalQueryReferences()

    @Test
    fun `implementation frame preserves the static call all targets and imported source owner`() {
        val f = ImplementationProjectionFixture()
        val frame = f.frame(RelationCompilation.complete(f.batch))
        assertTrue(f.formal(frame).impactDocument(references = projectionReferences).value().admitsExecutionOwner())
        val encoded =
            Json.encodeToJsonElement(
                    ImpactCallFrameDocument.serializer(),
                    frame.impactDocument(references = projectionReferences).value(),
                )
                .jsonObject
        val target = encoded.getValue("target").jsonObject
        assertEquals(setOf("type", "dispatch", "relation", "ownership"), target.keys)
        assertEquals("IMPLEMENTATION", target.getValue("type").jsonPrimitive.content)
        val dispatch = target.getValue("dispatch").jsonObject
        assertEquals(2, dispatch.getValue("facts").jsonArray.size)
        assertEquals(
            "implementations",
            dispatch.getValue("question").jsonObject.getValue("meaning").jsonPrimitive.content,
        )
        assertEquals("EXHAUSTED", dispatch.getValue("coverage").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("2", dispatch.getValue("coverage").jsonObject.getValue("exactCount").jsonPrimitive.content)
        val owner = target.getValue("ownership").jsonObject.getValue("root").jsonObject
        assertEquals("main", owner.getValue("sourceSet").jsonPrimitive.content)
        assertEquals("PRODUCTION", owner.getValue("sourceKind").jsonPrimitive.content)
        assertEquals("AUTHORED", owner.getValue("provenance").jsonPrimitive.content)
    }

    @Test
    fun `incomplete implementation coverage keeps its limit instead of claiming exhaustion`() {
        val f = ImplementationProjectionFixture()
        val read = RelationCompilation.qualifiedTerminal(f.batch, setOf(RelationLimitation.UNRESOLVED_TARGET)).value()
        val frame = f.frame(read)
        assertTrue(f.formal(frame).impactDocument(references = projectionReferences).value().admitsExecutionOwner())
        val encoded =
            Json.encodeToJsonElement(
                    ImpactCallFrameDocument.serializer(),
                    frame.impactDocument(references = projectionReferences).value(),
                )
                .jsonObject
        val coverage =
            encoded.getValue("target").jsonObject.getValue("dispatch").jsonObject.getValue("coverage").jsonObject
        assertEquals(setOf("type", "knownMinimum", "limitations"), coverage.keys)
        assertEquals("TERMINAL_INCOMPLETE", coverage.getValue("type").jsonPrimitive.content)
        assertEquals("2", coverage.getValue("knownMinimum").jsonPrimitive.content)
        assertEquals(
            listOf("UNRESOLVED_TARGET"),
            coverage.getValue("limitations").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `fresh work observations preserve matched return identity without erasing either observation`() {
        val f = ImplementationProjectionFixture()
        val entered = f.frame(RelationCompilation.complete(f.batch))
        val returned = f.freshFrame()
        assertEquals(entered, returned)
        val exit = f.exit(entered, returned).impactDocument(references = projectionReferences).value()
        assertTrue(exit.admitsEvidence())
        val encoded =
            Json.encodeToJsonElement(
                    ImpactCallFrameDocument.serializer(),
                    returned.impactDocument(references = projectionReferences).value(),
                )
                .jsonObject
        val dispatch = encoded.getValue("target").jsonObject.getValue("dispatch").jsonObject
        assertEquals("7", dispatch.getValue("examinedWorkUnits").jsonPrimitive.content)
    }

    @Test
    fun `zero-fact incomplete dispatch remains evidence on its unresolved path`() {
        val path = ImplementationProjectionFixture().unresolvedPath()
        val encoded =
            Json.encodeToJsonElement(
                    ImpactPathDocument.serializer(),
                    path.impactDocument(references = projectionReferences).value(),
                )
                .jsonObject
        val terminal = encoded.getValue("terminal").jsonObject
        assertTrue("evidence" in terminal, "Native dispatch evidence disappeared")
        val evidence = terminal.getValue("evidence").jsonObject
        assertEquals("IMPLEMENTATION_DISPATCH", evidence.getValue("type").jsonPrimitive.content)
        val dispatch = evidence.getValue("dispatch").jsonObject
        assertEquals(0, dispatch.getValue("facts").jsonArray.size)
        assertEquals(
            "TERMINAL_INCOMPLETE",
            dispatch.getValue("coverage").jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(
            listOf("UNRESOLVED_TARGET"),
            dispatch.getValue("coverage").jsonObject.getValue("limitations").jsonArray.map { it.jsonPrimitive.content },
        )
    }
}

private class ImplementationProjectionFixture {
    private val base = ImpactPathProjectionFixture()
    private val implementation = endpoint("implementation", 300)
    private val other = endpoint("other", 400)
    private val request = RelationRequest.start(base.call.callable, RelationMeaning.Implementations, budget())
    private val relation = fact(implementation)
    private val facts = listOf(relation, fact(other)).sorted()
    val batch = batch(2)

    private fun batch(work: Long) =
        RelationBatch.create(
                request,
                facts,
                RelationByteCount.parse(facts.sumOf { it.canonicalProjection().toByteArray().size.toLong() }).value(),
                RelationWorkCount.parse(work).value(),
                RelationResultCount.parse(2).value(),
            )
            .value()

    private val root =
        (WorkspaceSearchScopeModel.compile(
                base.lease.workspaceRoot,
                ImportedWorkspaceModelState.COMPLETE,
                listOf(
                    WorkspaceSourceRootBoundary(
                        "fixture",
                        Path.of(base.root),
                        ":",
                        "main",
                        Path.of(base.root),
                        WorkspaceSourceRootKind.PRODUCTION,
                        WorkspaceSourceRootProvenance.AUTHORED,
                    )
                ),
            ) as WorkspaceSearchScopeModelCompilation.Compiled)
            .model
            .sourceRoots
            .single()

    fun frame(read: RelationCompilation): ValueCallFrame =
        ValueCallFrame.implementation(
                base.call,
                ValueImplementationDispatch.fromCompiler(read).value(),
                relation,
                ValueSourceOwnership.fromCompiler(implementation, root).value(),
            )
            .value()

    fun freshFrame(): ValueCallFrame = frame(RelationCompilation.complete(batch(7)))

    fun unresolvedPath(): QueryImpactPath {
        val empty =
            RelationBatch.create(
                    request,
                    emptyList(),
                    RelationByteCount.parse(0).value(),
                    RelationWorkCount.parse(1).value(),
                    RelationResultCount.parse(0).value(),
                )
                .value()
        val read = RelationCompilation.qualifiedTerminal(empty, setOf(RelationLimitation.UNRESOLVED_TARGET)).value()
        val dispatch = ValueImplementationDispatch.fromCompiler(read).value()
        val site =
            ValueSite.fromCompiler(
                    base.owner,
                    range(31, 35),
                    ValueRole.Argument(base.call, ValueArgumentPosition.parse(0).value()),
                )
                .value()
        val obligation =
            ValueFlowObligation.fromImplementation(site, ValueFlowUnsupportedCause.UNMODELED_CALL, dispatch).value()
        return QueryImpactPath.fromEvidence(
                site,
                emptyList(),
                QueryImpactRepresentation.NotModeled,
                QueryImpactTerminal.Unresolved.Flow(obligation),
            )
            .value()
    }

    fun exit(entered: ValueCallFrame, returned: ValueCallFrame): ValueTransfer =
        ValueTransfer.fromCompiler(
                ValueSite.fromCompiler(
                        implementation,
                        range(340, 345),
                        ValueRole.Return,
                        callStack = ValueCallStack.Empty.enter(entered).value(),
                    )
                    .value(),
                base.call.resultSite(),
                ValueTransferKind.SOURCE_CALL_RETURN,
                ValueSourceTransfer.fromCompiler(ValueSourceOperation.Return(returned)),
            )
            .value()

    fun formal(frame: ValueCallFrame): ValueSite {
        val parameter =
            ValueFormalParameter.fromCompiler(
                    implementation,
                    ValueArgumentPosition.parse(0).value(),
                    SymbolDiscoveryCandidateName.parse("input").value(),
                    range(310, 315),
                )
                .value()
        return ValueSite.fromCompiler(
                implementation,
                parameter.range,
                ValueRole.Formal(ValueFormalPosition.Parameter(parameter)),
                callStack = ValueCallStack.Empty.enter(frame).value(),
            )
            .value()
    }

    private fun fact(source: RelationEndpoint) =
        RelationFact.create(
                request,
                source,
                base.call.callable,
                RelationOccurrence.fromSource(source.file, source.range),
                RelationProvenance.K2_AUTHORED_SOURCE,
            )
            .value()

    private fun endpoint(name: String, start: Int): RelationEndpoint =
        RelationEndpoint.resolve(
                base.lease,
                base.call.callable.scope,
                CompilerGroundedSymbolEvidence.fromBoundary(
                        base.call.callable.file,
                        start,
                        start + 50,
                        name,
                        "fixture.$name",
                        CompilerSymbolKind.FUNCTION,
                        CanonicalCompilerSignature.function("fixture.$name", null, emptyList(), listOf("String"), 0)
                            .value(),
                    )
                    .value(),
            )
            .value()
}

private fun budget() =
    RelationBudget(
        ResourceBudget(
            ResultLimit.parse(10).value(),
            WorkUnitLimit.parse(100).value(),
            ElapsedTimeLimitMillis.parse(1000).value(),
        ),
        RelationByteLimit.parse(1000000).value(),
    )

private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).value()

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
