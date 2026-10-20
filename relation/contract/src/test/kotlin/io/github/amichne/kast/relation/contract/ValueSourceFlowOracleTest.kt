package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueSourceFlowOracleTest {
    @Test
    fun `same typed request fields preserve separate declaration identity`() {
        val destination = flowField("Request.destination", 500, 520)
        val token = flowField("Request.sessionToken", 530, 550)
        assertEquals(
            (destination.declaration.signature as CanonicalCompilerSignature.Property).returnType,
            (token.declaration.signature as CanonicalCompilerSignature.Property).returnType,
        )
        val path = flowPath(ValuePathSegment.Field(destination))
        assertEquals(
            ValuePathSelection.Selected(ValueAccessPath.Whole),
            path.select(ValuePathSegment.Field(destination)),
        )
        assertEquals(ValuePathSelection.DifferentField, path.select(ValuePathSegment.Field(token)))
        assertNotEquals(destination.identity, token.identity)
    }

    @Test
    fun `eight field segments fit while the ninth rejects`() {
        val field = ValuePathSegment.Field(flowField("Response.link", 500, 520))
        val eight = List(8) { field }
        val admitted = ValueAccessPath.fromSegments(eight).flowRefined()
        assertEquals(8, admitted.depth)
        assertEquals(eight, admitted.segments)
        assertEquals(
            Refinement.Rejected(ValueAccessPathFailure.DEPTH_EXCEEDED),
            ValueAccessPath.fromSegments(eight + field),
        )
        assertEquals(
            Refinement.Rejected(ValueAccessPathFailure.DEPTH_EXCEEDED),
            admitted.prepend(field),
        )
    }

    @Test
    fun `foreign basis cannot enter a field path call frame or value state`() {
        val localField = flowField("Request.destination", 500, 520)
        val foreignField = flowField("Request.sessionToken", 530, 550, generation = 2)
        assertEquals(
            Refinement.Rejected(ValueAccessPathFailure.BASIS_MISMATCH),
            ValueAccessPath.fromSegments(
                listOf(ValuePathSegment.Field(localField), ValuePathSegment.Field(foreignField))
            ),
        )
        val local = callCase(20)
        val foreign = callCase(70, generation = 2)
        assertEquals(
            Refinement.Rejected(ValueCallBindingFailure.BASIS_MISMATCH),
            ValueCallFrame.direct(local.call, foreign.frame.callee),
        )
        val localSite = local.call.resultSite()
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.STATE_BASIS_MISMATCH),
            localSite.withState(valuePath = flowPath(ValuePathSegment.Field(foreignField))),
        )
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.STATE_BASIS_MISMATCH),
            localSite.withState(callStack = ValueCallStack.Empty.enter(foreign.frame).flowRefined()),
        )
    }

    @Test
    fun `two calls to one mapper cannot consume each others return`() {
        val first = callCase(20)
        val second = callCase(70)
        assertEquals(first.call.callable.valueIdentity, second.call.callable.valueIdentity)
        assertNotEquals(first.call.identity, second.call.identity)
        val stack = ValueCallStack.Empty.enter(first.frame).flowRefined()
        assertEquals(Refinement.Rejected(ValueCallBindingFailure.RETURN_MISMATCH), stack.exit(second.frame))
        val returned =
            ValueSite.fromCompiler(first.callee, flowRange(350, 360), ValueRole.Return, callStack = stack).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(
                returned,
                second.call.resultSite(),
                ValueTransferKind.SOURCE_CALL_RETURN,
                ValueSourceTransfer.fromCompiler(ValueSourceOperation.Return(first.frame)),
            ),
        )
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(
                returned,
                second.call.resultSite(),
                ValueTransferKind.SOURCE_CALL_RETURN,
                ValueSourceTransfer.fromCompiler(ValueSourceOperation.Return(second.frame)),
            ),
        )
        val matched =
            ValueTransfer.fromCompiler(
                    returned,
                    first.call.resultSite(),
                    ValueTransferKind.SOURCE_CALL_RETURN,
                    ValueSourceTransfer.fromCompiler(ValueSourceOperation.Return(first.frame)),
                )
                .flowRefined()
        assertEquals(ValueCallStack.Empty, matched.target.callStack)
        assertEquals(first.call.range, matched.target.range)
    }

    @Test
    fun `source entry preserves its field path and the exact entered invocation`() {
        val call = callCase(20)
        val path = flowPath(ValuePathSegment.Field(flowField("Request.destination", 500, 520)))
        val enteredStack = ValueCallStack.Empty.enter(call.frame).flowRefined()
        val actual = call.argument.withState(valuePath = path).flowRefined()
        val formal =
            ValueSite.fromCompiler(
                    call.callee,
                    call.formal.range,
                    ValueRole.Formal(call.position),
                    valuePath = path,
                    callStack = enteredStack,
                )
                .flowRefined()
        val evidence = ValueSourceTransfer.fromCompiler(ValueSourceOperation.Enter(call.frame, call.position))
        val entry =
            ValueTransfer.fromCompiler(actual, formal, ValueTransferKind.SOURCE_CALL_ENTRY, evidence).flowRefined()
        assertSame(path, entry.target.valuePath)
        assertSame(enteredStack, entry.target.callStack)
        assertEquals(listOf(call.frame), entry.target.callStack.frames)
    }

    @Test
    fun `source entry rejects a changed field or another entered invocation`() {
        val call = callCase(20)
        val path = flowPath(ValuePathSegment.Field(flowField("Request.destination", 500, 520)))
        val otherPath = flowPath(ValuePathSegment.Field(flowField("Request.sessionToken", 530, 550)))
        val actual = call.argument.withState(valuePath = path).flowRefined()
        val formal =
            ValueSite.fromCompiler(
                    call.callee,
                    call.formal.range,
                    ValueRole.Formal(call.position),
                    valuePath = path,
                    callStack = ValueCallStack.Empty.enter(call.frame).flowRefined(),
                )
                .flowRefined()
        val evidence = ValueSourceTransfer.fromCompiler(ValueSourceOperation.Enter(call.frame, call.position))
        rejectTransfer(
            actual,
            formal.withState(valuePath = otherPath).flowRefined(),
            ValueTransferKind.SOURCE_CALL_ENTRY,
            evidence,
        )
        rejectTransfer(
            actual,
            formal.withState(callStack = ValueCallStack.Empty).flowRefined(),
            ValueTransferKind.SOURCE_CALL_ENTRY,
            evidence,
        )
        val otherStack = ValueCallStack.Empty.enter(callCase(70).frame).flowRefined()
        rejectTransfer(
            actual,
            formal.withState(callStack = otherStack).flowRefined(),
            ValueTransferKind.SOURCE_CALL_ENTRY,
            evidence,
        )
    }

    @Test
    fun `source return preserves its field path and consumes exactly its own frame`() {
        val call = callCase(20)
        val path = flowPath(ValuePathSegment.Field(flowField("Request.destination", 500, 520)))
        val stack = ValueCallStack.Empty.enter(call.frame).flowRefined()
        val returned =
            ValueSite.fromCompiler(
                    call.callee,
                    flowRange(350, 360),
                    ValueRole.Return,
                    valuePath = path,
                    callStack = stack,
                )
                .flowRefined()
        val result = call.call.resultSite().withState(valuePath = path).flowRefined()
        val evidence = ValueSourceTransfer.fromCompiler(ValueSourceOperation.Return(call.frame))
        val exit =
            ValueTransfer.fromCompiler(returned, result, ValueTransferKind.SOURCE_CALL_RETURN, evidence).flowRefined()
        assertSame(path, exit.target.valuePath)
        assertEquals(ValueCallStack.Empty, exit.target.callStack)
    }

    @Test
    fun `source return rejects a changed field or unconsumed frame`() {
        val call = callCase(20)
        val path = flowPath(ValuePathSegment.Field(flowField("Request.destination", 500, 520)))
        val otherPath = flowPath(ValuePathSegment.Field(flowField("Request.sessionToken", 530, 550)))
        val stack = ValueCallStack.Empty.enter(call.frame).flowRefined()
        val returned =
            ValueSite.fromCompiler(
                    call.callee,
                    flowRange(350, 360),
                    ValueRole.Return,
                    valuePath = path,
                    callStack = stack,
                )
                .flowRefined()
        val result = call.call.resultSite().withState(valuePath = path).flowRefined()
        val evidence = ValueSourceTransfer.fromCompiler(ValueSourceOperation.Return(call.frame))
        rejectTransfer(
            returned,
            result.withState(valuePath = otherPath).flowRefined(),
            ValueTransferKind.SOURCE_CALL_RETURN,
            evidence,
        )
        rejectTransfer(
            returned,
            result.withState(callStack = stack).flowRefined(),
            ValueTransferKind.SOURCE_CALL_RETURN,
            evidence,
        )
    }

    @Test
    fun `direct occurrence evidence cannot forge source call entry or return`() {
        val call = callCase(20)
        val formal =
            ValueSite.fromCompiler(
                    call.callee,
                    call.formal.range,
                    ValueRole.Formal(call.position),
                )
                .flowRefined()
        val returned = ValueSite.fromCompiler(call.callee, flowRange(350, 360), ValueRole.Return).flowRefined()
        assertAll(
            { rejectTransfer(call.argument, formal, ValueTransferKind.SOURCE_CALL_ENTRY) },
            { rejectTransfer(returned, call.call.resultSite(), ValueTransferKind.SOURCE_CALL_RETURN) },
        )
    }

    @Test
    fun `direct occurrence evidence cannot forge field reads or constructor storage`() {
        val call = callCase(20)
        val read = ValueSite.fromCompiler(call.caller, flowRange(5, 10), ValueRole.LocalRead).flowRefined()
        val projected = ValueSite.fromCompiler(call.caller, flowRange(10, 15), ValueRole.ExpressionResult).flowRefined()
        assertAll(
            { rejectTransfer(read, projected, ValueTransferKind.FIELD_READ) },
            { rejectTransfer(call.argument, call.call.resultSite(), ValueTransferKind.CONSTRUCTOR_FIELD) },
        )
        val binding = ValueSite.fromCompiler(call.caller, flowRange(0, 5), ValueRole.LocalBinding).flowRefined()
        assertEquals(
            ValueTransferKind.LOCAL_READ,
            ValueTransfer.fromCompiler(binding, read, ValueTransferKind.LOCAL_READ).flowRefined().kind,
        )
    }

    private fun rejectTransfer(
        source: ValueSite,
        target: ValueSite,
        kind: ValueTransferKind,
        evidence: ValueTransferEvidence = ValueTransferEvidence.Direct,
    ) {
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(source, target, kind, evidence),
        )
    }

    private fun callCase(start: Int, generation: Long = 1): CallCase {
        val caller = flowEndpoint("investigate", 0, 200, generation = generation)
        val callee = flowEndpoint("Mapper.map", 300, 400, parameters = listOf("String"), generation = generation)
        val call = ValueInvocation.fromCompiler(caller, flowRange(start, start + 30), callee).flowRefined()
        val index = ValueArgumentPosition.parse(0).flowRefined()
        val formal =
            ValueFormalParameter.fromCompiler(
                    callee,
                    index,
                    SymbolDiscoveryCandidateName.parse("request").flowRefined(),
                    flowRange(315, 320),
                )
                .flowRefined()
        val frame = ValueCallFrame.direct(call, ValueExecutionOwner.declaration(callee)).flowRefined()
        val argument =
            ValueSite.fromCompiler(caller, flowRange(start + 10, start + 20), ValueRole.Argument(call, index))
                .flowRefined()
        return CallCase(caller, callee, call, frame, formal, ValueFormalPosition.Parameter(formal), argument)
    }

    private data class CallCase(
        val caller: RelationEndpoint,
        val callee: RelationEndpoint,
        val call: ValueInvocation,
        val frame: ValueCallFrame,
        val formal: ValueFormalParameter,
        val position: ValueFormalPosition.Parameter,
        val argument: ValueSite,
    )
}

internal fun flowField(name: String, start: Int, end: Int, generation: Long = 1): ValueField =
    ValueField.fromCompiler(flowEndpoint(name, start, end, generation = generation, propertyType = "String"))
        .flowRefined()

internal fun flowPath(vararg segments: ValuePathSegment): ValueAccessPath =
    ValueAccessPath.fromSegments(segments.toList()).flowRefined()

internal fun flowEndpoint(
    name: String,
    start: Int,
    end: Int,
    parameters: List<String> = emptyList(),
    generation: Long = 1,
    propertyType: String? = null,
): RelationEndpoint {
    val lease =
        SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/fixture")).flowRefined(),
            EvidenceGeneration.parse(generation).flowRefined(),
        )
    val displayName = name.substringAfterLast('.')
    val file =
        (SymbolDiscoveryCandidate.fromBoundary(
                    SymbolDiscoveryKind.SYMBOL,
                    displayName,
                    lease,
                    Path.of("/fixture/SourceFlow.kt"),
                    "file:///fixture/SourceFlow.kt",
                    start,
                )
                .flowRefined()
                .location as SymbolDiscoveryCandidateLocation.Declaration)
            .file
    val signature =
        if (propertyType == null) {
            CanonicalCompilerSignature.function("fixture.$name", null, emptyList(), parameters, 0).flowRefined()
        } else {
            CanonicalCompilerSignature.property("fixture.$name", null, emptyList(), propertyType).flowRefined()
        }
    val evidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
                file,
                start,
                end,
                displayName,
                "fixture.$name",
                if (propertyType == null) CompilerSymbolKind.FUNCTION else CompilerSymbolKind.PROPERTY,
                signature,
            )
            .flowRefined()
    return RelationEndpoint.resolve(
            lease,
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            ),
            evidence,
        )
        .flowRefined()
}

internal fun flowRange(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).flowRefined()

internal fun <V, F> Refinement<V, F>.flowRefined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Fixture fact rejected: $failure")
    }
