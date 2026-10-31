package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.rpc.ToolRpcReply
import io.github.amichne.kast.cli.rpc.presentToolRpcExit
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.QueryExecutionRejectionDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.CanonicalQueryCliDocuments
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class PublicFailureRegressionTest {
    @Test
    fun `host admission deadline does not prove caller grant expiry`() {
        val reply = hostedFailure(HostedFailureFixture.ADMISSION_DEADLINE_EXCEEDED)
        assertInstanceOf(ToolRpcReply.RejectedDocument::class.java, reply)
        assertEquals(4, classifyPublicReply(reply, PublicElapsedGrant.EXPLICIT).code)
        assertEquals(4, classifyPublicReply(reply, PublicElapsedGrant.ABSENT).code)
    }

    @Test
    fun `explicit elapsed grant expiry before first result has exit 124`() {
        val reply = queryFailure(QueryExecutionRejectionDocument.INVOCATION_TIME_LIMIT)
        assertInstanceOf(ToolRpcReply.RejectedDocument::class.java, reply)
        assertEquals(124, classifyPublicReply(reply, PublicElapsedGrant.EXPLICIT).code)
        assertEquals(10, classifyPublicReply(reply, PublicElapsedGrant.ABSENT).code)
    }

    @Test
    fun `explicit elapsed grant does not relabel an unrelated query rejection`() {
        val reply = queryFailure(QueryExecutionRejectionDocument.INVOCATION_WORK_LIMIT)
        assertInstanceOf(ToolRpcReply.RejectedDocument::class.java, reply)
        assertEquals(10, classifyPublicReply(reply, PublicElapsedGrant.EXPLICIT).code)
        assertEquals(10, classifyPublicReply(reply, PublicElapsedGrant.ABSENT).code)
    }

    @Test
    fun `caller timeout before operation admission retains elapsed policy`() {
        val document = CanonicalJsonDocument.generated(CallerTimeoutFixture.serializer()).create(CallerTimeoutFixture())
        val reply = presentToolRpcExit("query_symbols", CliExit.OperationRejected(document))
        assertInstanceOf(ToolRpcReply.RejectedDocument::class.java, reply)
        assertEquals(124, classifyPublicReply(reply, PublicElapsedGrant.EXPLICIT).code)
        assertEquals(10, classifyPublicReply(reply, PublicElapsedGrant.ABSENT).code)
    }

    @Test
    fun `elapsed classification requires both budget failure and caller timeout cause`() {
        val cases =
            listOf(
                CallerTimeoutFixture(failure = "PLATFORM_FAILURE"),
                CallerTimeoutFixture(detail = CallerTimeoutDetailFixture("RUNTIME")),
            )
        for (fixture in cases) {
            val document = CanonicalJsonDocument.generated(CallerTimeoutFixture.serializer()).create(fixture)
            val reply = presentToolRpcExit("query_symbols", CliExit.OperationRejected(document))
            assertEquals(6, classifyPublicReply(reply, PublicElapsedGrant.EXPLICIT).code)
        }
    }

    @Test
    fun `admitted hosted rejections preserve public input root transport and protocol categories`() {
        val cases =
            listOf(
                HostedFailureFixture.INVALID_REQUEST to 2,
                HostedFailureFixture.WRONG_ROOT to 3,
                HostedFailureFixture.PLATFORM_UNAVAILABLE to 4,
                HostedFailureFixture.IO_UNAVAILABLE to 5,
                HostedFailureFixture.RESPONSE_REJECTED to 6,
            )
        for ((failure, expected) in cases) {
            val reply = hostedFailure(failure)
            assertInstanceOf(ToolRpcReply.RejectedDocument::class.java, reply)
            assertEquals(expected, classifyPublicReply(reply).code, failure.name)
        }
    }
}

private fun queryFailure(reason: QueryExecutionRejectionDocument): ToolRpcReply {
    val outcome = OperationOutcome.Rejected(QueryRunRejection.ExecutionRejected(reason))
    val projected = CanonicalQueryCliDocuments.project(outcome) as ProjectedOperationOutcome.Rejected
    return presentToolRpcExit("query_symbols", CliExit.OperationRejected(projected.document))
}

private fun hostedFailure(failure: HostedFailureFixture): ToolRpcReply {
    val document =
        CanonicalJsonDocument.generated(HostedRejectionFixture.serializer()).create(HostedRejectionFixture(failure))
    return presentToolRpcExit("query_symbols", CliExit.OperationRejected(document))
}

@Serializable
private data class HostedRejectionFixture(
    val failure: HostedFailureFixture,
    @Required val type: String = "HOST_REJECTED",
)

@Serializable
private enum class HostedFailureFixture {
    ADMISSION_DEADLINE_EXCEEDED,
    INVALID_REQUEST,
    WRONG_ROOT,
    PLATFORM_UNAVAILABLE,
    IO_UNAVAILABLE,
    RESPONSE_REJECTED,
}

/** The real host's pre-operation rejection has no operation or HOST_REJECTED discriminator. */
@Serializable
private data class CallerTimeoutFixture(
    @Required val failure: String = "BUDGET_EXCEEDED",
    @Required val detail: CallerTimeoutDetailFixture = CallerTimeoutDetailFixture(),
    @Required val stage: String = "REQUEST_ADMISSION",
    @Required val schemaVersion: Int = 1,
    @Required val outcome: String = "rejected",
)

@Serializable private data class CallerTimeoutDetailFixture(@Required val cause: String = "CALLER_TIMEOUT")
