package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.TraversalContinuationDocument
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals

/** The upstream checkpoint contract includes its legacy token projection and rejects unproven state. */
internal fun LiveReadOutputSchemaTest.assertUpstreamTraversalCheckpointContract(
    document: JsonObject,
    continuation: TraversalContinuationDocument,
) {
    val operation = CanonicalOperation.TRAVERSAL_RUN
    val qualification = document.getValue("qualification").jsonObject
    val checkpoint = qualification.getValue("checkpoint").jsonObject
    assertEquals(JsonPrimitive("upstream"), checkpoint["type"])
    assertEquals(JsonPrimitive(continuation.value), checkpoint["token"])
    assertEquals(checkpoint["token"], qualification["continuation"])
    assertEquals(JsonPrimitive("resume"), qualification["next_action"])
    assertRejects(operation, document.with("qualification", qualification.with("checkpoint", JsonNull)))
    assertRejects(
        operation,
        document.with("qualification", qualification.with("next_action", JsonPrimitive("unknown"))),
    )
    assertRejects(
        operation,
        document.with(
            "qualification",
            qualification.with("checkpoint", checkpoint.with("type", JsonPrimitive("unknown"))),
        ),
    )
    assertRejects(
        operation,
        document.with(
            "qualification",
            qualification.with("checkpoint", checkpoint.with("unproven", JsonPrimitive(true))),
        ),
    )
}
