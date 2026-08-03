package io.github.amichne.kast.api

import io.github.amichne.kast.api.contract.ExactByteImage
import io.github.amichne.kast.api.contract.RawExactFileObservationPath
import io.github.amichne.kast.api.contract.query.RawExactFileObservationQuery
import io.github.amichne.kast.api.contract.result.RawExactFileObservationResult
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.validation.parsed
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RawExactFileObservationContractTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `query parses one canonical workspace relative path`() {
        val parsed = RawExactFileObservationQuery(
            filePath = "src/main/kotlin/sample/App.kt",
        ).parsed()

        assertEquals(
            RawExactFileObservationPath.parse("src/main/kotlin/sample/App.kt"),
            parsed.filePath,
        )
    }

    @Test
    fun `query rejects every noncanonical workspace relative path`() {
        listOf(
            "",
            " ",
            "/workspace/App.kt",
            "C:/workspace/App.kt",
            "src\\main\\App.kt",
            "./src/App.kt",
            "src/../App.kt",
            "src//App.kt",
            "src/App.kt/",
            "src/\u0000App.kt",
        ).forEach { invalid ->
            assertThrows(
                ValidationException::class.java,
                { RawExactFileObservationQuery(invalid).parsed() },
                invalid,
            )
        }
    }

    @Test
    fun `query decoder rejects unknown fields`() {
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<RawExactFileObservationQuery>(
                """{"filePath":"src/App.kt","unexpected":true}""",
            )
        }
    }

    @Test
    fun `result round trips only absent or present`() {
        val path = RawExactFileObservationPath.parse("src/main/kotlin/sample/App.kt")
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "package sample\r\nval face = \"😀\"\r\n".toByteArray()
        val image = ExactByteImage.of(bytes)
        val results = listOf<RawExactFileObservationResult>(
            RawExactFileObservationResult.Absent(path),
            RawExactFileObservationResult.Present(path, image),
        )

        results.forEach { result ->
            val encoded = json.encodeToString(result)
            val decoded = json.decodeFromString<RawExactFileObservationResult>(encoded)

            assertEquals(result, decoded)
            assertTrue(encoded.contains("\"type\":\"${result::class.simpleName!!.uppercase()}\""))
        }

        val present = assertInstanceOf(RawExactFileObservationResult.Present::class.java, results.last())
        assertArrayEquals(bytes, present.image.copyBytes())
    }

    @Test
    fun `result rejects unknown variants and fields`() {
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<RawExactFileObservationResult>(
                """{"type":"UNKNOWN","filePath":"src/App.kt"}""",
            )
        }
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<RawExactFileObservationResult>(
                """{"type":"ABSENT","filePath":"src/App.kt","unexpected":true}""",
            )
        }
    }

    @Test
    fun `present result rejects inconsistent byte image data`() {
        val requested = RawExactFileObservationPath.parse("src/App.kt")
        val present = RawExactFileObservationResult.Present(
            filePath = requested,
            image = ExactByteImage.of("unchanged".toByteArray()),
        )
        val encoded = json.encodeToJsonElement(
            RawExactFileObservationResult.serializer(),
            present,
        ).jsonObject
        val image = encoded.getValue("image").jsonObject
        val inconsistent = JsonObject(
            encoded + ("image" to JsonObject(image + ("sha256" to JsonPrimitive("0".repeat(64))))),
        )

        assertThrows(IllegalArgumentException::class.java) {
            json.decodeFromJsonElement(
                RawExactFileObservationResult.serializer(),
                inconsistent,
            )
        }
    }
}
