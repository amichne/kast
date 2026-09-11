package io.github.amichne.kast.appserver.acceptance.hostedchange

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A distinct JVM loads the staged production broker and only retrieves the exact prior plan through its real gate. */
object NativeBrokerRestartMain {
    @JvmStatic
    fun main(arguments: Array<String>): Unit = runBlocking {
        val inputs = NativeHostedChangeInputs.admit(arguments)
        val request = Json.parseToJsonElement(readln()).jsonObject
        val identity = request.textAt("planIdentity")
        demand(identity.matches(Regex("plan:[0-9a-f]{64}")), NativeFailure.INPUT_REJECTED)
        val source = inputs.workspace.resolve("src/main/kotlin/Fixture.kt")
        val before = Files.readAllBytes(source)
        demand(sha256(before) == request.textAt("sourceSha256"), NativeFailure.SOURCE_CHANGED)
        val session =
            NativeChangeSession.open(
                product = inputs.product,
                workspace = inputs.workspace,
                home = Path.of(System.getProperty("user.home")).toRealPath(),
                schemas = inputs.schemas,
                privateDirectory = inputs.privateDirectory,
            )
        try {
            withTimeout(180_000) { retrieve(session, identity, source, before) }
        } finally {
            session.close()
        }
    }

    private suspend fun retrieve(session: NativeChangeSession, identity: String, source: Path, before: ByteArray) {
        val result = session.connect().call("change_apply", buildJsonObject { put("planIdentity", identity) })
        demand(
            !result.rejected() &&
                result.document()["state"] in
                    setOf(
                        JsonPrimitive("verified"),
                        JsonPrimitive("applied_unverified"),
                        JsonPrimitive("recovery_required"),
                    ),
            NativeFailure.RESULT_SHAPE_REJECTED,
        )
        val after = Files.readAllBytes(source)
        demand(after.contentEquals(before), NativeFailure.SOURCE_CHANGED)
        println(
            buildJsonObject {
                put("outcome", "PASSED")
                put(
                    "evidence",
                    buildJsonObject {
                        put("sourcePreimageSha256", sha256(before))
                        put("sourcePostimageSha256", sha256(after))
                        put("retrievedState", result.document().getValue("state"))
                        if (result.document()["state"] == JsonPrimitive("verified")) {
                            put(
                                "receiptIdentitySha256",
                                sha256(result.document().textAt("receiptIdentity").toByteArray()),
                            )
                        }
                    },
                )
            }
        )
    }
}
