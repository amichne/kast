package io.github.amichne.kast.runtime.ide.read.dispatch

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.IdeHostCapability
import io.github.amichne.kast.protocol.contract.WorkspaceInspectRejection
import io.github.amichne.kast.protocol.wire.CanonicalOperationWireBindings
import io.github.amichne.kast.protocol.wire.WireBodyKind
import io.github.amichne.kast.protocol.wire.WireFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IdeReadRuntimeDispatchNegativeTest {
    @Test
    fun `admission decoding and encoding failures remain closed`() = runSuspend {
        val fixture = RecordingIdeReadPorts()
        assertEquals(
            IdeReadRuntimeDispatchResult.Rejected(
                IdeReadRuntimeDispatchFailure.RequestAdmissionFailed(WireFailure.MalformedEnvelope),
            ),
            fixture.dispatch.dispatch("{"),
        )

        val workspaceDocument = CanonicalOperationWireBindings.workspaceInspect
            .requestDocument(workspaceRequest())
        val unknownOperation = "symbol.missing"
        assertEquals(
            IdeReadRuntimeDispatchResult.Rejected(
                IdeReadRuntimeDispatchFailure.RequestAdmissionFailed(
                    WireFailure.UnknownOperation(operationId(unknownOperation)),
                ),
            ),
            fixture.dispatch.dispatch(
                workspaceDocument.replace("workspace.inspect", unknownOperation),
            ),
        )

        val outcomeDocument = CanonicalOperationWireBindings.workspaceInspect.encodeOutcome(
            workspaceComplete(),
        ).let { encoding ->
            when (encoding) {
                is io.github.amichne.kast.protocol.wire.WireEncoding.Encoded -> encoding.document
                is io.github.amichne.kast.protocol.wire.WireEncoding.Rejected ->
                    error("Expected outcome encoding, got ${encoding.failure}")
            }
        }
        assertEquals(
            IdeReadRuntimeDispatchResult.Rejected(
                IdeReadRuntimeDispatchFailure.RequestAdmissionFailed(
                    WireFailure.UnexpectedBody(
                        expected = setOf(WireBodyKind.REQUEST),
                        observed = WireBodyKind.COMPLETE,
                    ),
                ),
            ),
            fixture.dispatch.dispatch(outcomeDocument),
        )

        val unknownSchema = schemaIdentity("kast.unknown.v1")
        assertEquals(
            IdeReadRuntimeDispatchResult.Rejected(
                IdeReadRuntimeDispatchFailure.RequestDecodingFailed(
                    IdeHostCapability.WORKSPACE_INSPECT,
                    WireFailure.UnknownSchema(unknownSchema),
                ),
            ),
            fixture.dispatch.dispatch(
                workspaceDocument.replace(
                    CanonicalOperationWireBindings.workspaceInspect.schema.value,
                    unknownSchema.value,
                ),
            ),
        )
        assertEquals(IdeReadPortCalls(), fixture.calls)

        val wrongEvidence = RecordingIdeReadPorts(
            workspaceOutcome = OperationOutcome.Complete(
                evidence(
                    CanonicalOperation.SYMBOL_DISCOVER,
                    io.github.amichne.kast.protocol.contract.WorkspaceInspectResult(
                        protocolText("/workspace"),
                        io.github.amichne.kast.protocol.contract.WorkspaceStateDocument.READY,
                    ),
                ),
            ),
        )
        assertEquals(
            IdeReadRuntimeDispatchResult.Rejected(
                IdeReadRuntimeDispatchFailure.ResponseEncodingFailed(
                    IdeHostCapability.WORKSPACE_INSPECT,
                    WireFailure.UnexpectedOperation(
                        expected = CanonicalOperation.WORKSPACE_INSPECT,
                        observed = CanonicalOperation.SYMBOL_DISCOVER,
                    ),
                ),
            ),
            wrongEvidence.dispatch.dispatch(workspaceDocument),
        )
        assertEquals(IdeReadPortCalls(workspaceInspect = 1), wrongEvidence.calls)
    }

    @Test
    fun `all eight known unsupported operations reject before every port`() = runSuspend {
        val fixture = RecordingIdeReadPorts(
            workspaceOutcome = OperationOutcome.Rejected(WorkspaceInspectRejection.ROOT_UNAVAILABLE),
        )
        val workspaceDocument = CanonicalOperationWireBindings.workspaceInspect
            .requestDocument(workspaceRequest())
        val unsupported = CanonicalOperation.entries.filterNot { operation ->
            operation in IdeHostCapability.entries.map(IdeHostCapability::operation)
        }

        unsupported.forEach { operation ->
            assertEquals(
                IdeReadRuntimeDispatchResult.Rejected(
                    IdeReadRuntimeDispatchFailure.UnsupportedOperation(operation),
                ),
                fixture.dispatch.dispatch(
                    workspaceDocument.replace(
                        CanonicalOperation.WORKSPACE_INSPECT.id.value,
                        operation.id.value,
                    ),
                ),
            )
            assertEquals(IdeReadPortCalls(), fixture.calls)
        }
        assertEquals(8, unsupported.size)
    }
}
