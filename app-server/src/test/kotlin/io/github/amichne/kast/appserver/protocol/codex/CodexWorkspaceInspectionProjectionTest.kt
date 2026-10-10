package io.github.amichne.kast.appserver.protocol.codex

import io.github.amichne.kast.appserver.runtime.WorkspaceExecutionIdentityDocument
import io.github.amichne.kast.appserver.runtime.WorkspaceExecutionLaneSnapshot
import io.github.amichne.kast.appserver.runtime.WorkspaceExecutionLaneState
import io.github.amichne.kast.appserver.runtime.WorkspaceExecutionNextAction
import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class CodexWorkspaceInspectionProjectionTest {
    @Test
    fun `inspection retains request envelope native contents and certainty without adding native authority`() {
        for (certainty in InvocationCertainty.entries) {
            for (success in listOf(true, false)) {
                for (id in listOf(JsonPrimitive("request-inspect"), JsonPrimitive(17))) {
                    val original = reply(id, success, certainty)
                    val augmented = refined(appendWorkspaceInspection(original, observation, 10_000))
                    assertEquals(certainty, augmented.certainty)
                    val before = Json.parseToJsonElement(original.message).jsonObject
                    val after = Json.parseToJsonElement(augmented.message).jsonObject
                    assertEquals(setOf("id", "result"), after.keys)
                    assertEquals(before.getValue("id"), after.getValue("id"))
                    val native = before.getValue("result").jsonObject
                    val projected = after.getValue("result").jsonObject
                    assertEquals(native.getValue("success"), projected.getValue("success"))
                    assertEquals(setOf("success", "contentItems"), projected.keys)
                    val items = projected.getValue("contentItems").jsonArray
                    assertEquals(native.getValue("contentItems").jsonArray.toList(), items.dropLast(1))
                    val localItem = items.last().jsonObject
                    assertEquals("inputText", localItem.getValue("type").jsonPrimitive.content)
                    val local = Json.parseToJsonElement(localItem.getValue("text").jsonPrimitive.content).jsonObject
                    assertEquals(setOf("workspaceExecution", "nativeAuthority"), local.keys)
                    assertEquals("not_observed_by_broker", local.getValue("nativeAuthority").jsonPrimitive.content)
                    val lane = local.getValue("workspaceExecution").jsonObject
                    assertEquals("recovery_required", lane.getValue("state").jsonPrimitive.content)
                    assertEquals("reconcile_uncertain_mutation", lane.getValue("nextAction").jsonPrimitive.content)
                    assertEquals(
                        "first-call",
                        lane.getValue("blockedBy").jsonObject.getValue("callId").jsonPrimitive.content,
                    )
                    assertEquals(observation.workspaceId, lane.getValue("workspaceId").jsonPrimitive.content)
                }
            }
        }
    }

    @Test
    fun `projection enforces exact encoded UTF8 envelope limit including escaped native content`() {
        val original = reply(JsonPrimitive("request-\"\\\n"), true, InvocationCertainty.UNCERTAIN)
        val augmented = refined(appendWorkspaceInspection(original, observation, 10_000))
        val bytes = augmented.message.toByteArray(Charsets.UTF_8).size
        assertTrue(bytes > augmented.message.length, "Unicode witness distinguishes bytes from character count")
        assertEquals(augmented, refined(appendWorkspaceInspection(original, observation, bytes)))
        val resultBytes =
            Json.parseToJsonElement(augmented.message)
                .jsonObject
                .getValue("result")
                .toString()
                .toByteArray(Charsets.UTF_8)
                .size
        assertEquals(augmented, refined(appendWorkspaceInspection(original, observation, bytes, resultBytes)))
        assertEquals(
            Refinement.Rejected(WorkspaceInspectionProjectionFailure.INSPECTION_OUTPUT_LIMIT),
            appendWorkspaceInspection(original, observation, bytes, resultBytes - 1),
        )
        assertEquals(
            Refinement.Rejected(WorkspaceInspectionProjectionFailure.INSPECTION_OUTPUT_LIMIT),
            appendWorkspaceInspection(original, observation, bytes - 1),
        )
        assertEquals(
            Refinement.Rejected(WorkspaceInspectionProjectionFailure.INSPECTION_OUTPUT_LIMIT),
            appendWorkspaceInspection(original, observation, 0),
        )
    }

    @Test
    fun `malformed missing and incompatible inspection envelopes reject without replacing native content`() {
        // These intentionally invalid documents test the strict decoding boundary.
        val malformed =
            listOf(
                "not-json",
                fixtureJson.encodeToString(emptyList<TextWitness>()),
                fixtureJson.encodeToString(EmptyEnvelopeWitness()),
                fixtureJson.encodeToString(MissingResultWitness(17)),
                fixtureJson.encodeToString(MissingIdWitness(ResultWitness(true, emptyList()))),
                fixtureJson.encodeToString(ArrayResultWitness(17, emptyList())),
                fixtureJson.encodeToString(MissingSuccessReplyWitness(17, MissingSuccessResultWitness(emptyList()))),
                fixtureJson.encodeToString(NullContentReplyWitness(17, NullContentResultWitness(true))),
                fixtureJson.encodeToString(
                    NullTextReplyWitness(17, NullTextResultWitness(true, listOf(NullTextWitness())))
                ),
                fixtureJson.encodeToString(UnknownFieldReplyWitness(17, ResultWitness(true, emptyList()))),
            )
        for (message in malformed) {
            val original = ProtocolRouting.ReplyUpstream(message, InvocationCertainty.UNCERTAIN)
            assertEquals(
                Refinement.Rejected(WorkspaceInspectionProjectionFailure.INSPECTION_REPLY_REJECTED),
                appendWorkspaceInspection(original, observation, 10_000),
                message,
            )
            assertEquals(message, original.message)
            assertEquals(InvocationCertainty.UNCERTAIN, original.certainty)
        }
    }

    private fun refined(result: Refinement<ProtocolRouting.ReplyUpstream, WorkspaceInspectionProjectionFailure>) =
        assertInstanceOf<Refinement.Refined<ProtocolRouting.ReplyUpstream>>(result).value

    private fun reply(id: JsonElement, success: Boolean, certainty: InvocationCertainty) =
        ProtocolRouting.ReplyUpstream(
            fixtureJson.encodeToString(
                ReplyWitness(
                    id,
                    ResultWitness(
                        success,
                        listOf(
                            TextWitness(fixtureJson.encodeToString(NativeWitness("incarnation-7", "epoch-13"))),
                            TextWitness("native quoted \"line\"\n\\tail 🏡"),
                        ),
                    ),
                )
            ),
            certainty,
        )

    @Serializable private data class ReplyWitness(val id: JsonElement, val result: ResultWitness)

    @Serializable private data class ResultWitness(val success: Boolean, val contentItems: List<TextWitness>)

    @Serializable private data class TextWitness(val text: String, val type: String = "inputText")

    @Serializable private data class NativeWitness(val projectIncarnation: String, val epoch: String)

    @Serializable private class EmptyEnvelopeWitness

    @Serializable private data class MissingResultWitness(val id: Int)

    @Serializable private data class MissingIdWitness(val result: ResultWitness)

    @Serializable private data class ArrayResultWitness(val id: Int, val result: List<TextWitness>)

    @Serializable private data class MissingSuccessReplyWitness(val id: Int, val result: MissingSuccessResultWitness)

    @Serializable private data class MissingSuccessResultWitness(val contentItems: List<TextWitness>)

    @Serializable private data class NullContentReplyWitness(val id: Int, val result: NullContentResultWitness)

    @Serializable
    private data class NullContentResultWitness(val success: Boolean, val contentItems: List<TextWitness>? = null)

    @Serializable private data class NullTextReplyWitness(val id: Int, val result: NullTextResultWitness)

    @Serializable
    private data class NullTextResultWitness(val success: Boolean, val contentItems: List<NullTextWitness>)

    @Serializable private data class NullTextWitness(val text: String? = null)

    @Serializable
    private data class UnknownFieldReplyWitness(val id: Int, val result: ResultWitness, val unknown: Boolean = true)

    private companion object {
        val fixtureJson = Json { encodeDefaults = true }
        val observation =
            WorkspaceExecutionLaneSnapshot(
                "a".repeat(64),
                WorkspaceExecutionLaneState.RECOVERY_REQUIRED,
                0,
                WorkspaceExecutionIdentityDocument("connection", "thread", "turn", "first-call"),
                WorkspaceExecutionNextAction.RECONCILE_UNCERTAIN_MUTATION,
            )
    }
}
