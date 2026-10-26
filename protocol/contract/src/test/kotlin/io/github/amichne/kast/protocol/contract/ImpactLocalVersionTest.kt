package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactLocalVersionTest {
    private val fixture = ImpactFindingFixture()
    private val first =
        ImpactLocalVersionDocument.Defined(
            range(10, 30),
            range(5, 95),
            range(10, 30),
            range(20, 30),
            ImpactLocalLifetimeEndDocument.NextWrite(range(60, 80), range(70, 80)),
        )
    private val last =
        first.copy(
            definition = range(60, 80),
            rhs = range(70, 80),
            end = ImpactLocalLifetimeEndDocument.BlockEnd(range(5, 95)),
        )

    @Test
    fun `all finite versions encode independent exact shapes without changing unversioned sites`() {
        val versions = listOf(ImpactLocalVersionDocument.Unversioned, first, last)
        val expected = checkNotNull(javaClass.getResource("/impact-local-versions.expected.json")).readText()
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(Json.encodeToString(versions)))
        assertFalse(
            Json.parseToJsonElement(Json.encodeToString(fixture.producer)).jsonObject.containsKey("localVersion")
        )
        val encoded = Json.parseToJsonElement(Json.encodeToString(binding(first))).jsonObject
        assertEquals(
            Json.parseToJsonElement(Json.encodeToString<ImpactLocalVersionDocument>(first)),
            encoded["localVersion"],
        )
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString<ImpactLocalVersionDocument>(Json.encodeToString(UnknownVersion("UNKNOWN")))
        }
    }

    @Test
    fun `read and defining edges retain exact lifetime and declaration identity`() {
        for (allowed in listOf(range(35, 36), range(70, 71))) assertTrue(read(first, allowed).admitsEvidence())
        for (forbidden in listOf(range(20, 21), range(60, 61), range(90, 91))) assertFalse(
            read(first, forbidden).admitsEvidence()
        )
        assertTrue(read(last, range(90, 91)).admitsEvidence())
        assertFalse(read(last, range(70, 71)).admitsEvidence())
        val admitted = read(first, range(35, 36))
        assertFalse(
            admitted
                .copy(
                    target =
                        admitted.target.copy(
                            enclosing = fixture.declaration.copy(file = fixture.text("/workspace/Other.kt"))
                        )
                )
                .admitsEvidence()
        )
        val binding = binding(first)
        assertFalse(binding.hasSameValueIdentity(binding.copy(localVersion = ImpactLocalVersionDocument.Unversioned)))
        assertTrue(
            ImpactCompilerTransferDocument(
                    fixture.producer.copy(range = first.rhs),
                    binding,
                    ImpactTransferKindDocument.LOCAL_BINDING,
                )
                .admitsEvidence()
        )
        assertFalse(
            ImpactCompilerTransferDocument(
                    fixture.producer.copy(range = range(35, 36)),
                    binding,
                    ImpactTransferKindDocument.LOCAL_BINDING,
                )
                .admitsEvidence()
        )
    }

    @Test
    fun `malformed lifetime role and definition anchors fail closed`() {
        assertFalse(binding(first).copy(role = ImpactValueRoleDocument.LocalRead).admitsLocalVersion())
        assertFalse(binding(first).copy(range = range(20, 30)).admitsLocalVersion())
        assertFalse(
            binding(first.copy(end = ImpactLocalLifetimeEndDocument.NextWrite(range(20, 80), range(70, 80))))
                .admitsLocalVersion()
        )
        assertFalse(
            binding(first.copy(end = ImpactLocalLifetimeEndDocument.NextWrite(range(60, 80), range(90, 91))))
                .admitsLocalVersion()
        )
        assertFalse(binding(last.copy(declaration = range(70, 80))).admitsLocalVersion())
    }

    private fun range(start: Int, end: Int) = ImpactSourceRangeDocument(fixture.offset(start), fixture.offset(end))

    private fun binding(version: ImpactLocalVersionDocument.Defined) =
        fixture.producer.copy(
            range = version.definition,
            role = ImpactValueRoleDocument.LocalBinding,
            localVersion = version,
        )

    private fun read(version: ImpactLocalVersionDocument.Defined, range: ImpactSourceRangeDocument) =
        ImpactCompilerTransferDocument(
            binding(version),
            fixture.producer.copy(range = range, role = ImpactValueRoleDocument.LocalRead),
            ImpactTransferKindDocument.LOCAL_READ,
        )
}

@Serializable private data class UnknownVersion(val type: String)
