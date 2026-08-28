package io.github.amichne.kast.runtime.ide.read.revalidation.dispatch

import io.github.amichne.kast.kernel.EvidenceEnvelope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.OperationId
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SchemaIdentity
import io.github.amichne.kast.protocol.contract.SymbolDescribeRejection
import io.github.amichne.kast.protocol.contract.SymbolDescribeRequest
import io.github.amichne.kast.protocol.contract.SymbolDescribeResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverLimitation
import io.github.amichne.kast.protocol.contract.SymbolDiscoverQualification
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRejection
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverResult
import io.github.amichne.kast.protocol.contract.SymbolDiscoverTargetDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryKindDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.SymbolNameKindDocument
import io.github.amichne.kast.protocol.contract.SymbolResolveRejection
import io.github.amichne.kast.protocol.contract.SymbolResolveRequest
import io.github.amichne.kast.protocol.contract.SymbolResolveResult
import io.github.amichne.kast.protocol.contract.WorkspaceInspectQualification
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRejection
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRequest
import io.github.amichne.kast.protocol.contract.WorkspaceInspectResult
import io.github.amichne.kast.protocol.contract.WorkspaceStateDocument
import io.github.amichne.kast.protocol.wire.OperationWireBinding
import io.github.amichne.kast.protocol.wire.WireDecoding
import io.github.amichne.kast.protocol.wire.WireEncoding
import io.github.amichne.kast.runtime.ide.read.dispatch.IdeReadRuntimeDispatch
import io.github.amichne.kast.runtime.ide.read.dispatch.IdeReadRuntimeDispatchResult
import io.github.amichne.kast.runtime.ide.read.dispatch.SymbolDescribeReadPort
import io.github.amichne.kast.runtime.ide.read.dispatch.SymbolDiscoverReadPort
import io.github.amichne.kast.runtime.ide.read.dispatch.SymbolResolveReadPort
import io.github.amichne.kast.runtime.ide.read.dispatch.WorkspaceInspectReadPort
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

internal data class IdeReadPortCalls(
    var workspaceInspect: Int = 0,
    var symbolDiscover: Int = 0,
    var symbolResolve: Int = 0,
    var symbolDescribe: Int = 0,
)

internal class RecordingIdeReadPorts(
    workspaceOutcome: OperationOutcome<
        WorkspaceInspectResult,
        WorkspaceInspectQualification,
        WorkspaceInspectRejection,
        > = OperationOutcome.Rejected(WorkspaceInspectRejection.RUNTIME_BLOCKED),
    discoverOutcome: OperationOutcome<
        SymbolDiscoverResult,
        SymbolDiscoverQualification,
        SymbolDiscoverRejection,
        > = OperationOutcome.Rejected(SymbolDiscoverRejection.WORKSPACE_NOT_READY),
    resolveOutcome: OperationOutcome<
        SymbolResolveResult,
        Nothing,
        SymbolResolveRejection,
        > = OperationOutcome.Rejected(SymbolResolveRejection.WORKSPACE_NOT_READY),
    describeOutcome: OperationOutcome<
        SymbolDescribeResult,
        Nothing,
        SymbolDescribeRejection,
        > = OperationOutcome.Rejected(SymbolDescribeRejection.WORKSPACE_NOT_READY),
) {
    val calls = IdeReadPortCalls()
    val workspaceRequests = mutableListOf<WorkspaceInspectRequest>()
    val discoverRequests = mutableListOf<SymbolDiscoverRequest>()
    val resolveRequests = mutableListOf<SymbolResolveRequest>()
    val describeRequests = mutableListOf<SymbolDescribeRequest>()
    val dispatch = IdeReadRuntimeDispatch(
        workspaceInspect = WorkspaceInspectReadPort { request ->
            calls.workspaceInspect += 1
            workspaceRequests += request
            workspaceOutcome
        },
        symbolDiscover = SymbolDiscoverReadPort { request ->
            calls.symbolDiscover += 1
            discoverRequests += request
            discoverOutcome
        },
        symbolResolve = SymbolResolveReadPort { request ->
            calls.symbolResolve += 1
            resolveRequests += request
            resolveOutcome
        },
        symbolDescribe = SymbolDescribeReadPort { request ->
            calls.symbolDescribe += 1
            describeRequests += request
            describeOutcome
        },
    )
}

internal fun workspaceRequest(): WorkspaceInspectRequest = WorkspaceInspectRequest

internal fun discoverRequest(): SymbolDiscoverRequest = SymbolDiscoverRequest(
    target = SymbolDiscoverTargetDocument.Name(
        query = protocolText("Widget"),
        kind = SymbolNameKindDocument.CLASS,
        match = SymbolDiscoveryMatchDocument.EXACT_NAME,
    ),
    limit = ProtocolCount.parse(25).refinedValue(),
)

internal fun resolveRequest(): SymbolResolveRequest = SymbolResolveRequest(
    candidateSelector = protocolText("candidate:Widget"),
)

internal fun describeRequest(): SymbolDescribeRequest = SymbolDescribeRequest(
    exactSelector = protocolText("exact:Widget"),
)

internal fun workspaceComplete(): OperationOutcome<
    WorkspaceInspectResult,
    WorkspaceInspectQualification,
    WorkspaceInspectRejection,
    > = OperationOutcome.Complete(
    evidence(
        CanonicalOperation.WORKSPACE_INSPECT,
        WorkspaceInspectResult(protocolText("/workspace"), WorkspaceStateDocument.READY),
    ),
)

internal fun discoverQualified(): OperationOutcome<
    SymbolDiscoverResult,
    SymbolDiscoverQualification,
    SymbolDiscoverRejection,
    > = OperationOutcome.Qualified(
    evidence(
        CanonicalOperation.SYMBOL_DISCOVER,
        SymbolDiscoverResult(
            BoundedProtocolList.create(
                listOf<SymbolDiscoveryDocument>(
                    SymbolDiscoveryDocument.Declaration(
                        candidateSelector = protocolText("candidate:Widget"),
                        kind = SymbolDiscoveryKindDocument.CLASS,
                        name = protocolText("Widget"),
                        file = protocolText("src/Widget.kt"),
                        offset = io.github.amichne.kast.protocol.contract.ProtocolOffset.parse(7)
                            .refinedValue(),
                    ),
                ),
            ).refinedValue(),
        ),
    ),
    SymbolDiscoverQualification.from(setOf(SymbolDiscoverLimitation.RESULT_LIMIT)).refinedValue(),
)

internal fun resolveComplete(): OperationOutcome<
    SymbolResolveResult,
    Nothing,
    SymbolResolveRejection,
    > = OperationOutcome.Complete(
    evidence(
        CanonicalOperation.SYMBOL_RESOLVE,
        SymbolResolveResult(protocolText("exact:Widget")),
    ),
)

internal fun describeRejected(): OperationOutcome<
    SymbolDescribeResult,
    Nothing,
    SymbolDescribeRejection,
    > = OperationOutcome.Rejected(SymbolDescribeRejection.SELECTOR_STALE)

internal fun protocolText(raw: String): ProtocolText = ProtocolText.parse(raw).refinedValue()

internal fun operationId(raw: String): OperationId = OperationId.parse(raw).refinedValue()

internal fun schemaIdentity(raw: String): SchemaIdentity = SchemaIdentity.parse(raw).refinedValue()

internal fun <Payload> evidence(
    operation: CanonicalOperation,
    payload: Payload,
): EvidenceEnvelope<Payload> = EvidenceEnvelope(
    operation = operation.id,
    generation = EvidenceGeneration.parse(23).refinedValue(),
    payload = payload,
)

internal fun <
    Request : io.github.amichne.kast.protocol.contract.OperationRequest,
    Result : io.github.amichne.kast.protocol.contract.OperationResult,
    Qualification : io.github.amichne.kast.protocol.contract.OperationQualification,
    Rejection : io.github.amichne.kast.protocol.contract.OperationRejection,
    > OperationWireBinding<Request, Result, Qualification, Rejection>.requestDocument(
    request: Request,
): String = when (val encoding = encodeRequest(request)) {
    is WireEncoding.Encoded -> encoding.document
    is WireEncoding.Rejected -> error("Expected request encoding, got ${encoding.failure}")
}

internal fun IdeReadRuntimeDispatchResult.responseDocument(): String = when (this) {
    is IdeReadRuntimeDispatchResult.Responded -> document
    is IdeReadRuntimeDispatchResult.Rejected -> error("Expected response, got $failure")
}

internal fun <Value> WireDecoding<Value>.decodedValue(): Value = when (this) {
    is WireDecoding.Decoded -> value
    is WireDecoding.Rejected -> error("Expected decoded value, got $failure")
}

internal fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error("Expected refined value, got $failure")
}

internal fun <Value> runSuspend(block: suspend () -> Value): Value {
    var completion: Result<Value>? = null
    block.startCoroutine(
        object : Continuation<Value> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<Value>) {
                completion = result
            }
        },
    )
    return checkNotNull(completion) { "Expected synchronous coroutine completion" }.getOrThrow()
}
