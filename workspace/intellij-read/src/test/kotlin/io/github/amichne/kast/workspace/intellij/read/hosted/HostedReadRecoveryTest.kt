package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.intellij.read.ExistingProjectAdmissionFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostedReadRecoveryTest {
    @Test
    fun `publication rejection retains every exact cause without claiming source movement`() {
        for (cause in HostedPublicationFailureCause.entries) {
            val document =
                Json.parseToJsonElement(
                        HostedQueryWire.encode(
                            HostedQueryResult.Rejected(
                                HostedQueryFailure.Publication(cause),
                                HostedQueryStage.RESULT_DETACHED,
                            )
                        )
                    )
                    .jsonObject
            assertEquals("PUBLICATION_REJECTED", document.getValue("failure").jsonPrimitive.content)
            assertEquals(cause.name, document.getValue("detail").jsonObject.getValue("cause").jsonPrimitive.content)
            assertEquals("RESULT_DETACHED", document.getValue("stage").jsonPrimitive.content)
            val expected =
                when (cause) {
                    HostedPublicationFailureCause.EXPIRED,
                    HostedPublicationFailureCause.DEPENDENCY_UNAVAILABLE -> "retained_state_unavailable"
                    HostedPublicationFailureCause.CAPACITY_EXCEEDED -> "reduce_retained_work"
                    HostedPublicationFailureCause.OWNER_RETIRED,
                    HostedPublicationFailureCause.CLAIM_UNAVAILABLE,
                    HostedPublicationFailureCause.PUBLISHED_PAGE_MISMATCH,
                    HostedPublicationFailureCause.NON_ADVANCING_SUCCESSOR,
                    HostedPublicationFailureCause.INVALID_FITTED_PAGE -> "review_failure"
                }
            assertEquals(expected, document.getValue("recovery").jsonObject.getValue("kind").jsonPrimitive.content)
            val expectedInstruction =
                when (cause) {
                    HostedPublicationFailureCause.EXPIRED,
                    HostedPublicationFailureCause.DEPENDENCY_UNAVAILABLE ->
                        "The retained semantic execution state is no longer usable. Start a fresh read; this result did not publish a successor."
                    HostedPublicationFailureCause.CAPACITY_EXCEEDED ->
                        "The host could not retain this page and its unfinished work within the declared capacity. Inspect the retention limits and reduce the retained work before starting a fresh read."
                    else -> null
                }
            if (expectedInstruction != null) {
                assertEquals(
                    expectedInstruction,
                    document.getValue("recovery").jsonObject.getValue("instruction").jsonPrimitive.content,
                )
            }
        }
    }

    @Test
    fun `epoch movement reports restart read for both freshness and retained ownership rejection`() {
        val failures =
            listOf(
                HostedQueryFailure.Freshness(
                    io.github.amichne.kast.workspace.contract.VfsPassiveReadAdmissionFailure.Moved
                ),
                HostedQueryFailure.LiveAuthority(
                    io.github.amichne.kast.workspace.contract.LiveSemanticReadFailure.EPOCH_MOVED
                ),
            )
        failures.forEach { failure ->
            val document =
                Json.parseToJsonElement(
                        HostedQueryWire.encode(
                            HostedQueryResult.Rejected(failure, HostedQueryStage.CONTENT_REVALIDATION)
                        )
                    )
                    .jsonObject
            assertEquals(
                "restart_read",
                document.getValue("recovery").jsonObject.getValue("kind").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `capture timeout identifies the host preparation deadline`() {
        val document =
            Json.parseToJsonElement(
                    HostedQueryWire.encode(
                        HostedQueryResult.Rejected(HostedQueryFailure.BUDGET_EXCEEDED, HostedQueryStage.MODEL_CAPTURE)
                    )
                )
                .jsonObject
        val recovery = document.getValue("recovery").jsonObject
        assertEquals("increase_host_deadline", recovery.getValue("kind").jsonPrimitive.content)
        assertEquals("MODEL_CAPTURE", document.getValue("stage").jsonPrimitive.content)
    }

    @Test
    fun `queued timeout names the queue before semantic scope guidance`() {
        val document =
            Json.parseToJsonElement(
                    HostedQueryWire.encode(
                        HostedQueryResult.Rejected(
                            HostedQueryFailure.BUDGET_EXCEEDED,
                            HostedQueryStage.REQUEST_ADMISSION,
                        )
                    )
                )
                .jsonObject
        val recovery = document.getValue("recovery").jsonObject
        assertEquals("increase_host_deadline", recovery.getValue("kind").jsonPrimitive.content)
        assertEquals(
            "The host deadline expired before semantic work started. " +
                "Read admission, queue wait, project admission, or cached Gradle model capture " +
                "consumed the allowance. " +
                "Inspect the kast_semantic_read stages before you change the query scope. " +
                "Increase the host query, connection, and client exchange deadlines together " +
                "if this project needs more preparation time.",
            recovery.getValue("instruction").jsonPrimitive.content,
        )
    }

    @Test
    fun `caller capture timeout preserves provenance without inventing an admitted grant`() {
        val document =
            Json.parseToJsonElement(
                    HostedQueryWire.encode(
                        HostedQueryResult.Rejected(HostedQueryFailure.CALLER_TIMEOUT, HostedQueryStage.MODEL_CAPTURE)
                    )
                )
                .jsonObject
        assertEquals("BUDGET_EXCEEDED", document.getValue("failure").jsonPrimitive.content)
        assertEquals("CALLER_TIMEOUT", document.getValue("detail").jsonObject.getValue("cause").jsonPrimitive.content)
        assertEquals("MODEL_CAPTURE", document.getValue("stage").jsonPrimitive.content)
        assertEquals(false, document.containsKey("execution_budget"))
        val recovery = document.getValue("recovery").jsonObject
        assertEquals("increase_caller_timeout", recovery.getValue("kind").jsonPrimitive.content)
        assertEquals(
            "The explicitly requested maxElapsedMs expired, including admission, indexing waits, and model capture. " +
                "Increase or omit executionBudget.maxElapsedMs for a new RUN. " +
                "Host deadline settings do not extend this caller timeout.",
            recovery.getValue("instruction").jsonPrimitive.content,
        )
    }

    @Test
    fun `exhaustion and cancellation have different actionable recovery`() {
        for ((failure, expected) in
            listOf(
                HostedQueryFailure.BUDGET_EXCEEDED to "reduce_read_work",
                HostedQueryFailure.CANCELLED to "cancelled",
                HostedQueryFailure.DIRTY_DOCUMENTS to "save_source",
                HostedQueryFailure.BUSY to "wait_for_capacity",
            )) {
            val document =
                Json.parseToJsonElement(
                        HostedQueryWire.encode(HostedQueryResult.Rejected(failure, HostedQueryStage.SEMANTIC_READ))
                    )
                    .jsonObject
            assertEquals(expected, document.getValue("recovery").jsonObject.getValue("kind").jsonPrimitive.content)
        }
    }

    @Test
    fun `unavailable Gradle model retains rejection and points to observed readiness`() {
        val document =
            Json.parseToJsonElement(
                    HostedQueryWire.encode(
                        HostedQueryResult.Rejected(
                            HostedQueryFailure.ProjectAdmission(ExistingProjectAdmissionFailure.GradleModelUnavailable),
                            HostedQueryStage.PROJECT_ADMISSION,
                        )
                    )
                )
                .jsonObject
        assertEquals("rejected", document.getValue("outcome").jsonPrimitive.content)
        assertEquals("GRADLE_MODEL_UNAVAILABLE", document.getValue("detail").jsonPrimitive.content)
        val recovery = document.getValue("recovery").jsonObject
        assertEquals("after_state_change", recovery.getValue("kind").jsonPrimitive.content)
        assertEquals("IDE_STATUS", recovery.getValue("check").jsonPrimitive.content)
        assertEquals("CACHED_GRADLE_MODEL_COMPLETE", recovery.getValue("required").jsonPrimitive.content)
        assertEquals("OBSERVE_IDE_GRADLE_MODEL", recovery.getValue("remediation").jsonPrimitive.content)
    }
}
