package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.wire.presentation.OperationPreparation
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class ExistingIdeMutationTest {
    private val plan = "plan:" + "a".repeat(64)
    private val request =
        assertInstanceOf<OperationPreparation.Prepared>(
                canonicalCliRequestPreparers()
                    .changeApply
                    .prepare(ChangeApplyRequest(refined(ProtocolText.parse(plan))))
            )
            .request

    @Test
    fun `mutation retains exact canonical plan and encodes no credential`() {
        val operation =
            refined(
                ExistingIdeOperation.Mutation.admit(
                    request,
                    HostedMutationOperation.CHANGE_APPLY,
                    refined(HostedPlanIdentity.parse(plan)),
                )
            )
        val encoded = operation.encodeControlRequest(CanonicalRoot(Path.of("/workspace")))
        val fields = Json.parseToJsonElement(encoded.toString(Charsets.UTF_8)).jsonObject
        assertEquals(setOf("root", "type", "document"), fields.keys)
        assertEquals("CHANGE_APPLY", fields.getValue("type").jsonPrimitive.content)
        assertEquals(request.document, fields.getValue("document").jsonPrimitive.content)
        assertInstanceOf<Refinement.Refined<*>>(
            ExistingIdeDocuments.readSchema(encoded, "/ide-hosted/hosted-request.schema.json")
        )
    }

    @Test
    fun `another plan or effect rejects before an exchange`() {
        assertEquals(
            Refinement.Rejected(ExistingIdeFailure.INVALID_REQUEST),
            ExistingIdeOperation.Mutation.admit(
                request,
                HostedMutationOperation.CHANGE_APPLY,
                refined(HostedPlanIdentity.parse("plan:" + "b".repeat(64))),
            ),
        )
        assertEquals(
            Refinement.Rejected(ExistingIdeFailure.OPERATION_UNSUPPORTED),
            ExistingIdeOperation.Mutation.admit(
                request,
                HostedMutationOperation.CHANGE_RECOVER,
                refined(HostedPlanIdentity.parse(plan)),
            ),
        )
    }

    private fun <T, F> refined(value: Refinement<T, F>): T = assertInstanceOf<Refinement.Refined<T>>(value).value
}
