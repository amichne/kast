package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.cli.CanonicalRoot
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HostedApprovalChallengeTest {
    private val root = CanonicalRoot(Path.of("/workspace"))
    private val descriptor = ExistingIdeDescriptor(123, UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val operation =
        ExistingIdeOperation.ApprovalPreparation(
            HostedMutationOperation.CHANGE_APPLY,
            (HostedPlanIdentity.parse("plan:${"a".repeat(64)}") as Refinement.Refined).value,
        )

    private fun challenge() = buildJsonObject {
        put("version", 1)
        put("operation", "CHANGE_APPLY")
        put("root", "/workspace")
        put("host", descriptor.host.toString())
        put("planId", "a".repeat(64))
        put("challenge", "b".repeat(64))
        put(
            "preview",
            buildJsonObject {
                put("path", "src/Target.kt")
                put("diff", "@@\n+fun added() = Unit\n")
            },
        )
    }

    @Test
    fun `challenge requires exact plan owner root and operation`() {
        assertTrue(
            admitHostedApprovalChallenge(challenge().toString().toByteArray(), root, descriptor, operation)
                is Refinement.Refined
        )
        for ((key, value) in
            listOf(
                "root" to "/other",
                "host" to UUID.randomUUID().toString(),
                "planId" to "c".repeat(64),
                "operation" to "CHANGE_RECOVER",
                "challenge" to "invalid",
            )) {
            val changed = JsonObject(challenge() + (key to JsonPrimitive(value)))
            assertTrue(
                admitHostedApprovalChallenge(changed.toString().toByteArray(), root, descriptor, operation)
                    is Refinement.Rejected
            )
        }
    }

    @Test
    fun `unsafe preview and unknown response properties are rejected`() {
        for (path in listOf("../Target.kt", "/Target.kt", "src/../Target.kt")) {
            val changed =
                JsonObject(
                    challenge() +
                        ("preview" to
                            buildJsonObject {
                                put("path", path)
                                put("diff", "+x")
                            })
                )
            assertTrue(
                admitHostedApprovalChallenge(changed.toString().toByteArray(), root, descriptor, operation)
                    is Refinement.Rejected
            )
        }
        assertTrue(
            admitHostedApprovalChallenge(
                JsonObject(challenge() + ("extra" to JsonPrimitive(1))).toString().toByteArray(),
                root,
                descriptor,
                operation,
            )
                is Refinement.Rejected
        )
    }

    @Test
    fun `old descriptor protocol is unavailable even with otherwise valid metadata`() {
        val raw =
            """{"type":"KAST_IDE_ENDPOINT","protocol":2,"root":"/workspace","socket":"/tmp/host.sock","hostPid":123,"host":"${descriptor.host}","querySchema":"kast.query.run.v2","operations":["DESCRIBE","CLASS_LOOKUP","DIRECT_SUPERTYPE","QUERY_RUN","SYMBOL_DISCOVER","SYMBOL_INSPECT","SOURCE_READ","RELATION_READ","TRAVERSAL_RUN","DIAGNOSTIC_CHECK"]}"""
        assertEquals(
            Refinement.Rejected(ExistingIdeFailure.DESCRIPTOR_REJECTED),
            ExistingIdeDocuments.descriptor(raw.toByteArray(), root, Path.of("/tmp/host.sock")),
        )
    }
}
