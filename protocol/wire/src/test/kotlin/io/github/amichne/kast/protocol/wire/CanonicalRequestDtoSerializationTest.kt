package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.DiagnosticCheckRequest
import io.github.amichne.kast.protocol.contract.IndexSyncRequest
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.RelationReadRequest
import io.github.amichne.kast.protocol.contract.ProtocolCollectionConstraint
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolIntegerConstraint
import io.github.amichne.kast.protocol.contract.ProtocolStringConstraint
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolInspectRequest
import io.github.amichne.kast.protocol.contract.TopologyBuildRequest
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64

class CanonicalRequestDtoSerializationTest {
    @Test
    fun `all canonical requests own their wire serializer`() {
        val serializers = canonicalRequestSerializers()

        assertEquals(12, serializers.size)
        assertFalse(serializers.any { "WireDocument" in it.descriptor.serialName })
    }

    @Test
    fun `request deserialization rejects constrained primitives at parse time`() {
        assertThrows(SerializationException::class.java) {
            strictJson.decodeFromString(
                DiagnosticCheckRequest.serializer(),
                """{"path":"   ","limit":1}""",
            )
        }
        assertThrows(SerializationException::class.java) {
            strictJson.decodeFromString(
                DiagnosticCheckRequest.serializer(),
                """{"path":"src/main","limit":1001}""",
            )
        }
    }

    @Test
    fun `request deserialization rejects non-canonical aggregate syntax at parse time`() {
        assertThrows(SerializationException::class.java) {
            strictJson.decodeFromString(
                SourceReadRequest.serializer(),
                """
                    {
                      "anchor":{"type":"candidate","selector":"${selector("candidate")}"},
                      "region":{"type":"anchor"},
                      "entities":{"type":"matching","containment":"direct","filters":[]},
                      "text":{"type":"none"},
                      "entityLimit":1,
                      "textByteLimit":1,
                      "page":{"type":"first"}
                    }
                """.trimIndent(),
            )
        }
        assertThrows(SerializationException::class.java) {
            strictJson.decodeFromString(
                QueryRunRequest.serializer(),
                """
                    {
                      "from":{
                        "type":"symbols",
                        "match":{"type":"all"},
                        "scope":{
                          "sourceSets":["main","main"],
                          "directory":null,
                          "packageName":null
                        },
                        "declarationKinds":["class"]
                      },
                      "steps":[],
                      "output":{"type":"symbols","fields":["name"]},
                      "execution":{"kind":"exhaustive","budget":"interactive"}
                    }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `serializer descriptors retain the constraints enforced during parsing`() {
        val textConstraint = ProtocolText.serializer().descriptor.annotations
            .filterIsInstance<ProtocolStringConstraint>()
            .single()
        val countConstraint = ProtocolCount.serializer().descriptor.annotations
            .filterIsInstance<ProtocolIntegerConstraint>()
            .single()
        val collectionConstraint = QueryRunRequest.serializer().descriptor
            .getElementDescriptor(1)
            .annotations
            .filterIsInstance<ProtocolCollectionConstraint>()
            .single()

        assertEquals(1, textConstraint.minimumLength)
        assertEquals(1_048_576, textConstraint.maximumLength)
        assertTrue(textConstraint.pattern.isNotBlank())
        assertEquals(1, countConstraint.minimum)
        assertEquals(1_000, countConstraint.maximum)
        assertEquals(1_000, collectionConstraint.maximumItems)
    }

    private fun canonicalRequestSerializers(): List<KSerializer<*>> = listOf(
        IndexSyncRequest.serializer(),
        TopologyBuildRequest.serializer(),
        SymbolDiscoverRequest.serializer(),
        SymbolInspectRequest.serializer(),
        SourceReadRequest.serializer(),
        RelationReadRequest.serializer(),
        TraversalRunRequest.serializer(),
        QueryRunRequest.serializer(),
        DiagnosticCheckRequest.serializer(),
        ChangePlanRequest.serializer(),
        ChangeApplyRequest.serializer(),
        ChangeRecoverRequest.serializer(),
    )

    private fun selector(family: String): String {
        val payload = "{}".encodeToByteArray()
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return "$family:v2:$encoded:$digest"
    }

    private companion object {
        val strictJson = Json {
            classDiscriminator = "type"
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
        }
    }
}
